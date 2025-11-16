package com.neuedu.tempbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class PredictionService {

    private final RestTemplate restTemplate;

    // Python预测服务的URL，默认为本地端口5000，路径/predict
    @Value("${prediction.service.url:http://localhost:5000/predict}")
    private String predictionServiceUrl;

    public PredictionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 调用外部（Python）预测服务，预测温度
     * @param temperature 当前温度
     * @param humidity 当前湿度 (可能为null)
     * @param pressure 当前压力 (可能为null)
     * @return 预测温度值，如果调用失败则返回null
     */
    public Float predict(Float temperature, Float humidity, Float pressure) {
        if (temperature == null) {
            return null; // 没有当前温度无法预测
        }
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("temperature", temperature);
            requestBody.put("humidity", humidity); // 即使为null也发送
            requestBody.put("pressure", pressure); // 即使为null也发送

            // 调用Python预测服务
            // 假设Python服务返回 {"predicted_temperature": 25.5}
            Map<String, Object> response = restTemplate.postForObject(predictionServiceUrl, requestBody, Map.class);

            if (response != null && response.containsKey("predicted_temperature")) {
                // 将Number类型转换为Float
                return ((Number) response.get("predicted_temperature")).floatValue();
            }
        } catch (Exception e) {
            System.err.println("Error calling prediction service at " + predictionServiceUrl + ": " + e.getMessage());
            // 实际项目中可以记录更详细日志或使用断路器模式
        }
        return null;
    }
}
