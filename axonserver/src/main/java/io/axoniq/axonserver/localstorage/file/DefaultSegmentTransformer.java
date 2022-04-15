package io.axoniq.axonserver.localstorage.file;

import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.EventWithToken;
import io.axoniq.axonserver.localstorage.SerializedTransactionWithToken;
import reactor.core.publisher.Mono;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class DefaultSegmentTransformer implements SegmentTransformer {

    private final StorageProperties storageProperties;
    private final String context;
    private final long segment;
    private final int newVersion;
    private final AtomicReference<TransactionIterator> transactionIteratorRef = new AtomicReference<>();
    private final AtomicReference<SegmentWriter> segmentWriterRef = new AtomicReference<>();
    private final AtomicReference<File> tempFileRef = new AtomicReference<>();
    private final AtomicReference<File> dataFileRef = new AtomicReference<>();
    private final AtomicReference<SerializedTransactionWithToken> originalTransactionRef = new AtomicReference<>();
    private final IndexManager indexManager;
    private final List<Event> transformedTransaction = new CopyOnWriteArrayList<>();



    DefaultSegmentTransformer(StorageProperties storageProperties,
                              String context,
                              long segment,
                              int newVersion, IndexManager indexManager) {
        this.storageProperties = storageProperties;
        this.context = context;
        this.segment = segment;
        this.newVersion = newVersion;
        this.indexManager = indexManager;
    }

    @Override
    public Mono<Void> initialize() {
        return Mono.create(sink -> {
            try {
                File tempFile = storageProperties.transformedDataFile(context, new FileVersion(segment, newVersion));
                FileUtils.delete(tempFile);
                tempFileRef.set(tempFile);
                SegmentWriter segmentWriter = new StreamSegmentWriter(tempFile,
                                                                      segment,
                                                                      storageProperties.getFlags());
                segmentWriterRef.set(segmentWriter);
                sink.success();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    @Override
    public Mono<TransformationProgress> transformEvent(EventWithToken transformedEvent) {
        return process(() -> Optional.of(transformedEvent))
                .thenReturn(new TransformationProgressUpdate(transformedEvent.getToken()));
    }

    @Override
    public Mono<Void> completeSegment() {
        return process(Optional::empty);
    }

    @Override
    public Mono<Void> rollback(Throwable e) {
        return Mono.fromRunnable(() -> {
            File tempFile = tempFileRef.get();
            if (tempFile != null) {
                FileUtils.delete(tempFile);
            }
        });
    }

    @Override
    public Mono<Void> cancel() {
        return null;
    }

    private Mono<Void> process(Supplier<Optional<EventWithToken>> replacement) {
        return Mono.create(sink -> {
            try {
                boolean done = false;
                do {
                    if (originalTransactionRef.get() == null) {
                        originalTransactionRef.set(transactionIteratorRef.get().next());
                    }

                    SerializedTransactionWithToken originalTX = originalTransactionRef.get();
                    Event event = originalTX.getEvents(transformedTransaction.size());


                    Optional<EventWithToken> eventWithToken = replacement.get();
                    if (eventWithToken.isPresent()) {
                        long currentToken = originalTX.getToken() + transformedTransaction.size();
                        EventWithToken transformedEvent = eventWithToken.get();
                        if (transformedEvent.getToken() == currentToken) {
                            event = transformedEvent.getEvent();
                            done = true;
                        }
                    }
                    transformedTransaction.add(event);

                    if (originalTX.getEvents().size() == transformedTransaction.size()) {
                        SegmentWriter segmentWriter = segmentWriterRef.get();
                        segmentWriter.write(transformedTransaction);
                        originalTransactionRef.set(null);
                        transformedTransaction.clear();

                        if (!transactionIteratorRef.get().hasNext()) {
                            segmentWriter.writeEndOfFile();
                            indexManager.createNewVersion(segment, newVersion, segmentWriter.indexEntries());
                            done = true;
                        }
                    }
                } while (!done);
                sink.success();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
}
