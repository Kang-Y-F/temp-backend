package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.model.SensorData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PredictionService {

    private final RestTemplate restTemplate;

    // Python预测服务的URL，默认为本地端口5000，路径/predict (单点预测)
    @Value("${prediction.service.url:http://localhost:5000/predict}")
    private String predictionServiceUrl;

    // Python预测趋势服务的URL，默认为本地端口5000，路径/predict_trend
    @Value("${prediction.service.trendUrl:http://localhost:5000/predict_trend}")
    private String predictionServiceTrendUrl;

    public PredictionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 调用外部（Python）预测服务，预测单个数据点的温度
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
            System.err.println("Error calling single point prediction service at " + predictionServiceUrl + ": " + e.getMessage());
            // 实际项目中可以记录更详细日志或使用断路器模式
        }
        return null;
    }

    /**
     * 调用外部（Python）预测服务，进行时间序列的趋势预测。
     * 假设Python服务需要一个包含历史数据点列表的JSON，并返回一个预测值列表。
     * @param sensorId 传感器ID
     * @param historyData 历史SensorData列表 (已降采样到模型所需频率，如 5 秒一个点)
     * @param predictHorizonSeconds 预测未来多少秒
     * @return 预测的温度序列 (List<Float>)，如果调用失败则返回null
     */
    public List<Float> predictTrend(String sensorId, List<SensorData> historyData, int predictHorizonSeconds) {
        if (historyData == null || historyData.isEmpty()) {
            return null;
        }

        try {
            // 构造请求体，将历史数据转换为Python服务期望的格式
            // 例如： [{"timestamp": "...", "temperature": "...", "humidity": "..."}, ...]
            List<Map<String, Object>> historicalPoints = historyData.stream().map(data -> {
                Map<String, Object> item = new HashMap<>();
                item.put("timestamp", data.getTimestamp().toString()); // 假设Python服务能解析ISO格式时间字符串
                item.put("temperature", data.getTemperature());
                if (data.getHumidity() != null) item.put("humidity", data.getHumidity());
                if (data.getPressure() != null) item.put("pressure", data.getPressure());
                return item;
            }).collect(Collectors.toList());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("sensorId", sensorId);
            requestBody.put("historyData", historicalPoints);
            requestBody.put("predictHorizonSeconds", predictHorizonSeconds);

            // 假设Python服务返回 {"forecast_temperatures": [t1, t2, t3, ...]}
            Map<String, Object> response = restTemplate.postForObject(predictionServiceTrendUrl, requestBody, Map.class);

            if (response != null && response.containsKey("forecast_temperatures")) {
                // 将Number类型的List转换为Float的List
                List<?> rawForecasts = (List<?>) response.get("forecast_temperatures");
                return rawForecasts.stream()
                        .map(o -> ((Number) o).floatValue())
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("Error calling trend prediction service at " + predictionServiceTrendUrl + " for sensor " + sensorId + ": " + e.getMessage());
        }
        return null;
    }
}
