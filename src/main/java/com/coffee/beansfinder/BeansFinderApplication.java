package com.coffee.beansfinder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BeansFinderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeansFinderApplication.class, args);
    }
}
