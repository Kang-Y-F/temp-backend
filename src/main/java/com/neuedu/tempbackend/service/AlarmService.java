// src/main/java/com/neuedu/tempbackend/service/AlarmService.java
package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.model.EdgeConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 报警服务，现在可以从云端动态获取阈值。
 * 优先级：传感器特定阈值（云端） > 全局阈值（云端） > application.yml默认值
 */
@Service
public class AlarmService {

    // application.yml中的默认阈值，作为云端配置未拉取到或未指定时的最终兜底
    @Value("${alarm.threshold.upper:30.0}")
    private Float appDefaultUpperThreshold;

    @Value("${alarm.threshold.lower:10.0}")
    private Float appDefaultLowerThreshold;

    @Value("${alarm.threshold.deviation:2.0}")
    private Float appDefaultDeviationThreshold;

    // 当前生效的全局报警阈值（由云端下发）
    private volatile Float currentGlobalUpperThreshold;
    private volatile Float currentGlobalLowerThreshold;
    private volatile Float currentGlobalDeviationThreshold;

    // 持有传感器特定报警阈值的映射，这个Map由ConfigSyncService通过Setter方法更新
    // 这样AlarmService就不需要直接依赖ConfigSyncService了
    private final ConcurrentMap<String, EdgeConfig.AlarmThresholdsConfig> sensorSpecificThresholds = new ConcurrentHashMap<>();

    // 构造函数，不再注入 ConfigSyncService
    public AlarmService() {
    }

    // Bean初始化后，设置初始的全局阈值
    @PostConstruct
    public void init() {
        this.currentGlobalUpperThreshold = appDefaultUpperThreshold;
        this.currentGlobalLowerThreshold = appDefaultLowerThreshold;
        this.currentGlobalDeviationThreshold = appDefaultDeviationThreshold;
    }

    /**
     * 由 ConfigSyncService 调用，更新全局报警阈值。
     * @param newThresholds 从云端获取的全局阈值配置
     */
    public void updateGlobalThresholds(EdgeConfig.AlarmThresholdsConfig newThresholds) {
        if (newThresholds.getUpper() != null) {
            this.currentGlobalUpperThreshold = newThresholds.getUpper();
        } else {
            this.currentGlobalUpperThreshold = appDefaultUpperThreshold; // 如果云端全局未指定，则回退到yml默认
        }
        if (newThresholds.getLower() != null) {
            this.currentGlobalLowerThreshold = newThresholds.getLower();
        } else {
            this.currentGlobalLowerThreshold = appDefaultLowerThreshold;
        }
        if (newThresholds.getDeviation() != null) {
            this.currentGlobalDeviationThreshold = newThresholds.getDeviation();
        } else {
            this.currentGlobalDeviationThreshold = appDefaultDeviationThreshold;
        }
        System.out.println("AlarmService: Updated global alarm thresholds. Upper: " + currentGlobalUpperThreshold + ", Lower: " + currentGlobalLowerThreshold + ", Deviation: " + currentGlobalDeviationThreshold);
    }

    /**
     * 由 ConfigSyncService 调用，更新传感器特定的报警阈值。
     * @param sensorId 传感器ID
     * @param thresholds 该传感器的特定阈值
     */
    public void updateSensorSpecificThresholds(String sensorId, EdgeConfig.AlarmThresholdsConfig thresholds) {
        if (sensorId != null && thresholds != null) {
            sensorSpecificThresholds.put(sensorId, thresholds);
        } else if (sensorId != null) {
            // 如果云端下发配置明确移除某个传感器的特定阈值，则从map中移除
            sensorSpecificThresholds.remove(sensorId);
        }
    }

    /**
     * 获取给定传感器的实际生效阈值。
     * 优先级：传感器特定阈值（云端） > 全局阈值（云端） > application.yml默认值
     * @param sensorId 传感器ID
     * @return 实际生效的阈值配置
     */
    private EdgeConfig.AlarmThresholdsConfig getEffectiveThresholds(String sensorId) {
        // 尝试获取传感器特定的阈值
        EdgeConfig.AlarmThresholdsConfig specificThresholds = sensorSpecificThresholds.get(sensorId); // <-- 直接从自身map获取

        // 创建一个用于返回的有效阈值对象
        EdgeConfig.AlarmThresholdsConfig effective = new EdgeConfig.AlarmThresholdsConfig();

        // 为 upper 阈值确定最终值
        effective.setUpper(
                specificThresholds != null && specificThresholds.getUpper() != null ? specificThresholds.getUpper() :
                        (currentGlobalUpperThreshold != null ? currentGlobalUpperThreshold : appDefaultUpperThreshold)
        );
        // 为 lower 阈值确定最终值
        effective.setLower(
                specificThresholds != null && specificThresholds.getLower() != null ? specificThresholds.getLower() :
                        (currentGlobalLowerThreshold != null ? currentGlobalLowerThreshold : appDefaultLowerThreshold)
        );
        // 为 deviation 阈值确定最终值
        effective.setDeviation(
                specificThresholds != null && specificThresholds.getDeviation() != null ? specificThresholds.getDeviation() :
                        (currentGlobalDeviationThreshold != null ? currentGlobalDeviationThreshold : appDefaultDeviationThreshold)
        );
        return effective;
    }

    /**
     * 检查是否触发报警
     * @param sensorId 传感器ID
     * @param actualTemperature 实际温度
     * @param predictedTemperature 预测温度
     * @return 如果触发报警则返回true
     */
    public boolean checkAlarm(String sensorId, Float actualTemperature, Float predictedTemperature) {
        if (actualTemperature == null) {
            return false;
        }

        EdgeConfig.AlarmThresholdsConfig thresholds = getEffectiveThresholds(sensorId);
        Float upper = thresholds.getUpper();
        Float lower = thresholds.getLower();
        Float deviation = thresholds.getDeviation();

        // 1. 绝对值阈值报警
        if (actualTemperature > upper || actualTemperature < lower) {
            return true;
        }

        // 2. 预测偏差报警 (如果有预测值)
        if (predictedTemperature != null) {
            float actualDeviation = Math.abs(actualTemperature - predictedTemperature);
            if (actualDeviation > deviation) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取报警信息，包含传感器ID和名称
     * @param sensorId 传感器ID
     * @param sensorName 传感器名称
     * @param actualTemperature 实际温度
     * @param predictedTemperature 预测温度
     * @return 报警信息字符串，如果没有报警则返回"No Alarm"
     */
    public String getAlarmMessage(String sensorId, String sensorName, Float actualTemperature, Float predictedTemperature) {
        String prefix = "传感器 [" + sensorName + " (" + sensorId + ")] ";

        if (actualTemperature == null) return prefix + "未知温度";

        EdgeConfig.AlarmThresholdsConfig thresholds = getEffectiveThresholds(sensorId);
        Float upper = thresholds.getUpper();
        Float lower = thresholds.getLower();
        Float deviation = thresholds.getDeviation();

        if (actualTemperature > upper) return prefix + "温度过高: " + String.format("%.2f", actualTemperature) + "°C (阈值: " + String.format("%.2f", upper) + "°C)";
        if (actualTemperature < lower) return prefix + "温度过低: " + String.format("%.2f", actualTemperature) + "°C (阈值: " + String.format("%.2f", lower) + "°C)";

        if (predictedTemperature != null) {
            float actualDeviation = Math.abs(actualTemperature - predictedTemperature);
            if (actualDeviation > deviation) {
                return prefix + "温度异常波动: 实际 " + String.format("%.2f", actualTemperature) + "°C, 预测 " + String.format("%.2f", predictedTemperature) + "°C (偏差: " + String.format("%.2f", actualDeviation) + "°C, 阈值: " + String.format("%.2f", deviation) + "°C)";
            }
        }
        return "No Alarm";
    }


}
