package com.campusbuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CampusBuddyApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusBuddyApplication.class, args);
    }
}
