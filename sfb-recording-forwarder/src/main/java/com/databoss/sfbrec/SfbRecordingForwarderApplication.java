package com.databoss.sfbrec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SfbRecordingForwarderApplication {

    public static void main(String[] args) {
        SpringApplication.run(SfbRecordingForwarderApplication.class, args);
    }
}
