package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset; // 导入 ZoneOffset
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.LinkedHashMap; // 导入 LinkedHashMap
import java.time.ZoneOffset;

@Service
public class TemperaturePollingService {

    private final ModbusRtuManager manager;
    private final SensorDataRepository sensorDataRepository;
    private final PredictionService predictionService;
    private final AlarmService alarmService;
    private final CloudUploadService cloudUploadService;
    private final ModbusProperties modbusProperties;
    private final ThreadPoolTaskScheduler taskScheduler;

    @Value("${edge.deviceId:jetson-001}") private String deviceId;
    @Value("${cloud.upload.batchSize:50}") private int uploadBatchSize;
    @Value("${cloud.upload.batchIntervalMs:60000}") private long uploadBatchIntervalMs;

    public record Sample(double value, Instant ts) {}
    private final Map<String, AtomicReference<Sample>> latestSensorDataMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<SensorData>> latestCompleteSensorDataMap = new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> scheduledPollingTasks = new ConcurrentHashMap<>();

    @Value("${data.retention.realtimeMinutes:10}")
    private int realtimeRetentionMinutes; // 秒级数据保留时长

    @Value("${data.retention.minutelyHours:24}")
    private int minutelyRetentionHours; // 分钟级数据保留时长

    @Value("${data.retention.hourlyDays:7}")
    private int hourlyRetentionDays; // 小时级数据保留时长

    @Autowired
    public TemperaturePollingService(
            ModbusRtuManager manager,
            SensorDataRepository sensorDataRepository,
            PredictionService predictionService,
            AlarmService alarmService,
            CloudUploadService cloudUploadService,
            ModbusProperties modbusProperties,
            ThreadPoolTaskScheduler taskScheduler) {
        this.manager = manager;
        this.sensorDataRepository = sensorDataRepository;
        this.predictionService = predictionService;
        this.alarmService = alarmService;
        this.cloudUploadService = cloudUploadService;
        this.modbusProperties = modbusProperties;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void initPollingTasks() {
        if (modbusProperties.getSerial() == null || modbusProperties.getSerial().getSensors() == null || modbusProperties.getSerial().getSensors().isEmpty()) {
            System.out.println("No sensors configured for polling in application.yml.");
            return;
        }

        for (ModbusProperties.SensorProperties sensorProp : modbusProperties.getSerial().getSensors()) {
            String sensorId = sensorProp.getSensorId();
            if (sensorId == null || sensorId.isEmpty()) {
                System.err.println("Sensor configuration in application.yml missing sensorId, skipping.");
                continue;
            }

            long initialDelay = Optional.ofNullable(sensorProp.getPollIntervalMs()).orElse(modbusProperties.getPollIntervalMs());

            latestSensorDataMap.put(sensorId, new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH)));
            latestCompleteSensorDataMap.put(sensorId, new AtomicReference<>(new SensorData()));

            ScheduledFuture<?> task = taskScheduler.scheduleWithFixedDelay(
                    () -> pollAndProcessSingleSensor(sensorProp),
                    initialDelay
            );
            scheduledPollingTasks.put(sensorId, task);
            System.out.println("Scheduled initial polling for sensor " + sensorId + " (" + sensorProp.getSensorName() + ") with delay " + initialDelay + "ms");
        }
    }


    /**
     * 动态更新单个传感器的轮询间隔。
     * 此方法由 ConfigSyncService 调用，以响应云端配置更新。
     * @param sensorId 传感器ID
     * @param newPollIntervalMs 新的轮询间隔（毫秒）
     */
    public void updateSensorPollingInterval(String sensorId, long newPollIntervalMs) {
        ScheduledFuture<?> existingTask = scheduledPollingTasks.get(sensorId);
        if (existingTask != null) {
            existingTask.cancel(false); // 取消现有任务
            System.out.println("Canceled existing polling task for sensor " + sensorId);
        }

        // 查找对应的传感器配置（ModbusProperties中存储的是初始配置）
        Optional<ModbusProperties.SensorProperties> sensorPropOpt = modbusProperties.getSerial().getSensors().stream()
                .filter(s -> sensorId.equals(s.getSensorId()))
                .findFirst();

        if (sensorPropOpt.isPresent()) {
            ModbusProperties.SensorProperties sensorProp = sensorPropOpt.get(); // 获取原始配置
            // 重新调度新任务
            ScheduledFuture<?> newTask = taskScheduler.scheduleWithFixedDelay(
                    () -> pollAndProcessSingleSensor(sensorProp), // 仍使用原始配置，只是改变了调度频率
                    newPollIntervalMs
            );
            scheduledPollingTasks.put(sensorId, newTask);
            System.out.println("Updated polling for sensor " + sensorId + " to new delay " + newPollIntervalMs + "ms");
        } else {
            System.err.println("Could not find sensor configuration for ID: " + sensorId + " to update polling interval. Make sure it's in application.yml.");
        }
    }


    /**
     * 轮询并处理单个传感器的逻辑
     * @param sensorProp 传感器的配置属性 (来自 application.yml，包含了 Modbus 寄存器信息)
     */
    public void pollAndProcessSingleSensor(ModbusProperties.SensorProperties sensorProp) {
        String sensorId = sensorProp.getSensorId();
        String sensorName = sensorProp.getSensorName();
        String connectionName = sensorProp.getConnection();
        int slaveId = sensorProp.getSlaveId();

        try {
            System.out.println("开始轮询传感器 [" + sensorName + " (" + sensorId + ")]...");

            // 1. 读取传感器数据
            Optional<Float> tempOpt = manager.readSensorRegister(connectionName, slaveId, sensorProp.getTemperature());
            Optional<Float> humidityOpt = manager.readSensorRegister(connectionName, slaveId, sensorProp.getHumidity());
            Optional<Float> pressureOpt = manager.readSensorRegister(connectionName, slaveId, sensorProp.getPressure());

            // 如果没有配置温度寄存器，或者读取失败，则跳过本次处理
            if (sensorProp.getTemperature() == null || tempOpt.isEmpty()) {
                System.err.println("传感器 [" + sensorName + " (" + sensorId + ")] 未配置或未读取到有效温度数据，跳过本次处理。");
                return;
            }

            Float currentTemperature = tempOpt.get();
            Float currentHumidity = humidityOpt.orElse(null);
            Float currentPressure = pressureOpt.orElse(null);

            // 2. 调用预测服务 (单点预测)
            Float predictedTemperature = predictionService.predict(currentTemperature, currentHumidity, currentPressure);
            System.out.println("传感器 [" + sensorName + " (" + sensorId + ")] 单点预测温度: " + (predictedTemperature != null ? String.format("%.2f", predictedTemperature) + "°C" : "N/A"));


            // 3. 调用报警服务 (传入 sensorId 以便 AlarmService 获取动态阈值)
            boolean isAlarm = alarmService.checkAlarm(sensorId, currentTemperature, predictedTemperature);

            // 4. 创建SensorData实体
            SensorData sensorData = new SensorData();
            sensorData.setDeviceId(deviceId);
            sensorData.setSensorId(sensorId);
            sensorData.setSensorName(sensorName);
            sensorData.setTimestamp(LocalDateTime.now());
            sensorData.setTemperature(currentTemperature);
            sensorData.setHumidity(currentHumidity);
            sensorData.setPressure(currentPressure);
            sensorData.setPredictedTemperature(predictedTemperature);
            sensorData.setAlarmTriggered(isAlarm);
            sensorData.setAlarmMessage(alarmService.getAlarmMessage(sensorId, sensorName, currentTemperature, predictedTemperature));

            // ==================== 设置 storageLevel ====================
            sensorData.setStorageLevel("REALTIME"); // 标记为实时数据
            // ==========================================================

            if (isAlarm) {
                System.err.println("!!! 传感器 [" + sensorName + " (" + sensorId + ")] 实时报警 !!! " + sensorData.getAlarmMessage());
            }

            // 5. 保存到本地数据库 (isUploaded 初始为 false)
            sensorData.setUploaded(false); // 明确设置为 false，等待上传
            sensorDataRepository.save(sensorData);
            System.out.println("传感器 [" + sensorName + " (" + sensorId + ")] 数据已保存到本地数据库: " + sensorData.getId() + ", Level: " + sensorData.getStorageLevel());
            latestCompleteSensorDataMap.get(sensorId).set(sensorData); // 更新最新的完整 SensorData 对象到缓存
            latestSensorDataMap.get(sensorId).set(new Sample(currentTemperature, Instant.now())); // 更新最新的原始值到缓存

            // 6. **立即通过 A 通道上传最新采集的原始数据。**
            boolean uploadSuccess = cloudUploadService.uploadData(sensorData); // 直接上传 SensorData 对象
            if (uploadSuccess) {
                sensorData.setUploaded(true); // 上传成功，更新本地状态
                sensorDataRepository.save(sensorData); // 持久化上传状态
                System.out.println("传感器 [" + sensorName + " (" + sensorId + ")] 最新数据通过 A 通道上传成功。");
            } else {
                System.err.println("传感器 [" + sensorName + " (" + sensorId + ")] 最新数据通过 A 通道上传失败，将在后续批量任务中重试。");
            }

        } catch (Exception e) {
            System.err.println("轮询或处理传感器 [" + sensorName + " (" + sensorId + ")] 数据时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // 定时批量上传所有未上传的数据 (包括实时和聚合数据)
    @Scheduled(fixedDelayString = "${cloud.upload.batchIntervalMs}")
    public void scheduledUploadPendingDataToCloud() {
        cloudUploadService.uploadPendingDataToCloudTask(uploadBatchSize);
    }

    // ==================== 预测所需历史数据获取方法 ====================

    /**
     * 获取用于预测的降采样历史数据。
     * 根据你的 ARIMA 模型输入要求，这里将原始数据降采样到 5 秒一个点。
     * 只从 REALTIME 存储级别中获取数据。
     * @param sensorId 传感器ID
     * @param historyWindowMinutes 需要回溯的历史窗口（分钟）
     * @return 降采样后的 SensorData 列表 (每个点代表 5 秒的平均值)
     */
    public List<SensorData> get5SecondAggregatedHistoryForPrediction(String sensorId, int historyWindowMinutes) {
        LocalDateTime endTime = LocalDateTime.now();
        // 稍微多取一点数据以确保边界对齐，并且确保即使是最近的 5 秒窗口也有数据
        LocalDateTime startTime = endTime.minusMinutes(historyWindowMinutes).minusSeconds(5);

        // 只从 REALTIME 存储级别中获取原始数据
        List<SensorData> rawData = sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, startTime, endTime, "REALTIME");

        List<SensorData> aggregatedData = new ArrayList<>();

        // 对原始数据进行 5 秒平均降采样
        // 使用 LinkedHashMap 保持分组后键的插入顺序，确保时间序列的顺序
        Map<LocalDateTime, List<SensorData>> groupedBy5Seconds = rawData.stream()
                .collect(Collectors.groupingBy(data -> {
                    long epochSecond = data.getTimestamp().toEpochSecond(ZoneOffset.UTC); // 使用 UTC 时区，确保一致性
                    long roundedSecond = (epochSecond / 5) * 5; // 截断到最近的 5 秒边界
                    return LocalDateTime.ofEpochSecond(roundedSecond, 0, ZoneOffset.UTC); // 重新转换为 LocalDateTime
                }, LinkedHashMap::new, Collectors.toList()));

        groupedBy5Seconds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()) // 再次确保时间顺序
                .forEach(entry -> {
                    LocalDateTime timeWindow = entry.getKey();
                    List<SensorData> windowData = entry.getValue();

                    if (!windowData.isEmpty()) {
                        double avgTemp = windowData.stream()
                                .filter(d -> d.getTemperature() != null)
                                .mapToDouble(SensorData::getTemperature)
                                .average().orElse(Double.NaN);
                        double avgHumidity = windowData.stream()
                                .filter(d -> d.getHumidity() != null)
                                .mapToDouble(SensorData::getHumidity)
                                .average().orElse(Double.NaN);
                        double avgPressure = windowData.stream()
                                .filter(d -> d.getPressure() != null)
                                .mapToDouble(SensorData::getPressure)
                                .average().orElse(Double.NaN);

                        SensorData aggregated = new SensorData();
                        aggregated.setSensorId(sensorId);
                        aggregated.setTimestamp(timeWindow);
                        aggregated.setTemperature(Double.isNaN(avgTemp) ? null : (float) avgTemp);
                        aggregated.setHumidity(Double.isNaN(avgHumidity) ? null : (float) avgHumidity);
                        aggregated.setPressure(Double.isNaN(avgPressure) ? null : (float) avgPressure);
                        // 对于预测输入，其他字段如 deviceId, sensorName, predictedTemperature, alarmTriggered 等不需要填充
                        aggregated.setStorageLevel("TEMP_5SEC_AGG"); // 临时标志，不存储到 DB
                        aggregatedData.add(aggregated);
                    }
                });
        return aggregatedData;
    }

    // ==================== 公共查询方法 (已调整以利用 storageLevel) ====================

    /**
     * 获取所有传感器的最近N条历史数据，智能地从不同存储级别中组合数据。
     * @param count 要获取的数据条数
     * @return 组合后的SensorData列表
     */
    public List<SensorData> getRecentSensorDataForAll(int count) {
        LocalDateTime now = LocalDateTime.now();
        List<SensorData> result = new ArrayList<>();

        // 查询最近 realtimeRetentionMinutes 分钟的 REALTIME 数据 (最精细)
        // 确保时间范围覆盖到配置文件中的保留时长
        result.addAll(sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                now.minusMinutes(realtimeRetentionMinutes), now, "REALTIME"));

        // 查询 minutelyRetentionHours 小时内的 MINUTELY_COMPACTED 数据
        result.addAll(sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                now.minusHours(minutelyRetentionHours), now.minusMinutes(realtimeRetentionMinutes), "MINUTELY_COMPACTED")); // 排除 REALTIME 已经覆盖的时间

        // 查询 hourlyRetentionDays 天内的 HOURLY_COMPACTED 数据
        result.addAll(sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                now.minusDays(hourlyRetentionDays), now.minusHours(minutelyRetentionHours), "HOURLY_COMPACTED")); // 排除 MINUTELY_COMPACTED 已经覆盖的时间

        // 合并所有结果，按时间降序排列，取最新 count 条
        return result.stream()
                .sorted(Comparator.comparing(SensorData::getTimestamp).reversed())
                .distinct() // 简单的去重
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * 获取某个传感器的最近N条历史数据，智能地从不同存储级别中组合数据。
     * @param sensorId 传感器ID
     * @param count 要获取的数据条数
     * @return 组合后的SensorData列表
     */
    public List<SensorData> getRecentSensorDataBySensorId(String sensorId, int count) {
        LocalDateTime now = LocalDateTime.now();
        List<SensorData> result = new ArrayList<>();

        // 查询最近 realtimeRetentionMinutes 分钟的 REALTIME 数据
        result.addAll(sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, now.minusMinutes(realtimeRetentionMinutes), now, "REALTIME"));

        // 查询 minutelyRetentionHours 小时内的 MINUTELY_COMPACTED 数据
        result.addAll(sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, now.minusHours(minutelyRetentionHours), now.minusMinutes(realtimeRetentionMinutes), "MINUTELY_COMPACTED"));

        // 查询 hourlyRetentionDays 天内的 HOURLY_COMPACTED 数据
        result.addAll(sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, now.minusDays(hourlyRetentionDays), now.minusHours(minutelyRetentionHours), "HOURLY_COMPACTED"));

        // 合并所有结果，按时间降序排列，取最新 count 条
        return result.stream()
                .sorted(Comparator.comparing(SensorData::getTimestamp).reversed())
                .distinct() // 简单的去重
                .limit(count)
                .collect(Collectors.toList());
    }

    public SensorData getLatestCompleteSensorData(String sensorId) {
        return latestCompleteSensorDataMap.getOrDefault(sensorId, new AtomicReference<>(new SensorData())).get();
    }
    public List<ModbusProperties.SensorProperties> getAllConfiguredSensors() {
        return modbusProperties.getSerial().getSensors();
    }
    public long getDefaultPollIntervalMs() { return modbusProperties.getPollIntervalMs(); }
}
