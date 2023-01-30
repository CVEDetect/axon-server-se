package io.axoniq.axonserver.eventstore.transformation.requestprocessor;

import io.axoniq.axonserver.api.Authentication;
import io.axoniq.axonserver.eventstore.transformation.api.EventStoreTransformationService;
import io.axoniq.axonserver.grpc.event.Event;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


import static java.lang.String.format;

public class FastValidationEventStoreTransformationService implements EventStoreTransformationService {

    private static final Logger logger = LoggerFactory.getLogger(FastValidationEventStoreTransformationService.class);

    private final EventStoreTransformationService delegate;
    private final ContextEventProviderSupplier contextEventProviderSupplier;

    public FastValidationEventStoreTransformationService(EventStoreTransformationService delegate,
                                                         ContextEventProviderSupplier contextEventProviderSupplier) {
        this.delegate = delegate;
        this.contextEventProviderSupplier = contextEventProviderSupplier;
    }

    public void destroy() {
        // TODO: 1/19/23 close opened event providers
    }

    private Mono<Void> validateEventToDelete(String context, long token) {
        return contextEventProviderSupplier.eventProviderFor(context)
                                           .event(token)
                                           .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                                   "Trying to delete non existing event " + token)))
                                           .doOnError(t -> t instanceof FastValidationException,
                                                      t -> logger.warn("Invalid token to delete.", t))
                                           .doOnError(t -> !(t instanceof FastValidationException),
                                                      t -> logger.warn("Unable to validate deletion.", t))
                                           .then();
    }

    private Mono<Event> validateEventToReplace(String context, long token, Event replacement) {
        return contextEventProviderSupplier.eventProviderFor(context)
                                           .event(token)
                                           .flatMap(original -> validateAggregateSequenceNumber(original, replacement))
                                           .flatMap(original -> validateAggregateIdentifier(original, replacement))
                                           .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                                   "Event not found: " + token)))
                                           .doOnError(t -> t instanceof FastValidationException,
                                                      t -> logger.warn("Invalid event to replace.", t))
                                           .doOnError(t -> !(t instanceof FastValidationException),
                                                      t -> logger.warn("Unable to validate replacement.", t));
    }

    private Mono<Event> validateAggregateSequenceNumber(Event event, Event replacement) {
        if (event.getAggregateSequenceNumber() == replacement.getAggregateSequenceNumber()) {
            return Mono.just(event);
        }
        return Mono.error(new FastValidationException(format("Invalid aggregate sequence number: %d, expecting %d",
                                                             replacement.getAggregateSequenceNumber(),
                                                             event.getAggregateSequenceNumber())));
    }

    private Mono<Event> validateAggregateIdentifier(Event event, Event replacement) {
        if (event.getAggregateIdentifier().equals(replacement.getAggregateIdentifier())) {
            return Mono.just(event);
        }
        return Mono.error(new FastValidationException(format("Invalid aggregate identifier: %s, expecting %s",
                                                             replacement.getAggregateIdentifier(),
                                                             event.getAggregateIdentifier())));
    }


    @Override
    public Flux<Transformation> transformations(String context, @NotNull Authentication authentication) {
        return delegate.transformations(context, authentication);
    }

    @Override
    public Mono<Void> start(String id, String context, String description, @NotNull Authentication authentication) {
        return delegate.start(id, context, description, authentication);
    }

    @Override
    public Mono<Void> deleteEvent(String context, String transformationId, long token, long sequence,
                                  @NotNull Authentication authentication) {
        return validateEventToDelete(context, token)
                .then(delegate.deleteEvent(context, transformationId, token, sequence, authentication));
    }

    @Override
    public Mono<Void> replaceEvent(String context, String transformationId, long token, Event event, long sequence,
                                   @NotNull Authentication authentication) {
        return validateEventToReplace(context, token, event)
                .then(delegate.replaceEvent(context, transformationId, token, event, sequence, authentication));
    }

    @Override
    public Mono<Void> cancel(String context, String transformationId, @NotNull Authentication authentication) {
        return delegate.cancel(context, transformationId, authentication);
    }

    @Override
    public Mono<Void> startApplying(String context, String transformationId, long sequence,
                                    @NotNull Authentication authentication) {
        return delegate.startApplying(context,
                                      transformationId,
                                      sequence,
                                      authentication);
    }

    @Override
    public Mono<Void> compact(String compactionId, String context, @NotNull Authentication authentication) {
        return delegate.compact(compactionId, context, authentication);
    }
}
