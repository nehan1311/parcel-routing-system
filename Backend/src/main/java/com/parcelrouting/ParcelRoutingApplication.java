package com.parcelrouting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.parcelrouting.routing.RoutingEngine;

@SpringBootApplication
public class ParcelRoutingApplication {

    @Bean
    RoutingEngine routingEngine() {
        return new RoutingEngine();
    }

    public static void main(String[] args) {
        SpringApplication.run(ParcelRoutingApplication.class, args);
    }
}
