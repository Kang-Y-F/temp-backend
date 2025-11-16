package com.neuedu.tempbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AlarmService {

    @Value("${alarm.threshold.upper:30.0}") // 温度上限报警阈值
    private Float upperThreshold;

    @Value("${alarm.threshold.lower:10.0}") // 温度下限报警阈值
    private Float lowerThreshold;

    @Value("${alarm.threshold.deviation:2.0}") // 实际值与预测值偏差报警阈值
    private Float deviationThreshold;

    /**
     * 检查是否触发报警
     * @param actualTemperature 实际温度
     * @param predictedTemperature 预测温度
     * @return 如果触发报警则返回true
     */
    public boolean checkAlarm(Float actualTemperature, Float predictedTemperature) {
        if (actualTemperature == null) {
            return false; // 没有实际温度无法判断
        }

        // 1. 绝对值阈值报警
        if (actualTemperature > upperThreshold || actualTemperature < lowerThreshold) {
            return true;
        }

        // 2. 预测偏差报警 (如果有预测值)
        if (predictedTemperature != null) {
            float deviation = Math.abs(actualTemperature - predictedTemperature);
            if (deviation > deviationThreshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取报警信息
     * @param actualTemperature 实际温度
     * @param predictedTemperature 预测温度
     * @return 报警信息字符串，如果没有报警则返回"No Alarm"
     */
    public String getAlarmMessage(Float actualTemperature, Float predictedTemperature) {
        if (actualTemperature == null) return "Unknown Temperature";

        if (actualTemperature > upperThreshold) return "温度过高: " + String.format("%.2f", actualTemperature) + "°C (阈值: " + upperThreshold + "°C)";
        if (actualTemperature < lowerThreshold) return "温度过低: " + String.format("%.2f", actualTemperature) + "°C (阈值: " + lowerThreshold + "°C)";

        if (predictedTemperature != null) {
            float deviation = Math.abs(actualTemperature - predictedTemperature);
            if (deviation > deviationThreshold) {
                return "温度异常波动: 实际 " + String.format("%.2f", actualTemperature) + "°C, 预测 " + String.format("%.2f", predictedTemperature) + "°C (偏差: " + String.format("%.2f", deviation) + "°C)";
            }
        }
        return "No Alarm";
    }
}
