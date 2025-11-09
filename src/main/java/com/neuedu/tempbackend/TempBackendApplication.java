package com.neuedu.tempbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling     // ✅ 这行非常关键
public class TempBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TempBackendApplication.class, args);
    }
}
