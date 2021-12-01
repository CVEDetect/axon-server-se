/*
 *  Copyright (c) 2017-2021 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.eventstore.transformation.impl;

import io.axoniq.axonserver.config.SystemInfoProvider;
import io.axoniq.axonserver.grpc.SerializedObject;
import io.axoniq.axonserver.grpc.event.DeletedEvent;
import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.TransformEventRequest;
import io.axoniq.axonserver.localstorage.EventTransformationFunction;
import io.axoniq.axonserver.localstorage.LocalEventStoreTransformer;
import io.axoniq.axonserver.localstorage.file.EmbeddedDBProperties;
import io.axoniq.axonserver.localstorage.file.TransformationProgress;
import org.junit.*;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Marc Gathier
 * @since
 */
public class TransformationProcessorTest {

    private static final String NORMAL_TRANSFORMATION = "NORMAL";
    private static final String RESTARTED_TRANSFORMATION = "RESTARTED_TRANSFORMATION";
    public static final String CONTEXT = "SAMPLE";
    private TransformationProcessor testSubject;
    private final AtomicInteger updatesCounter = new AtomicInteger();
    private TransformationStoreRegistry registry = new TransformationStoreRegistry(new EmbeddedDBProperties(new SystemInfoProvider() {
        @Override
        public String getHostName() throws UnknownHostException {
            return "";
        }
    }));

    @Before
    public void setUp() throws Exception {
        TransformationStateManager transformationStateManager = mock(TransformationStateManager.class);
        when(transformationStateManager.entryStore(anyString()))
                .then(invocationOnMock -> registry.get(invocationOnMock.getArgument(0)));
        when(transformationStateManager.transformation(NORMAL_TRANSFORMATION))
                .thenReturn(Optional.of(new EventStoreTransformationJpa(NORMAL_TRANSFORMATION, CONTEXT)));

        EventStoreTransformationJpa inProgressTransformation = new EventStoreTransformationJpa(RESTARTED_TRANSFORMATION, CONTEXT);
        inProgressTransformation.setStatus(EventStoreTransformationJpa.Status.CLOSED);
        inProgressTransformation.setFirstEventToken(100L);
        inProgressTransformation.setLastEventToken(111L);
        when(transformationStateManager.findTransformations(CONTEXT)).thenReturn(Collections.singletonList(inProgressTransformation));

        EventStoreTransformationProgressJpa t = new EventStoreTransformationProgressJpa();
        t.setLastTokenApplied(110);
        when(transformationStateManager.progress(RESTARTED_TRANSFORMATION)).thenReturn(Optional.of(t));

        ApplicationContext applicationContext = mock(ApplicationContext.class);
        LocalEventStoreTransformer localEventStore = new LocalEventStoreTransformer() {
            @Override
            public void deleteOldVersions(String context, int version) {

            }

            @Override
            public void rollbackSegments(String context, int version) {

            }

            @Override
            public Flux<TransformationProgress> transformEvents(String context, long firstToken, long lastToken,
                                                                boolean keepOldVersions, int version,
                                                                EventTransformationFunction transformationFunction) {
                LongStream.range(firstToken, lastToken + 1)
                          .forEach(i -> {
                              Event original = Event.newBuilder().setPayload(SerializedObject.newBuilder()
                                                                                             .setType("PayloadType")
                                                                                             .build()).build();
                              Event updated = transformationFunction.apply(original, i).event();
                              if (!original.equals(updated)) {
                                  updatesCounter.incrementAndGet();
                              }

                          });
                return Flux.empty();
            }
        };
        when(applicationContext.getBean(LocalEventStoreTransformer.class)).thenReturn(localEventStore);

        testSubject = new TransformationProcessor(applicationContext, transformationStateManager);
    }

    @After
    public void cleanup() {
        registry.delete(NORMAL_TRANSFORMATION);
        registry.delete(RESTARTED_TRANSFORMATION);
    }

    @Test
    public void applyEmpty() {
        registry.register(CONTEXT, NORMAL_TRANSFORMATION);

        StepVerifier.create(testSubject.apply(NORMAL_TRANSFORMATION, true, "Junit", new Date(), 0, 0))
                    .expectComplete()
                    .verify();
    }

    @Test
    public void applyNormal() {
        TransformationEntryStore store = registry.register(CONTEXT, NORMAL_TRANSFORMATION);
        store.append(TransformEventRequest.newBuilder().setDeleteEvent(DeletedEvent.newBuilder().setToken(100)).build())
             .block();
        store.append(TransformEventRequest.newBuilder().setDeleteEvent(DeletedEvent.newBuilder().setToken(101)).build())
             .block();
        store.append(TransformEventRequest.newBuilder().setDeleteEvent(DeletedEvent.newBuilder().setToken(111)).build())
             .block();

        StepVerifier.create(testSubject.apply(NORMAL_TRANSFORMATION, true, "Junit", new Date(), 100, 150))
                    .expectComplete()
                    .verify();
        assertEquals(3, updatesCounter.get());
    }

    @Test
    public void restartApply() throws ExecutionException, InterruptedException, TimeoutException {
        TransformationEntryStore store = registry.register(CONTEXT, RESTARTED_TRANSFORMATION);
        store.append(TransformEventRequest.newBuilder().setDeleteEvent(DeletedEvent.newBuilder().setToken(100)).build()).block();
        store.append(TransformEventRequest.newBuilder().setDeleteEvent(DeletedEvent.newBuilder().setToken(101)).build()).block();
        store.append(TransformEventRequest.newBuilder().setDeleteEvent(DeletedEvent.newBuilder().setToken(111)).build()).block();

        testSubject.restartApply(CONTEXT).get(1, TimeUnit.SECONDS);
        assertEquals(1, updatesCounter.get());
    }
}