package com.neuedu.tempbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler; // 导入线程池调度器
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableScheduling // 启用Spring的定时任务调度
public class TempBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TempBackendApplication.class, args);
    }

    @Bean // 将RestTemplate注册为Spring Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean // 注册 ThreadPoolTaskScheduler Bean，用于动态调度传感器轮询任务
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10); // 根据你可能同时运行的传感器数量，适当调整线程池大小
        scheduler.setThreadNamePrefix("sensor-polling-");
        scheduler.initialize();
        return scheduler;
    }
}
