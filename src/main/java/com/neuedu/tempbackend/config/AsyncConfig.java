// 新增一个配置类，例如 AsyncConfig.java
package com.neuedu.tempbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync // 再次启用异步，确保在自定义Executor之后
public class AsyncConfig {

    @Bean(name = "cloudUploadExecutor")
    public Executor cloudUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // 核心线程数
        executor.setMaxPoolSize(10); // 最大线程数
        executor.setQueueCapacity(25); // 队列容量
        executor.setThreadNamePrefix("CloudUpload-");
        executor.initialize();
        return executor;
    }
    @Bean(name = "cloudConfigSyncExecutor")
    public Executor cloudConfigSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); // 配置同步不需要太多线程，一个就够
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("ConfigSync-");
        executor.initialize();
        return executor;
    }
    // ✅ 轮询用的线程池（关键）
//    @Bean(name = "taskScheduler")
//    public ThreadPoolTaskScheduler taskScheduler() {
//        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
//        // 至少 >= 串口数量，给多一点也没问题
//        scheduler.setPoolSize(8);
//        scheduler.setThreadNamePrefix("Polling-");
//        scheduler.setRemoveOnCancelPolicy(true);
//        scheduler.initialize();
//        return scheduler;
//    }
}
