package com.neuedu.tempbackend.config; // 放在 config 包下，或者任何你觉得合适的地方

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration // 标记这是一个配置类
public class RestTemplateConfig {

    @Bean // 标记这个方法会生成一个 Spring Bean，并将其注册到 Spring 容器中
    public RestTemplate restTemplate() {
        // 可以根据需要配置 RestTemplate，例如设置超时、拦截器等
        return new RestTemplate();
    }
}
