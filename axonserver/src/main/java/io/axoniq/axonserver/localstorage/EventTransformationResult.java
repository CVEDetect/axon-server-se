/*
 *  Copyright (c) 2017-2021 AxonIQ B.V. and/or licensed to AxonIQ B.V.
 *  under one or more contributor license agreements.
 *
 *  Licensed under the AxonIQ Open Source License Agreement v1.0;
 *  you may not use this file except in compliance with the license.
 *
 */

package io.axoniq.axonserver.localstorage;

import io.axoniq.axonserver.grpc.event.Event;

import javax.annotation.Nonnull;

/**
 * Result of a single event transformation.
 *
 * @author Marc Gathier
 * @since 4.6.0
 */
public interface EventTransformationResult {

    /**
     * Returns the transformed event.
     *
     * @return the transformed event
     */
    @Nonnull
    Event event();

    /**
     * Returns the token of the next event to transform. If the transformation function does not know the next token, it
     * must return the token from the event transformation request plus one.
     *
     * @return the token of the next event to transform
     */
    long nextToken();
}
