package com.neuedu.tempbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate; // 导入RestTemplate

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
}
