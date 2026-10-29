package com.reservation.engine.config;

import com.reservation.engine.inventory.spi.InventoryModels;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import com.reservation.engine.resourcetype.SimpleResourceTypeDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Built-in resource types: pure data mapping type -> inventory model (+ optional TTL / billing period). */
@Configuration
public class BuiltInResourceTypes {

    @Bean
    ResourceTypeDefinition flightSeatType() {
        return new SimpleResourceTypeDefinition("FLIGHT", InventoryModels.EXCLUSIVE_UNIT, Duration.ofMinutes(15), null);
    }

    @Bean
    ResourceTypeDefinition trainSeatType() {
        return new SimpleResourceTypeDefinition("TRAIN", InventoryModels.EXCLUSIVE_UNIT);
    }

    @Bean
    ResourceTypeDefinition busType() {
        return new SimpleResourceTypeDefinition("BUS", InventoryModels.CAPACITY);
    }

    @Bean
    ResourceTypeDefinition eventGeneralAdmissionType() {
        return new SimpleResourceTypeDefinition("EVENT_GA", InventoryModels.CAPACITY, Duration.ofMinutes(8), null);
    }

    @Bean
    ResourceTypeDefinition eventSeatType() {
        return new SimpleResourceTypeDefinition("EVENT_SEAT", InventoryModels.EXCLUSIVE_UNIT, Duration.ofMinutes(8), null);
    }

    @Bean
    ResourceTypeDefinition hotelRoomType() {
        return new SimpleResourceTypeDefinition("HOTEL_ROOM", InventoryModels.TIME_RANGE, null, Duration.ofDays(1));
    }

    @Bean
    ResourceTypeDefinition appointmentType() {
        return new SimpleResourceTypeDefinition("APPOINTMENT", InventoryModels.TIME_SLOT);
    }
}
