package com.neuedu.tempbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 启用定时任务
@EnableCaching    // 启用缓存
public class TempBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TempBackendApplication.class, args);
    }

}
