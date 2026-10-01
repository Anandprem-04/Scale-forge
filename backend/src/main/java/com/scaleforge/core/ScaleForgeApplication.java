package com.scaleforge.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScaleForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScaleForgeApplication.class, args);
    }
}
