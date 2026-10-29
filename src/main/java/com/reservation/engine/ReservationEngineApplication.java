package com.reservation.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReservationEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationEngineApplication.class, args);
    }
}
