/*
 *  Copyright (c) 2017-2022 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.localstorage.file;

import io.axoniq.axonserver.config.FileSystemMonitor;
import io.axoniq.axonserver.config.SystemInfoProvider;
import io.axoniq.axonserver.localstorage.EventType;
import io.axoniq.axonserver.localstorage.EventTypeContext;
import io.axoniq.axonserver.localstorage.SerializedEvent;
import io.axoniq.axonserver.localstorage.transformation.DefaultEventTransformerFactory;
import io.axoniq.axonserver.metric.DefaultMetricCollector;
import io.axoniq.axonserver.metric.MeterFactory;
import io.axoniq.axonserver.test.TestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.UnknownHostException;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * @author Marc Gathier
 */
public class MultipleSnapshotSegments {

    private PrimaryEventStore testSubject;

    private FileSystemMonitor fileSystemMonitor = mock(FileSystemMonitor.class);

    File sampleEventStoreFolder = new File(TestUtils
                                                   .fixPathOnWindows(InputStreamEventStore.class
                                                                             .getResource("/multiple-snapshot-segments")
                                                                             .getFile()));

    @Before
    public void init() {
        MeterFactory meterFactory = new MeterFactory(new SimpleMeterRegistry(), new DefaultMetricCollector());

        doNothing().when(fileSystemMonitor).registerPath(any(), any());

        StorageProperties storageProperties = new StorageProperties(new SystemInfoProvider() {
            @Override
            public String getHostName() throws UnknownHostException {
                return null;
            }
        }, ".snapshots", ".sindex", ".sbloom", ".snindex", ".sxref").withStorage(sampleEventStoreFolder
                                                                                         .getAbsolutePath());

        IndexManager indexManager = new StandardIndexManager("default",
                                                             () -> storageProperties,
                                                             "storage",
                                                             EventType.SNAPSHOT,
                                                             meterFactory);

        InputStreamEventStore secondaryEventStore = new InputStreamEventStore(new EventTypeContext("default",
                                                                                                   EventType.SNAPSHOT),
                                                                              indexManager,
                                                                              new DefaultEventTransformerFactory(),
                                                                              () -> storageProperties,
                                                                              meterFactory,
                                                                              storageProperties.getPrimaryStorage("default"));
        testSubject = new PrimaryEventStore(new EventTypeContext("default", EventType.SNAPSHOT),
                                            indexManager,
                                            new DefaultEventTransformerFactory(),
                                            () -> storageProperties,
                                            () -> secondaryEventStore,
                                            meterFactory, fileSystemMonitor);
        testSubject.init(true);
    }

    @Test
    public void readSnapshotInFirstSegment() {
        testSubject.init(true);
        Optional<SerializedEvent> snapshot = testSubject.getLastEvent(
                "Aggregate-325",
                0, Long.MAX_VALUE);
        assertTrue(snapshot.isPresent());
        assertEquals(1, snapshot.get().getAggregateSequenceNumber());
    }

    @Test
    public void readSnapshotInLastSegment() {
        testSubject.init(true);
        Optional<SerializedEvent> snapshot = testSubject.getLastEvent(
                "Aggregate-360",
                0, Long.MAX_VALUE);
        assertTrue(snapshot.isPresent());
        assertEquals(1, snapshot.get().getAggregateSequenceNumber());
    }
}
