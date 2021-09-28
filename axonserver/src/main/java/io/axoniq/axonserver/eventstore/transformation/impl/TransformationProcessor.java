/*
 *  Copyright (c) 2017-2021 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.eventstore.transformation.impl;

import io.axoniq.axonserver.grpc.event.DeletedEvent;
import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.TransformEventsRequest;
import io.axoniq.axonserver.grpc.event.TransformedEvent;
import io.axoniq.axonserver.localstorage.LocalEventStore;
import io.axoniq.axonserver.localstorage.file.TransformationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Marc Gathier
 * @since 4.6.0
 */
@Component
public class TransformationProcessor {

    private final Logger logger = LoggerFactory.getLogger(TransformationProcessor.class);

    private final LocalEventStore localEventStore;
    private final TransformationStoreRegistry transformationStoreRegistry;
    private final TransformationCache transformationCache;

    public TransformationProcessor(
            LocalEventStore localEventStore,
            TransformationStoreRegistry transformationStoreRegistry,
            TransformationCache transformationCache) {
        this.localEventStore = localEventStore;
        this.transformationStoreRegistry = transformationStoreRegistry;
        this.transformationCache = transformationCache;
    }

    public void startTransformation(String context, String transformationId) {
        transformationCache.create(context, transformationId);
    }

    public Mono<Void> deleteEvent(String transformationId, long token) {
        return transformationCache.add(transformationId, deleteEventEntry(token));
    }

    public Mono<Void> replaceEvent(String transformationId, long token, Event event) {
        return transformationCache.add(transformationId, replaceEventEntry(token, event));
    }

    public void cancel(String transformationId) {
        transformationStoreRegistry.delete(transformationId);
        transformationCache.delete(transformationId);
    }

    public void complete(String transformationId) {
        transformationStoreRegistry.delete(transformationId);
        transformationCache.complete(transformationId);
    }

    public void apply(String transformationId, boolean keepOldVersions) {
        TransformationEntryStore transformationFileStore = transformationStoreRegistry.get(transformationId);
        EventStoreTransformation transformation = transformationCache.get(transformationId);
        TransformEventsRequest first = transformationFileStore.firstEntry();
        if (first == null) {
            return;
        }
        long firstToken = token(first);
        long lastToken = transformation.previousToken();

        logger.info("{}: Start apply transformation from {} to {}", transformation.context(), first, lastToken);
        CloseableIterator<TransformEventsRequest> iterator = transformationFileStore.iterator(0);
        if (iterator.hasNext()) {
            transformationCache.startApply(transformationId, keepOldVersions);
            TransformEventsRequest transformationEntry = iterator.next();
            AtomicReference<TransformEventsRequest> request = new AtomicReference<>(transformationEntry);
            logger.debug("Next token {}", token(request.get()));
            localEventStore.transformEvents(transformation.context(),
                                            firstToken,
                                            lastToken,
                                            keepOldVersions,
                                            (event, token) -> {
                                                logger.debug("Found token {}", token);
                                                Event result = event;
                                                TransformEventsRequest nextRequest = request.get();
                                                if (token(nextRequest) == token) {
                                                    result = applyTransformation(event, nextRequest);
                                                    if (iterator.hasNext()) {
                                                        request.set(iterator.next());
                                                        logger.debug("Next token {}", token(request.get()));
                                                    }
                                                }
                                                return result;
                                            },
                                            transformationProgress -> handleTransformationProgress( transformation,
                                                    transformationProgress)).thenAccept(r -> {
                iterator.close();
                transformationCache.setTransformationStatus(transformationId, EventStoreTransformationJpa.Status.APPLIED);
            }).exceptionally(ex -> {
                ex.printStackTrace();
                transformationCache.setTransformationStatus(transformationId, EventStoreTransformationJpa.Status.FAILED);
                return null;
            });
        }
    }

    private void handleTransformationProgress(EventStoreTransformation transformation,
                                              TransformationProgress transformationProgress) {
        logger.info("{}: Transformation {} Progress {}", transformation.context(), transformation.id(), transformationProgress);
        transformationCache.setProgress(transformation.id(), transformationProgress);
    }

    private TransformEventsRequest deleteEventEntry(long token) {
        return TransformEventsRequest.newBuilder()
                                     .setDeleteEvent(DeletedEvent.newBuilder()
                                                                 .setToken(token))
                                     .build();
    }

    private TransformEventsRequest replaceEventEntry(long token, Event event) {
        return TransformEventsRequest.newBuilder()
                                     .setEvent(TransformedEvent.newBuilder()
                                                               .setToken(token)
                                                               .setEvent(event))
                                     .build();
    }


    private Event applyTransformation(Event original, TransformEventsRequest transformRequest) {
        switch (transformRequest.getRequestCase()) {
            case EVENT:
                return merge(original, transformRequest.getEvent().getEvent());
            case DELETE_EVENT:
                return nullify(original);
            case REQUEST_NOT_SET:
                break;
        }
        return original;
    }

    private Event merge(Event original, Event updated) {
        return Event.newBuilder(original)
                    .clearMetaData()
                    .setAggregateType(updated.getAggregateType())
                    .setPayload(updated.getPayload())
                    .putAllMetaData(updated.getMetaDataMap())
                    .build();
    }

    private Event nullify(Event event) {
        return Event.newBuilder(event)
                    .clearPayload()
                    .clearMessageIdentifier()
                    .clearMetaData()
                    .build();
    }

    private long token(TransformEventsRequest nextRequest) {
        switch (nextRequest.getRequestCase()) {
            case EVENT:
                return nextRequest.getEvent().getToken();
            case DELETE_EVENT:
                return nextRequest.getDeleteEvent().getToken();
            default:
                throw new IllegalArgumentException("Request without token");
        }
    }

}
