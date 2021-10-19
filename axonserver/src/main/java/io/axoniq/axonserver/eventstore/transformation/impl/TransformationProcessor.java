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
import org.springframework.context.ApplicationContext;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.lang.Math.max;

/**
 * @author Marc Gathier
 * @since 4.6.0
 */
@Component
public class TransformationProcessor {

    private final Logger logger = LoggerFactory.getLogger(TransformationProcessor.class);
    private final TransformationStoreRegistry transformationStoreRegistry;
    private final TransformationCache transformationCache;
    private final EventStoreTransformationRepository transformationRepository;
    private final ApplicationContext applicationContext;

    public TransformationProcessor(
            ApplicationContext applicationContext,
            TransformationStoreRegistry transformationStoreRegistry,
            TransformationCache transformationCache,
            EventStoreTransformationRepository transformationRepository) {
        this.applicationContext = applicationContext;
        this.transformationRepository = transformationRepository;
        this.transformationStoreRegistry = transformationStoreRegistry;
        this.transformationCache = transformationCache;
    }

    public void startTransformation(String context, String transformationId, int version, String description) {
        transformationCache.create(context, transformationId, version, description);
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

    public CompletableFuture<Void> apply(String transformationId, boolean keepOldVersions, String appliedBy,
                                         Date appliedDate) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            TransformationEntryStore transformationFileStore = transformationStoreRegistry.get(transformationId);
            EventStoreTransformationJpa transformation = transformationCache.transformation(transformationId)
                                                                            .orElseThrow(() -> new RuntimeException(
                                                                                    "Transformation not found"));

            return doApply(transformationFileStore,
                        transformation.getContext(),
                        transformationId,
                        keepOldVersions,
                        transformation.getVersion(),
                           appliedBy, appliedDate,
                        0);
        } catch (Exception ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    private CompletableFuture<Void> doApply(TransformationEntryStore transformationFileStore, String context,
                                            String transformationId, boolean keepOldVersions, int version,
                                            String appliedBy, Date appliedDate, long nextToken) {

        TransformEventsRequest first = transformationFileStore.firstEntry();
        if (first == null) {
            return CompletableFuture.completedFuture(null);
        }
        long firstToken = max(token(first), nextToken);
        long lastToken = token(transformationFileStore.lastEntry());

        logger.info("{}: Start apply transformation from {} to {}", context, firstToken, lastToken);
        CloseableIterator<TransformEventsRequest> iterator = transformationFileStore.iterator(0);
        if (iterator.hasNext()) {
            transformationCache.startApply(transformationId, keepOldVersions, appliedBy, appliedDate);
            TransformEventsRequest transformationEntry = iterator.next();
            AtomicReference<TransformEventsRequest> request = new AtomicReference<>(transformationEntry);
            logger.debug("Next token {}", token(request.get()));
            return localEventStore().transformEvents(context,
                                                   firstToken,
                                                   lastToken,
                                                   keepOldVersions,
                                                   version,
                                                   (event, token) -> {
                                                       logger.debug("Found token {}", token);
                                                       Event result = event;
                                                       TransformEventsRequest nextRequest = request.get();
                                                       long t = token(nextRequest);
                                                       while (t < token && iterator.hasNext()) {
                                                           nextRequest = iterator.next();
                                                           request.set(nextRequest);
                                                           t = token(nextRequest);
                                                       }

                                                       if (token(nextRequest) == token) {
                                                           result = applyTransformation(event, nextRequest);
                                                           if (iterator.hasNext()) {
                                                               request.set(iterator.next());
                                                               logger.debug("Next token {}", token(request.get()));
                                                           }
                                                       }
                                                       return result;
                                                   },
                                                   transformationProgress -> handleTransformationProgress(context,
                                                                                                          transformationId,
                                                                                                          transformationProgress))
                                  .thenAccept(r -> {
                                      iterator.close();
                                      transformationCache.completeTransformation(transformationId);
                                  });
        }
        return CompletableFuture.completedFuture(null);
    }

    private void handleTransformationProgress(String context, String transformationId,
                                              TransformationProgress transformationProgress) {
        logger.info("{}: Transformation {} Progress {}", context, transformationId, transformationProgress);
        transformationCache.setProgress(transformationId, transformationProgress);
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

    public CompletableFuture<String> restartApply(String context) {
        CompletableFuture<String> future = new CompletableFuture<>();
        List<EventStoreTransformationJpa> toApply = transformationCache.findTransformation(context)
                                                                       .stream()
                                                                       .filter(transformation -> EventStoreTransformationJpa.Status.CLOSED.equals(
                                                                               transformation.getStatus()))
                                                                       .collect(Collectors.toList());
        if (toApply.isEmpty()) {
            future.complete(null);
        } else if (toApply.size() > 1) {
            logger.warn("{}: {} transformations to apply", context, toApply.size());
            future.complete(null);
        } else {
            EventStoreTransformationJpa transformation = toApply.get(0);
            Optional<EventStoreTransformationProgress> progress = transformationCache.progress(transformation.getTransformationId());
            if(!progress.isPresent()) {
                future.complete(null);
            } else {
                TransformationEntryStore transformationFileStore = transformationStoreRegistry.get(
                        transformation.getTransformationId());
                transformationCache.reserve(context, transformation.getTransformationId());
                return doApply(transformationFileStore,
                               transformation.getContext(),
                               transformation.getTransformationId(),
                               transformation.isKeepOldVersions(),
                               transformation.getVersion(),
                               transformation.getAppliedBy(),
                               transformation.getDateApplied(),
                               progress.get().getLastTokenApplied())
                        .thenApply(v -> transformation.getTransformationId());
            }
        }
        return future;
    }

    public void deleteOldVersions(String context, String id) {
        transformationRepository.findById(id)
                                .ifPresent(transformation -> {
                                    localEventStore().deleteSegments(context, transformation.getVersion());
                                });
    }

    public void rollbackTransformation(String context, String transformationId) {
        transformationRepository.findById(transformationId)
                                .ifPresent(transformation -> {
                                    localEventStore().rollbackSegments(context, transformation.getVersion());
                                    transformationCache.delete(transformationId);
                                });
    }

    public void checkApplyProcessor(String id) {
        transformationCache.transformation(id)
                .ifPresent(transformation -> {
                    if (EventStoreTransformationJpa.Status.CLOSED.equals(transformation.getStatus())) {
                        apply(id, transformation.isKeepOldVersions(),
                              transformation.getAppliedBy(),
                              transformation.getDateApplied());
                    }
                });
    }

    private LocalEventStore localEventStore() {
        return applicationContext.getBean(LocalEventStore.class);
    }
}
