package com.product.activation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ActivationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ActivationApplication.class, args);
    }
}
