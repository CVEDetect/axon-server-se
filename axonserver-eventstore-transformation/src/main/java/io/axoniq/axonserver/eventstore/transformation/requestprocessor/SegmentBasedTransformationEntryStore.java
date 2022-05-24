package io.axoniq.axonserver.eventstore.transformation.requestprocessor;

import io.axoniq.axonserver.filestorage.AppendOnlyFileStore;
import io.axoniq.axonserver.filestorage.FileStoreEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

public class SegmentBasedTransformationEntryStore implements TransformationEntryStore {

    private final AppendOnlyFileStore appendOnlyFileStore;

    public SegmentBasedTransformationEntryStore(AppendOnlyFileStore appendOnlyFileStore) {
        this.appendOnlyFileStore = appendOnlyFileStore;
    }

    @Override
    public Mono<Long> store(TransformationEntry entry) {
        long sequence = entry.sequence();
        return resetIfNeeded(sequence).then(appendOnlyFileStore.append(toFileStoreEntry(entry)))
                                      .thenReturn(sequence);
    }

    @Override
    public Flux<TransformationEntry> readFrom(long sequence) {
        return Flux.deferContextual(c -> appendOnlyFileStore.stream(sequence)
                                                            .doOnNext(System.out::println)
                                                            .map(e -> {
                                                                AtomicLong index = c.get("index");
                                                                return map(e, index.getAndIncrement());
                                                            }))
                   .contextWrite(c -> c.put("index", new AtomicLong(sequence)));
    }

    private TransformationEntry map(FileStoreEntry entry, long sequence) {
        return new TransformationEntry() {
            @Override
            public long sequence() {
                return sequence;
            }

            @Override
            public byte[] payload() {
                return entry.bytes();
            }

            @Override
            public byte version() {
                return entry.version();
            }
        };
    }

    @Override
    public Flux<TransformationEntry> read() {
        return readFrom(0);
    }

    @Override
    public Mono<Void> delete() {
        return appendOnlyFileStore.delete();
    }

    private FileStoreEntry toFileStoreEntry(TransformationEntry entry) {
        return new FileStoreEntry() {
            @Override
            public byte[] bytes() {
                return entry.payload();
            }

            @Override
            public byte version() {
                return entry.version();
            }
        };
    }

    private Mono<Void> resetIfNeeded(long sequence) {
        return neededToResetTo(sequence).flatMap(this::reset);
    }

    /**
     * //TODO Returns the sequence to reset to. Returns an Empty mono if reset is not needed.
     *
     * @param sequence
     * @return
     */
    private Mono<Long> neededToResetTo(long sequence) {
        return appendOnlyFileStore.lastSequence()
                                  .filter(lastSequence -> lastSequence + 1 != sequence)
                                  .map(lastSequence -> sequence - 1);
    }

    private Mono<Void> reset(long sequence) {
        return appendOnlyFileStore.reset(sequence);
    }
}




