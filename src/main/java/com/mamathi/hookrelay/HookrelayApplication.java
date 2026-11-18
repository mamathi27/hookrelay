package com.mamathi.hookrelay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HookrelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(HookrelayApplication.class, args);
    }
}