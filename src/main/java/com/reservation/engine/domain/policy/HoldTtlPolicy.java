package com.reservation.engine.domain.policy;

import com.reservation.engine.resourcetype.ResourceTypeDefinition;

import java.time.Duration;

/** Decides how long an unconfirmed hold keeps its inventory. */
public interface HoldTtlPolicy {
    Duration holdTtl(ResourceTypeDefinition type);
}
