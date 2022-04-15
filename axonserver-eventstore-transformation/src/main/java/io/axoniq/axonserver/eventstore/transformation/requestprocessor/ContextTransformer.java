package io.axoniq.axonserver.eventstore.transformation.requestprocessor;

import io.axoniq.axonserver.grpc.event.Event;
import reactor.core.publisher.Mono;

public interface ContextTransformer {

    Mono<String> start(String description);

    Mono<Void> deleteEvent(String transformationId, long token, long sequence);

    Mono<Void> replaceEvent(String transformationId, long token, Event event, long sequence);

    Mono<Void> cancel(String transformationId);

    Mono<Void> startApplying(String transformationId, long sequence, boolean keepOldVersions);

    Mono<Void> markApplied(String transformationId);

    Mono<Void> startRollingBack(String transformationId);

    Mono<Void> markRolledBack(String transformationId);

    Mono<Void> deleteOldVersions();

    Mono<Void> restart();
}
