package com.yeodam.yeodambe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class YeodamBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(YeodamBeApplication.class, args);
    }

}
