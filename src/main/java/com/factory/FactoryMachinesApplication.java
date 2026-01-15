package com.factory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the Factory Machines Backend System.
 * This system processes machine events, stores them, and provides statistics.
 */
@SpringBootApplication
public class FactoryMachinesApplication {

    public static void main(String[] args) {
        SpringApplication.run(FactoryMachinesApplication.class, args);
    }
}

