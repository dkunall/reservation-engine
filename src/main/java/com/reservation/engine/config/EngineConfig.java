package com.reservation.engine.config;

import com.reservation.engine.domain.policy.CancellationPolicy;
import com.reservation.engine.domain.policy.DefaultCancellationPolicy;
import com.reservation.engine.domain.policy.DefaultHoldTtlPolicy;
import com.reservation.engine.domain.policy.HoldTtlPolicy;
import com.reservation.engine.inventory.spi.InventoryStrategy;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import com.reservation.engine.outbox.EventPublisher;
import com.reservation.engine.outbox.LoggingEventPublisher;
import com.reservation.engine.payment.FakePaymentAdapter;
import com.reservation.engine.payment.PaymentPort;
import com.reservation.engine.persistence.SavepointTemplate;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import com.reservation.engine.resourcetype.SimpleResourceTypeDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Wiring of registries, default policies and default adapters. Every default is replaceable by a bean. */
@Configuration
public class EngineConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public InventoryStrategyRegistry inventoryStrategyRegistry(List<InventoryStrategy> strategies) {
        return new InventoryStrategyRegistry(strategies);
    }

    /**
     * Resource types = code-defined beans (built-ins below, extensions like VEHICLE) + config-declared ones.
     */
    @Bean
    public ResourceTypeRegistry resourceTypeRegistry(List<ResourceTypeDefinition> beans,
                                                     InventoryStrategyRegistry strategies,
                                                     ReservationProperties props) {
        List<ResourceTypeDefinition> all = new ArrayList<>(beans);
        props.customResourceTypes().forEach(c -> all.add(new SimpleResourceTypeDefinition(
                c.code(), c.inventoryModel(), c.holdTtl(), c.billingPeriod())));
        return new ResourceTypeRegistry(all, strategies);
    }

    @Bean
    @ConditionalOnMissingBean
    public HoldTtlPolicy holdTtlPolicy(ReservationProperties props) {
        return new DefaultHoldTtlPolicy(props.hold().defaultTtl(), props.hold().maxTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public CancellationPolicy defaultCancellationPolicy(ReservationProperties props) {
        return new DefaultCancellationPolicy(props.cancellation().cutoffBeforeStart());
    }

    @Bean
    @ConditionalOnMissingBean(PaymentPort.class)
    public FakePaymentAdapter fakePaymentAdapter(ReservationProperties props) {
        return new FakePaymentAdapter(props.payment().fakeMode(), props.payment().fakeLatency());
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public SavepointTemplate savepointTemplate(DataSource dataSource) {
        return new SavepointTemplate(dataSource);
    }
}
