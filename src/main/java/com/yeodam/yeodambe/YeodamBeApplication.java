package com.yeodam.yeodambe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class YeodamBeApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(YeodamBeApplication.class, args);
        if (context.getEnvironment().getProperty("app.attachment-conversion.operations.enabled", Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }

}
