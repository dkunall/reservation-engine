package com.reservation.engine.application;

import com.reservation.engine.domain.policy.CancellationPolicy;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import org.springframework.stereotype.Component;

/** Picks a type-specific policy if the resource type supplies one, otherwise the engine default. */
@Component
public class PolicyResolver {
    private final ResourceTypeRegistry types;
    private final CancellationPolicy defaultCancellation;

    public PolicyResolver(ResourceTypeRegistry types, CancellationPolicy defaultCancellation) {
        this.types = types;
        this.defaultCancellation = defaultCancellation;
    }

    public CancellationPolicy cancellationPolicy(String typeCode) {
        return types.find(typeCode).flatMap(t -> t.cancellationPolicy()).orElse(defaultCancellation);
    }
}
