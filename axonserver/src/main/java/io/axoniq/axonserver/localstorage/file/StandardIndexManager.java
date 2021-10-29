/*
 * Copyright (c) 2017-2019 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 * under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.localstorage.file;

import io.axoniq.axonserver.exception.ErrorCode;
import io.axoniq.axonserver.exception.MessagingPlatformException;
import io.axoniq.axonserver.localstorage.EventType;
import io.axoniq.axonserver.metric.BaseMetricName;
import io.axoniq.axonserver.metric.MeterFactory;
import io.axoniq.axonserver.util.DaemonThreadFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tags;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Implementation of the index manager that creates 2 files per segment, an index file containing a map of aggregate
 * identifiers and the position of events for this aggregate and a bloom filter to quickly check if an aggregate occurs
 * in the segment.
 *
 * @author Marc Gathier
 * @since 4.4
 */
public class StandardIndexManager implements IndexManager {

    private static final Logger logger = LoggerFactory.getLogger(StandardIndexManager.class);
    private static final String AGGREGATE_MAP = "aggregateMap";
    private static final ScheduledExecutorService scheduledExecutorService =
            Executors.newScheduledThreadPool(1, new DaemonThreadFactory("index-manager-"));
    protected final StorageProperties storageProperties;
    protected final String context;
    private final EventType eventType;
    private final ConcurrentNavigableMap<Long, Map<String, IndexEntries>> activeIndexes = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<FileVersion, PersistedBloomFilter> bloomFilterPerSegment = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<FileVersion, Index> indexMap = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<Long, Integer> indexesDescending = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
    private final MeterFactory.RateMeter indexOpenMeter;
    private final MeterFactory.RateMeter indexCloseMeter;
    private final RemoteAggregateSequenceNumberResolver remoteIndexManager;
    private final AtomicLong useMmapAfterIndex = new AtomicLong();
    private final Counter bloomFilterOpenMeter;
    private final Counter bloomFilterCloseMeter;
    private ScheduledFuture<?> cleanupTask;

    /**
     * @param context           the context of the storage engine
     * @param storageProperties storage engine configuration
     * @param eventType         content type of the event store (events or snapshots)
     * @param meterFactory      factory to create metrics meter
     */
    public StandardIndexManager(String context, StorageProperties storageProperties, EventType eventType,
                                MeterFactory meterFactory) {
        this(context, storageProperties, eventType, null, meterFactory);
    }

    /**
     * @param context            the context of the storage engine
     * @param storageProperties  storage engine configuration
     * @param eventType          content type of the event store (events or snapshots)
     * @param remoteIndexManager component that provides last sequence number for old aggregates
     * @param meterFactory       factory to create metrics meter
     */
    public StandardIndexManager(String context, StorageProperties storageProperties, EventType eventType,
                                RemoteAggregateSequenceNumberResolver remoteIndexManager,
                                MeterFactory meterFactory) {
        this.storageProperties = storageProperties;
        this.context = context;
        this.eventType = eventType;
        this.remoteIndexManager = remoteIndexManager;
        Tags tags = Tags.of(MeterFactory.CONTEXT, context, "type", eventType.name());
        this.indexOpenMeter = meterFactory.rateMeter(BaseMetricName.AXON_INDEX_OPEN, tags);
        this.indexCloseMeter = meterFactory.rateMeter(BaseMetricName.AXON_INDEX_CLOSE, tags);
        this.bloomFilterOpenMeter = meterFactory.counter(BaseMetricName.AXON_BLOOM_OPEN, tags);
        this.bloomFilterCloseMeter = meterFactory.counter(BaseMetricName.AXON_BLOOM_CLOSE, tags);
        scheduledExecutorService.scheduleAtFixedRate(this::indexCleanup, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Initializes the index manager.
     */
    public void init() {
        String[] indexFiles = FileUtils.getFilesWithSuffix(new File(storageProperties.getStorage(context)),
                                                           storageProperties.getIndexSuffix());
        for (String indexFile : indexFiles) {
            FileVersion fileVersion = FileUtils.process(indexFile);
            indexesDescending.compute(fileVersion.segment(), (s,old) -> old == null ? fileVersion.version() : Math.max(
                    fileVersion.version(), old));
        }

        updateUseMmapAfterIndex();
    }

    private void updateUseMmapAfterIndex() {
        useMmapAfterIndex.set(indexesDescending.keySet().stream().skip(storageProperties.getMaxIndexesInMemory()).findFirst()
                                               .orElse(-1L));
    }

    private void createIndex(FileVersion segment, Map<String, IndexEntries> positionsPerAggregate) {
        if (positionsPerAggregate == null) {
            positionsPerAggregate = Collections.emptyMap();
        }
        File tempFile = storageProperties.indexTemp(context, segment.segment());
        if (!FileUtils.delete(tempFile)) {
            throw new MessagingPlatformException(ErrorCode.INDEX_WRITE_ERROR,
                                                 "Failed to delete temp index file:" + tempFile);
        }
        DBMaker.Maker maker = DBMaker.fileDB(tempFile);
        if (storageProperties.isUseMmapIndex()) {
            maker.fileMmapEnable();
            if (storageProperties.isForceCleanMmapIndex()) {
                maker.cleanerHackEnable();
            }
        } else {
            maker.fileChannelEnable();
        }
        DB db = maker.make();
        try (HTreeMap<String, IndexEntries> map = db.hashMap(AGGREGATE_MAP, Serializer.STRING,
                                                             StandardIndexEntriesSerializer.get())
                                                    .createOrOpen()) {
            map.putAll(positionsPerAggregate);
        }
        db.close();

        try {
            Files.move(tempFile.toPath(), storageProperties.index(context, segment).toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MessagingPlatformException(ErrorCode.INDEX_WRITE_ERROR,
                                                 "Failed to rename index file" + storageProperties
                                                         .index(context, segment),
                                                 e);
        }

        PersistedBloomFilter filter = new PersistedBloomFilter(storageProperties.bloomFilter(context, segment)
                                                                                .getAbsolutePath(),
                                                               positionsPerAggregate.keySet().size(),
                                                               storageProperties.getBloomIndexFpp());
        filter.create();
        filter.insertAll(positionsPerAggregate.keySet());
        filter.store();
        bloomFilterPerSegment.put(segment, filter);
        getIndex(segment);
    }

    private IndexEntries getPositions(FileVersion fileVersion, String aggregateId) {
        if (notInBloomIndex(fileVersion, aggregateId)) {
            return null;
        }

        RuntimeException lastError = new RuntimeException();
        for (int retry = 0; retry < 3; retry++) {
            try {
                Index idx = getIndex(fileVersion);
                return idx.getPositions(aggregateId);
            } catch (IndexNotFoundException ex) {
                return null;
            } catch (Exception ex) {
                lastError = new RuntimeException(
                        "Error happened while trying get positions for " + fileVersion.segment() + " segment.", ex);
            }
        }
        throw lastError;
    }

    private Index getIndex(FileVersion fileVersion) {
        try {
            return indexMap.computeIfAbsent(fileVersion, Index::new).ensureReady();
        } catch (IndexNotFoundException indexNotFoundException) {
            indexMap.remove(fileVersion);
            throw indexNotFoundException;
        }
    }

    private void indexCleanup() {
        while (indexMap.size() > storageProperties.getMaxIndexesInMemory()) {
            Map.Entry<FileVersion, Index> entry = indexMap.pollFirstEntry();
            logger.debug("{}: Closing index {}", context, entry.getKey());
            cleanupTask = scheduledExecutorService.schedule(() -> entry.getValue().close(), 2, TimeUnit.SECONDS);
        }

        while (bloomFilterPerSegment.size() > storageProperties.getMaxBloomFiltersInMemory()) {
            Map.Entry<FileVersion, PersistedBloomFilter> removed = bloomFilterPerSegment.pollFirstEntry();
            logger.debug("{}: Removed bloom filter for {} from memory", context, removed.getKey());
            bloomFilterCloseMeter.increment();
        }
    }

    private boolean notInBloomIndex(FileVersion fileVersion, String aggregateId) {
        PersistedBloomFilter persistedBloomFilter = bloomFilterPerSegment.computeIfAbsent(fileVersion,
                                                                                          this::loadBloomFilter);
        return persistedBloomFilter != null && !persistedBloomFilter.mightContain(aggregateId);
    }

    private PersistedBloomFilter loadBloomFilter(FileVersion fileVersion) {
        logger.debug("{}: open bloom filter for {}", context, fileVersion.segment());
        PersistedBloomFilter filter = new PersistedBloomFilter(storageProperties.bloomFilter(context, fileVersion)
                                                                                .getAbsolutePath(), 0, 0.03f);
        if (!filter.fileExists()) {
            return null;
        }
        bloomFilterOpenMeter.increment();
        filter.load();
        return filter;
    }

    /**
     * Adds the position of an event for an aggregate to an active (writable) index.
     *
     * @param segment     the segment number
     * @param aggregateId the identifier for the aggregate
     * @param indexEntry  position, sequence number and token of the new entry
     */
    @Override
    public void addToActiveSegment(long segment, String aggregateId, IndexEntry indexEntry) {
        if (indexesDescending.containsKey(segment)) {
            throw new IndexNotFoundException(segment + ": already completed");
        }
        activeIndexes.computeIfAbsent(segment, s -> new ConcurrentHashMap<>())
                     .computeIfAbsent(aggregateId, a -> new StandardIndexEntries(indexEntry.getSequenceNumber()))
                     .add(indexEntry);
    }

    /**
     * Adds positions of a number of events for aggregates to an active (writable) index.
     *
     * @param segment      the segment number
     * @param indexEntries the new entries to add
     */
    @Override
    public void addToActiveSegment(Long segment, Map<String, List<IndexEntry>> indexEntries) {
        if (indexesDescending.containsKey(segment)) {
            throw new IndexNotFoundException(segment + ": already completed");
        }

        indexEntries.forEach((aggregateId, entries) ->
                                     activeIndexes.computeIfAbsent(segment, s -> new ConcurrentHashMap<>())
                                                  .computeIfAbsent(aggregateId,
                                                                   a -> new StandardIndexEntries(
                                                                           entries.get(0).getSequenceNumber()))
                                                  .addAll(entries));
    }

    @Override
    public void activeVersion(long segment, int version) {
        indexesDescending.put(segment, version);
    }

    @Override
    public void createNewVersion(long segment, int version, Map<String, List<IndexEntry>> indexEntriesMap) {
        FileVersion newVersion = new FileVersion(segment, version);
        if (indexEntriesMap == null) {
            indexEntriesMap = Collections.emptyMap();
        }
        File tempFile = storageProperties.index(context, newVersion);
        if (!FileUtils.delete(tempFile)) {
            throw new MessagingPlatformException(ErrorCode.INDEX_WRITE_ERROR,
                                                 "Failed to delete temp index file:" + tempFile);
        }
        DBMaker.Maker maker = DBMaker.fileDB(tempFile);
        if (storageProperties.isUseMmapIndex()) {
            maker.fileMmapEnable();
            if (storageProperties.isForceCleanMmapIndex()) {
                maker.cleanerHackEnable();
            }
        } else {
            maker.fileChannelEnable();
        }
        DB db = maker.make();
        try (HTreeMap<String, IndexEntries> map = db.hashMap(AGGREGATE_MAP, Serializer.STRING,
                                                             StandardIndexEntriesSerializer.get())
                                                    .createOrOpen()) {
            indexEntriesMap.forEach((key, value) -> {
                IndexEntry first = value.get(0);
                Integer[] positions = new Integer[value.size()];
                for (int i = 0; i < value.size(); i++) {
                    positions[i] = value.get(i).getPosition();
                }
                map.put(key, new StandardIndexEntries(first.getSequenceNumber(),
                                                      positions));
            });
        }
        db.close();
        PersistedBloomFilter filter = new PersistedBloomFilter(storageProperties.bloomFilter(context, newVersion)
                                                                                .getAbsolutePath(),
                                                               indexEntriesMap.keySet().size(),
                                                               storageProperties.getBloomIndexFpp());
        filter.create();
        filter.insertAll(indexEntriesMap.keySet());
        filter.store();

        indexesDescending.put(segment, version);
    }

    /**
     * Commpletes an active index.
     *
     * @param segment the first token in the segment
     */
    @Override
    public void complete(FileVersion segment) {
        createIndex(segment, activeIndexes.get(segment.segment()));
        indexesDescending.put(segment.segment(), segment.version());
        activeIndexes.remove(segment.segment());
        updateUseMmapAfterIndex();
    }

    /**
     * Returns the last sequence number of an aggregate if this is found.
     *
     * @param aggregateId  the identifier for the aggregate
     * @param maxSegments  maximum number of segments to check for the aggregate
     * @param maxTokenHint maximum token to check
     * @return last sequence number for the aggregate (if found)
     */
    @Override
    public Optional<Long> getLastSequenceNumber(String aggregateId, int maxSegments, long maxTokenHint) {
        if (activeIndexes.isEmpty()) {
            return Optional.empty();
        }
        int checked = 0;
        for (Long segment : activeIndexes.descendingKeySet()) {
            if (checked >= maxSegments) {
                return Optional.empty();
            }
            if (segment <= maxTokenHint) {
                IndexEntries indexEntries = activeIndexes.get(segment).get(aggregateId);
                if (indexEntries != null) {
                    return Optional.of(indexEntries.lastSequenceNumber());
                }
                checked++;
            }
        }
        for (Map.Entry<Long, Integer> segment : indexesDescending.entrySet()) {
            if (checked >= maxSegments) {
                return Optional.empty();
            }
            if (segment.getKey() <= maxTokenHint) {
                IndexEntries indexEntries = getPositions(new FileVersion(segment.getKey(), segment.getValue()), aggregateId);
                if (indexEntries != null) {
                    return Optional.of(indexEntries.lastSequenceNumber());
                }
                checked++;
            }
        }
        if (remoteIndexManager != null && checked < maxSegments) {
            return remoteIndexManager.getLastSequenceNumber(context,
                                                            aggregateId,
                                                            maxSegments - checked,
                                                            indexesDescending.isEmpty() ?
                                                                    activeIndexes.firstKey() - 1 :
                                                                    indexesDescending.keySet().last() - 1);
        }
        return Optional.empty();
    }

    /**
     * Returns the position of the last event for an aggregate.
     *
     * @param aggregateId       the aggregate identifier
     * @param maxSequenceNumber maximum sequence number of the event to find (exclusive)
     * @return
     */
    @Override
    public SegmentIndexEntries lastIndexEntries(String aggregateId, long maxSequenceNumber) {
        for (Long segment : activeIndexes.descendingKeySet()) {
            IndexEntries indexEntries = activeIndexes.get(segment).get(aggregateId);
            if (indexEntries != null && indexEntries.firstSequenceNumber() < maxSequenceNumber) {
                return new SegmentIndexEntries(new FileVersion(segment, 0), indexEntries.range(indexEntries.firstSequenceNumber(),
                                                                           maxSequenceNumber,
                                                                           EventType.SNAPSHOT.equals(eventType)));
            }
        }
        for (Map.Entry<Long, Integer> segment : indexesDescending.entrySet()) {
            IndexEntries indexEntries = getPositions(new FileVersion(segment.getKey(),segment.getValue()), aggregateId);
            if (indexEntries != null && indexEntries.firstSequenceNumber() < maxSequenceNumber) {
                return new SegmentIndexEntries(new FileVersion(segment.getKey(), segment.getValue()), indexEntries.range(indexEntries.firstSequenceNumber(),
                                                                           maxSequenceNumber,
                                                                           EventType.SNAPSHOT.equals(eventType)));
            }
        }
        return null;
    }

    /**
     * Checks if the index and bloom filter for the segment exist.
     *
     * @param segment the segment number
     * @return true if the index for this segment is valid
     */
    @Override
    public boolean validIndex(FileVersion segment) {
        try {
            if (indexesDescending.containsKey(segment.segment()) && indexesDescending.get(segment.segment()) == segment.version()) {
                return loadBloomFilter(segment) != null && getIndex(segment) != null;
            }
        } catch (Exception ex) {
            logger.warn("Failed to validate index for segment: {}", segment, ex);
        }
        return false;
    }

    /**
     * Removes the index and bloom filter for the segment
     *
     * @param segment the segment number
     */
    @Override
    public boolean remove(long segment) {
        if (activeIndexes.remove(segment) == null) {
            Integer version = indexesDescending.remove(segment);
            if ( version != null) {
                FileVersion fileVersion = new FileVersion(segment, version);
                Index index = indexMap.remove(fileVersion);
                if (index != null) {
                    index.close();
                }
                bloomFilterPerSegment.remove(fileVersion);
            }
        }
        return FileUtils.delete(storageProperties.index(context, segment)) &&
                FileUtils.delete(storageProperties.bloomFilter(context, segment));
    }

    @Override
    public boolean remove(FileVersion fileVersion) {
        Index index = indexMap.remove(fileVersion);
        if (index != null) {
            index.close();
        }
        bloomFilterPerSegment.remove(fileVersion);
        return FileUtils.delete(storageProperties.index(context, fileVersion)) &&
                FileUtils.delete(storageProperties.bloomFilter(context, fileVersion));
    }

    /**
     * Finds all positions for an aggregate within the specified sequence number range.
     *
     * @param aggregateId         the aggregate identifier
     * @param firstSequenceNumber minimum sequence number for the events returned (inclusive)
     * @param lastSequenceNumber  maximum sequence number for the events returned (exclusive)
     * @param maxResults          maximum number of results allowed
     * @return all positions for an aggregate within the specified sequence number range
     */
    @Override
    public SortedMap<FileVersion, IndexEntries> lookupAggregate(String aggregateId, long firstSequenceNumber,
                                                         long lastSequenceNumber, long maxResults, long minToken) {
        SortedMap<FileVersion, IndexEntries> results = new TreeMap<>();
        logger.debug("{}: lookupAggregate {} minSequenceNumber {}, lastSequenceNumber {}",
                     context,
                     aggregateId,
                     firstSequenceNumber,
                     lastSequenceNumber);

        long minTokenInPreviousSegment = Long.MAX_VALUE;
        for (Long segment : activeIndexes.descendingKeySet()) {
            if (minTokenInPreviousSegment < minToken) {
                return results;
            }
            IndexEntries entries = activeIndexes.getOrDefault(segment, Collections.emptyMap()).get(aggregateId);
            if (entries != null) {
                entries = addToResult(firstSequenceNumber, lastSequenceNumber, results, new FileVersion(segment, 0), entries);
                maxResults -= entries.size();
                if (allEntriesFound(firstSequenceNumber, maxResults, entries)) {
                    return results;
                }
            }
            minTokenInPreviousSegment = segment;
        }

        for (Map.Entry<Long, Integer> index : indexesDescending.entrySet()) {
            if (minTokenInPreviousSegment < minToken) {
                return results;
            }
            FileVersion fileVersion = new FileVersion(index.getKey(), index.getValue());
            IndexEntries entries = getPositions(fileVersion, aggregateId);
            logger.debug("{}: lookupAggregate {} in segment {} found {}", context, aggregateId, index, entries);
            if (entries != null) {
                entries = addToResult(firstSequenceNumber, lastSequenceNumber, results, fileVersion, entries);
                maxResults -= entries.size();
                if (allEntriesFound(firstSequenceNumber, maxResults, entries)) {
                    return results;
                }
            }
            minTokenInPreviousSegment = index.getKey();
        }

        return results;
    }

    private IndexEntries addToResult(long firstSequenceNumber, long lastSequenceNumber,
                                     SortedMap<FileVersion, IndexEntries> results, FileVersion segment, IndexEntries entries) {
        entries = entries.range(firstSequenceNumber, lastSequenceNumber, EventType.SNAPSHOT.equals(eventType));
        if (!entries.isEmpty()) {
            results.put(segment, entries);
        }
        return entries;
    }

    private boolean allEntriesFound(long firstSequenceNumber, long maxResults, IndexEntries entries) {
        return !entries.isEmpty() && firstSequenceNumber >= entries.firstSequenceNumber() || maxResults <= 0;
    }

    /**
     * Cleanup the index manager.
     *
     * @param delete flag to indicate that all indexes should be deleted
     */
    public void cleanup(boolean delete) {
        activeIndexes.clear();
        bloomFilterPerSegment.clear();
        indexMap.forEach((segment, index) -> index.close());
        indexMap.clear();
        indexesDescending.clear();
        if (cleanupTask != null && !cleanupTask.isDone()) {
            cleanupTask.cancel(true);
        }
    }

    @Override
    public Stream<String> getBackupFilenames(long lastSegmentBackedUp, int lastVersionBackedUp) {
        return indexesDescending.entrySet()
                                .stream()
                                .filter(s -> s.getKey() > lastSegmentBackedUp || s.getValue() > lastVersionBackedUp)
                                .flatMap(s -> Stream.of(
                                        storageProperties.index(context, s.getKey()).getAbsolutePath(),
                                        storageProperties.bloomFilter(context, s.getKey()).getAbsolutePath()
                                ));
    }

    private class Index implements Closeable {

        private final FileVersion segment;
        private final Object initLock = new Object();
        private volatile boolean initialized;
        private HTreeMap<String, IndexEntries> positions;
        private DB db;


        private Index(FileVersion fileVersion) {
            this.segment = fileVersion;
        }

        public IndexEntries getPositions(String aggregateId) {
            return positions.get(aggregateId);
        }

        @Override
        public void close() {
            logger.debug("{}: close {}", segment, storageProperties.index(context, segment));
            if (db != null && !db.isClosed()) {
                indexCloseMeter.mark();
                positions.close();
                db.close();
            }
        }

        public Index ensureReady() {
            if (initialized && !db.isClosed()) {
                return this;
            }

            synchronized (initLock) {
                if (initialized && !db.isClosed()) {
                    return this;
                }

                File index = storageProperties.index(context, segment);
                if (!index.exists()) {
                    throw new IndexNotFoundException("Index not found for segment: " + segment);
                }
                indexOpenMeter.mark();
                logger.debug("{}: open {}", segment, index);
                DBMaker.Maker maker = DBMaker.fileDB(index)
                                             .readOnly()
                                             .fileLockDisable();
                if (storageProperties.isUseMmapIndex() && segment.segment() > useMmapAfterIndex.get()) {
                    maker.fileMmapEnable();
                    if (storageProperties.isForceCleanMmapIndex()) {
                        maker.cleanerHackEnable();
                    }
                } else {
                    maker.fileChannelEnable();
                }
                this.db = maker.make();
                this.positions = db.hashMap(AGGREGATE_MAP, Serializer.STRING, StandardIndexEntriesSerializer.get())
                                   .createOrOpen();
                initialized = true;
            }
            return this;
        }
    }
}
