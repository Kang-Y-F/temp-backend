package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.model.EdgeConfig;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 负责执行未来趋势预测和预警。
 */
@Service
public class TrendPredictionService {

    @Autowired
    private TemperaturePollingService temperaturePollingService; // 用于获取历史数据
    @Autowired
    private PredictionService predictionService; // 调用 Python 预测模型
    @Autowired
    private AlarmService alarmService; // 判断预警阈值
    @Autowired
    private SensorDataRepository sensorDataRepository; // 保存预测性报警
    @Autowired
    private CloudUploadService cloudUploadService; // 上传预测性报警
    @Autowired
    private ModbusProperties modbusProperties; // 获取所有传感器配置

    @Value("${edge.deviceId:jetson-001}")
    private String deviceId;

    @Value("${prediction.trend.historyMinutes:10}") // 预测模型需要过去多长时间的数据 (分钟)
    private int trendPredictionHistoryMinutes;

    @Value("${prediction.trend.horizonSeconds:60}") // 预测未来多长时间 (秒)
    private int trendPredictionHorizonSeconds;


    /**
     * 定时任务：检查未来温度预警。
     * 例如每 30 秒运行一次。
     */
    @Transactional
    @Scheduled(fixedRateString = "${prediction.trend.checkIntervalMs:30000}")
    public void checkFutureTemperatureAlerts() {
        System.out.println("开始执行未来温度趋势预测和预警检查...");

        for (ModbusProperties.SensorProperties sensorProp : modbusProperties.getSerial().getSensors()) {
            String sensorId = sensorProp.getSensorId();
            String sensorName = sensorProp.getSensorName();

            // 1. 获取用于预测的历史数据 (5秒降采样)
            List<SensorData> history = temperaturePollingService.get5SecondAggregatedHistoryForPrediction(
                    sensorId, trendPredictionHistoryMinutes);

            // 确保历史数据量足够
            if (history == null || history.size() < (trendPredictionHistoryMinutes * 60 / 5) * 0.8) { // 例如，至少有 80% 的数据点
                System.out.println("传感器 " + sensorId + " 历史数据不足 (" + (history != null ? history.size() : 0) + "点)，无法进行趋势预测。");
                continue;
            }

            // 2. 调用 Python 预测服务，获取未来趋势预测
            List<Float> futureTemperatures = predictionService.predictTrend(
                    sensorId, history, trendPredictionHorizonSeconds);

            if (futureTemperatures != null && !futureTemperatures.isEmpty()) {
                EdgeConfig.AlarmThresholdsConfig thresholds = alarmService.getEffectiveThresholds(sensorId);
                Float upper = thresholds.getUpper();
                Float lower = thresholds.getLower();

                // 3. 遍历预测结果，检查是否超阈值
                for (int i = 0; i < futureTemperatures.size(); i++) {
                    Float predictedTemp = futureTemperatures.get(i);
                    // 假设 Python 返回的是 5 秒间隔的数据点
                    LocalDateTime predictedOccurrenceTime = LocalDateTime.now().plusSeconds((i + 1) * 5); // 预测的是未来 (i+1)*5 秒后的温度

                    if (predictedTemp != null && (predictedTemp > upper || predictedTemp < lower)) {
                        String message = String.format("【预测性报警】传感器 [%s (%s)] 预测 %d 秒后温度将超阈值: %.2f°C (阈值: %.2f°C ~ %.2f°C)",
                                sensorName, sensorId, (i + 1) * 5, predictedTemp, lower, upper);
                        System.err.println(message);

                        // 4. 创建 SensorData 记录用于上传预测性报警
                        SensorData predictionAlarm = new SensorData();
                        predictionAlarm.setDeviceId(deviceId);
                        predictionAlarm.setSensorId(sensorId);
                        predictionAlarm.setSensorName(sensorName);
                        predictionAlarm.setTimestamp(predictedOccurrenceTime); // 报警时间是预测发生的时间
                        predictionAlarm.setTemperature(predictedTemp); // 将预测的超阈值温度填入 temperature 字段
                        predictionAlarm.setPredictedTemperature(predictedTemp); // predictedTemperature 也填入预测值
                        predictionAlarm.setAlarmTriggered(true); // 标记为报警
                        predictionAlarm.setAlarmMessage(message);
                        predictionAlarm.setStorageLevel("PREDICTED_ALARM"); // 特殊的存储级别，方便识别
                        predictionAlarm.setUploaded(false); // 默认未上传

                        sensorDataRepository.save(predictionAlarm);
                        // 立即上传 (高优先级)
                        cloudUploadService.uploadData(predictionAlarm);

                        // 发现第一个未来超阈值点就预警，避免多次重复报警
                        // 如果需要同时预警所有超阈值的点，则删除 break
                        break;
                    }
                }
            }
        }
    }
}
