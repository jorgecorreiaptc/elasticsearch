/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.execution;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.Preference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.xpack.watcher.support.init.proxy.WatcherClientProxy;
import org.elasticsearch.xpack.watcher.watch.Watch;
import org.elasticsearch.xpack.watcher.watch.WatchStoreUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.watcher.support.Exceptions.illegalState;
import static org.elasticsearch.xpack.watcher.support.Exceptions.ioException;

public class TriggeredWatchStore extends AbstractComponent {

    public static final String INDEX_NAME = ".triggered_watches";
    public static final String DOC_TYPE = "triggered_watch";

    private final int scrollSize;
    private final WatcherClientProxy client;
    private final TimeValue scrollTimeout;
    private final TriggeredWatch.Parser triggeredWatchParser;

    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock accessLock = readWriteLock.readLock();
    private final Lock stopLock = readWriteLock.writeLock();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public TriggeredWatchStore(Settings settings, WatcherClientProxy client, TriggeredWatch.Parser triggeredWatchParser) {
        super(settings);
        this.scrollSize = settings.getAsInt("xpack.watcher.execution.scroll.size", 1000);
        this.client = client;
        this.scrollTimeout = settings.getAsTime("xpack.watcher.execution.scroll.timeout", TimeValue.timeValueSeconds(30));
        this.triggeredWatchParser = triggeredWatchParser;
        this.started.set(true);
    }

    public void start() {
        started.set(true);
    }

    public boolean validate(ClusterState state) {
        try {
            IndexMetaData indexMetaData = WatchStoreUtils.getConcreteIndex(INDEX_NAME, state.metaData());
            if (indexMetaData == null) {
                return true;
            } else {
                if (indexMetaData.getState() == IndexMetaData.State.CLOSE) {
                    logger.debug("triggered watch index [{}] is marked as closed, watcher cannot be started",
                            indexMetaData.getIndex().getName());
                    return false;
                } else {
                    return state.routingTable().index(indexMetaData.getIndex()).allPrimaryShardsActive();
                }
            }
        } catch (IllegalStateException e) {
            logger.trace((Supplier<?>) () -> new ParameterizedMessage("error getting index meta data [{}]: ", INDEX_NAME), e);
            return false;
        }
    }

    public void stop() {
        stopLock.lock(); // This will block while put or update actions are underway
        try {
            started.set(false);
        } finally {
            stopLock.unlock();
        }
    }

    public void putAll(final List<TriggeredWatch> triggeredWatches, final ActionListener<BitSet> listener) {
        if (triggeredWatches.isEmpty()) {
            listener.onResponse(new BitSet(0));
            return;
        }

        ensureStarted();
        BulkRequest request = new BulkRequest();
        for (TriggeredWatch triggeredWatch : triggeredWatches) {
            try {
                IndexRequest indexRequest = new IndexRequest(INDEX_NAME, DOC_TYPE, triggeredWatch.id().value());
                try (XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()) {
                    indexRequest.source(xContentBuilder.value(triggeredWatch));
                }
                indexRequest.opType(IndexRequest.OpType.CREATE);
                request.add(indexRequest);
            } catch (IOException e) {
                logger.warn("could not create JSON to store triggered watch [{}]", triggeredWatch.id().value());
            }
        }
        client.bulk(request, ActionListener.wrap(response -> {
            BitSet successFullSlots = new BitSet(triggeredWatches.size());
            for (int i = 0; i < response.getItems().length; i++) {
                BulkItemResponse itemResponse = response.getItems()[i];
                if (itemResponse.isFailed()) {
                    logger.error("could not store triggered watch with id [{}], failed [{}]", itemResponse.getId(),
                            itemResponse.getFailureMessage());
                } else {
                    successFullSlots.set(i);
                }
            }
            listener.onResponse(successFullSlots);
        }, listener::onFailure));
    }

    public void put(TriggeredWatch triggeredWatch) throws Exception {
        putAll(Collections.singletonList(triggeredWatch));
    }

    public BitSet putAll(final List<TriggeredWatch> triggeredWatches) throws Exception {
        ensureStarted();
        try {
            BulkRequest request = new BulkRequest();
            for (TriggeredWatch triggeredWatch : triggeredWatches) {
                IndexRequest indexRequest = new IndexRequest(INDEX_NAME, DOC_TYPE, triggeredWatch.id().value());
                try (XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()) {
                    indexRequest.source(xContentBuilder.value(triggeredWatch));
                }
                indexRequest.opType(IndexRequest.OpType.CREATE);
                request.add(indexRequest);
            }
            BulkResponse response = client.bulk(request, (TimeValue) null);
            BitSet successFullSlots = new BitSet(triggeredWatches.size());
            for (int i = 0; i < response.getItems().length; i++) {
                BulkItemResponse itemResponse = response.getItems()[i];
                if (itemResponse.isFailed()) {
                    logger.error("could store triggered watch with id [{}], because failed [{}]", itemResponse.getId(),
                            itemResponse.getFailureMessage());
                } else {
                    successFullSlots.set(i);
                }
            }
            return successFullSlots;
        } catch (IOException e) {
            throw ioException("failed to persist triggered watches", e);
        }
    }

    public void delete(Wid wid) {
        ensureStarted();
        accessLock.lock();
        try {
            DeleteRequest request = new DeleteRequest(INDEX_NAME, DOC_TYPE, wid.value());
            client.delete(request);
            logger.trace("successfully deleted triggered watch with id [{}]", wid);
        } finally {
            accessLock.unlock();
        }
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw illegalState("unable to persist triggered watches, the store is not ready");
        }
    }

    /**
     * Checks if any of the loaded watches has been put into the triggered watches index for immediate execution
     *
     * Note: This is executing a blocking call over the network, thus a potential source of problems
     *
     * @param watches The list of watches that will be loaded here
     * @return A list of triggered watches that have been started to execute somewhere else but not finished
     */
    public Collection<TriggeredWatch> findTriggeredWatches(Collection<Watch> watches) {
        if (watches.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            client.refresh(new RefreshRequest(TriggeredWatchStore.INDEX_NAME));
        } catch (IndexNotFoundException e) {
            // no index, no problems, we dont need to search further
            return Collections.emptyList();
        }

        Set<String> ids = watches.stream().map(Watch::id).collect(Collectors.toSet());
        Collection<TriggeredWatch> triggeredWatches = new ArrayList<>(ids.size());

        SearchRequest searchRequest = new SearchRequest(TriggeredWatchStore.INDEX_NAME)
                .types(TriggeredWatchStore.DOC_TYPE)
                .scroll(scrollTimeout)
                .preference(Preference.LOCAL.toString())
                .source(new SearchSourceBuilder()
                        .size(scrollSize)
                        .sort(SortBuilders.fieldSort("_doc"))
                        .version(true));

        SearchResponse response = client.search(searchRequest);
        logger.debug("trying to find triggered watches for ids {}: found [{}] docs", ids, response.getHits().getTotalHits());
        try {
            while (response.getHits().getHits().length != 0) {
                for (SearchHit hit : response.getHits()) {
                    Wid wid = new Wid(hit.getId());
                    if (ids.contains(wid.watchId())) {
                        TriggeredWatch triggeredWatch = triggeredWatchParser.parse(hit.getId(), hit.getVersion(), hit.getSourceRef());
                        triggeredWatches.add(triggeredWatch);
                    }
                }
                response = client.searchScroll(response.getScrollId(), scrollTimeout);
            }
        } finally {
            client.clearScroll(response.getScrollId());
        }

        return triggeredWatches;
    }

}
