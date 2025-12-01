package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.config.RetentionProperties;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class TemperaturePollingService {

    private final ModbusRtuManager manager;
    private final SensorDataRepository sensorDataRepository;
    private final PredictionService predictionService;
    private final AlarmService alarmService;
    private final CloudUploadService cloudUploadService;
    private final ModbusProperties modbusProperties;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final RetentionProperties retentionProperties;

    @Value("${edge.deviceId:jetson-001}")
    private String deviceId;

    @Value("${cloud.upload.batchSize:50}")
    private int uploadBatchSize;

    @Value("${cloud.upload.batchIntervalMs:60000}")
    private long uploadBatchIntervalMs;

    public record Sample(double value, java.time.Instant ts) {}
    private final Map<String, AtomicReference<Sample>> latestSensorDataMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<SensorData>> latestCompleteSensorDataMap = new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> scheduledConnectionTasks = new ConcurrentHashMap<>();

    private static class ConnectionPollingContext {
        final String connectionName;
        final List<ModbusProperties.SensorProperties> sensors;
        final long periodMs;
        int index = 0;

        ConnectionPollingContext(String connectionName,
                                 List<ModbusProperties.SensorProperties> sensors,
                                 long periodMs) {
            this.connectionName = connectionName;
            this.sensors = sensors;
            this.periodMs = periodMs;
        }

        synchronized ModbusProperties.SensorProperties nextSensor() {
            if (sensors.isEmpty()) return null;
            ModbusProperties.SensorProperties s = sensors.get(index);
            index = (index + 1) % sensors.size();
            return s;
        }
    }

    private final Map<String, ConnectionPollingContext> connectionContexts = new ConcurrentHashMap<>();

    @Value("${data.retention.realtimeMinutes:20}")
    private int realtimeRetentionMinutes;

    @Value("${data.retention.minutelyHours:24}")
    private int minutelyRetentionHours;

    @Value("${data.retention.hourlyDays:7}")
    private int hourlyRetentionDays;

    @Autowired
    public TemperaturePollingService(
            ModbusRtuManager manager,
            SensorDataRepository sensorDataRepository,
            PredictionService predictionService,
            AlarmService alarmService,
            CloudUploadService cloudUploadService,
            ModbusProperties modbusProperties,
            ThreadPoolTaskScheduler taskScheduler,
            RetentionProperties retentionProperties
    ) {
        this.manager = manager;
        this.sensorDataRepository = sensorDataRepository;
        this.predictionService = predictionService;
        this.alarmService = alarmService;
        this.cloudUploadService = cloudUploadService;
        this.modbusProperties = modbusProperties;
        this.taskScheduler = taskScheduler;
        this.retentionProperties = retentionProperties;
    }

    @PostConstruct
    public void initPollingTasks() {
        if (modbusProperties.getSerial() == null
                || modbusProperties.getSerial().getSensors() == null
                || modbusProperties.getSerial().getSensors().isEmpty()) {
            System.out.println("No sensors configured for polling in application.yml.");
            return;
        }

        for (ModbusProperties.SensorProperties sensorProp : modbusProperties.getSerial().getSensors()) {
            String sensorId = sensorProp.getSensorId();
            if (sensorId == null || sensorId.isEmpty()) {
                System.err.println("Sensor configuration in application.yml missing sensorId, skipping.");
                continue;
            }
            latestSensorDataMap.put(sensorId,
                    new AtomicReference<>(new Sample(Double.NaN, java.time.Instant.EPOCH)));
            latestCompleteSensorDataMap.put(sensorId,
                    new AtomicReference<>(new SensorData()));
        }

        Map<String, List<ModbusProperties.SensorProperties>> sensorsByConnection =
                modbusProperties.getSerial().getSensors().stream()
                        .filter(s -> s.getConnection() != null && !s.getConnection().isBlank())
                        .collect(Collectors.groupingBy(ModbusProperties.SensorProperties::getConnection));

        long targetPerSensorInterval =
                modbusProperties.getPollIntervalMs() > 0 ? modbusProperties.getPollIntervalMs() : 1000L;

        for (Map.Entry<String, List<ModbusProperties.SensorProperties>> entry : sensorsByConnection.entrySet()) {
            String connectionName = entry.getKey();
            List<ModbusProperties.SensorProperties> sensors = entry.getValue();
            if (sensors.isEmpty()) continue;

            long periodMs = Math.max(50L, targetPerSensorInterval / sensors.size());

            ConnectionPollingContext context =
                    new ConnectionPollingContext(connectionName, sensors, periodMs);
            connectionContexts.put(connectionName, context);

            ScheduledFuture<?> task = taskScheduler.scheduleAtFixedRate(
                    () -> pollNextSensorOnConnection(context),
                    periodMs
            );

            scheduledConnectionTasks.put(connectionName, task);
            System.out.println("Scheduled polling for connection '" + connectionName
                    + "', sensors=" + sensors.size()
                    + ", period=" + periodMs + "ms (per-sensor≈" + (periodMs * sensors.size()) + "ms)");
        }
    }

    @Transactional
    public void pollNextSensorOnConnection(ConnectionPollingContext context) {
        ModbusProperties.SensorProperties sensorProp = context.nextSensor();
        if (sensorProp == null) return;

        String sensorId = sensorProp.getSensorId();
        String sensorName = sensorProp.getSensorName();
        String connectionName = context.connectionName;
        int slaveId = sensorProp.getSlaveId();

        long start = System.currentTimeMillis();

        try {
            Float currentTemperature;
            Float currentHumidity;
            Float currentPressure;

            // ===== 只对 thp-combo（TH11S）使用一次性读 3 寄存器，其它串口保持原逻辑 =====
            if ("thp-combo".equals(connectionName)) {
                ModbusProperties.RegisterConfig tempCfg = sensorProp.getTemperature();
                ModbusProperties.RegisterConfig humCfg  = sensorProp.getHumidity();
                ModbusProperties.RegisterConfig presCfg = sensorProp.getPressure();

                Optional<ModbusRtuManager.Th11sReadings> thOpt =
                        manager.readTh11sAll(connectionName, slaveId, tempCfg, humCfg, presCfg);

                if (tempCfg == null || thOpt.isEmpty() || thOpt.get().temperature == null) {
                    System.err.println("传感器 [" + sensorName + " (" + sensorId + ")] 未读取到温度数据，跳过本次采集。");
                    return;
                }

                ModbusRtuManager.Th11sReadings th = thOpt.get();
                currentTemperature = th.temperature;
                currentHumidity    = th.humidity;
                currentPressure    = th.pressure;

            } else {
                // ========= 原来的三次 readSensorRegister 逻辑（例如 temp-only 串口） =========
                Optional<Float> tempOpt = manager.readSensorRegister(connectionName, slaveId, sensorProp.getTemperature());
                Optional<Float> humidityOpt = manager.readSensorRegister(connectionName, slaveId, sensorProp.getHumidity());
                Optional<Float> pressureOpt = manager.readSensorRegister(connectionName, slaveId, sensorProp.getPressure());

                if (sensorProp.getTemperature() == null || tempOpt.isEmpty()) {
                    System.err.println("传感器 [" + sensorName + " (" + sensorId + ")] 未读取到温度数据，跳过本次采集。");
                    return;
                }

                currentTemperature = tempOpt.get();
                currentHumidity = humidityOpt.orElse(null);
                currentPressure = pressureOpt.orElse(null);
            }

            // ====================== 写 REALTIME 数据 + 异步处理 ======================
            SensorData sensorData = new SensorData();
            sensorData.setDeviceId(deviceId);
            sensorData.setSensorId(sensorId);
            sensorData.setSensorName(sensorName);
            sensorData.setTimestamp(LocalDateTime.now());
            sensorData.setTemperature(currentTemperature);
            sensorData.setHumidity(currentHumidity);
            sensorData.setPressure(currentPressure);
            sensorData.setStorageLevel("REALTIME");
            sensorData.setPredictedTemperature(null);
            sensorData.setAlarmTriggered(false);
            sensorData.setAlarmMessage(null);
            sensorData.setUploaded(false);

            sensorDataRepository.save(sensorData);

            latestSensorDataMap.get(sensorId)
                    .set(new Sample(currentTemperature, java.time.Instant.now()));
            latestCompleteSensorDataMap.get(sensorId).set(sensorData);

            processSensorDataAsync(sensorData);

            long end = System.currentTimeMillis();
            System.out.println("Polled sensor [" + sensorName + " (" + sensorId + ")] on connection '"
                    + connectionName + "', cost=" + (end - start) + "ms");
        } catch (Exception e) {
            System.err.println("轮询传感器 [" + sensorName + " (" + sensorId + ")] 时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Async("cloudUploadExecutor")
    @Transactional
    public void processSensorDataAsync(SensorData sensorData) {
        try {
            Float temperature = sensorData.getTemperature();
            Float humidity = sensorData.getHumidity();
            Float pressure = sensorData.getPressure();

            Float predictedTemperature = predictionService.predict(temperature, humidity, pressure);
            sensorData.setPredictedTemperature(predictedTemperature);

            boolean isAlarm = alarmService.checkAlarm(
                    sensorData.getSensorId(),
                    temperature,
                    predictedTemperature
            );
            sensorData.setAlarmTriggered(isAlarm);
            sensorData.setAlarmMessage(
                    alarmService.getAlarmMessage(
                            sensorData.getSensorId(),
                            sensorData.getSensorName(),
                            temperature,
                            predictedTemperature
                    )
            );

            sensorDataRepository.save(sensorData);

            cloudUploadService.uploadData(sensorData);
        } catch (Exception e) {
            System.err.println("异步处理传感器数据（预测/报警/实时上传）时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelayString = "${cloud.upload.batchIntervalMs}")
    public void scheduledUploadPendingDataToCloud() {
        cloudUploadService.uploadPendingDataToCloudTask(uploadBatchSize);
    }

    public List<SensorData> get5SecondAggregatedHistoryForPrediction(String sensorId, int historyWindowMinutes) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(historyWindowMinutes).minusSeconds(5);

        List<SensorData> rawData = sensorDataRepository
                .findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                        sensorId, startTime, endTime, "REALTIME");

        List<SensorData> aggregatedData = new ArrayList<>();

        Map<LocalDateTime, List<SensorData>> groupedBy5Seconds = rawData.stream()
                .collect(Collectors.groupingBy(data -> {
                    long epochSecond = data.getTimestamp().toEpochSecond(ZoneOffset.UTC);
                    long roundedSecond = (epochSecond / 5) * 5;
                    return LocalDateTime.ofEpochSecond(roundedSecond, 0, ZoneOffset.UTC);
                }, LinkedHashMap::new, Collectors.toList()));

        groupedBy5Seconds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
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
                        aggregated.setStorageLevel("TEMP_5SEC_AGG");
                        aggregatedData.add(aggregated);
                    }
                });
        return aggregatedData;
    }

    public List<SensorData> getRecentSensorDataForAll(int count) {
        LocalDateTime now = LocalDateTime.now();
        int realtimeRetentionMinutes = retentionProperties.getRealtimeMinutes();
        int minutelyRetentionHours   = retentionProperties.getMinutelyHours();
        int hourlyRetentionDays      = retentionProperties.getHourlyDays();
        List<SensorData> result = new ArrayList<>();

        result.addAll(sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                now.minusMinutes(realtimeRetentionMinutes), now, "REALTIME"));

        result.addAll(sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                now.minusHours(minutelyRetentionHours), now.minusMinutes(realtimeRetentionMinutes), "MINUTELY_COMPACTED"));

        result.addAll(sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                now.minusDays(hourlyRetentionDays), now.minusHours(minutelyRetentionHours), "HOURLY_COMPACTED"));

        return result.stream()
                .sorted(Comparator.comparing(SensorData::getTimestamp).reversed())
                .distinct()
                .limit(count)
                .collect(Collectors.toList());
    }

    public List<SensorData> getRecentSensorDataBySensorId(String sensorId, int count) {
        LocalDateTime now = LocalDateTime.now();
        List<SensorData> result = new ArrayList<>();

        result.addAll(sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, now.minusMinutes(realtimeRetentionMinutes), now, "REALTIME"));

        result.addAll(sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, now.minusHours(minutelyRetentionHours), now.minusMinutes(realtimeRetentionMinutes), "MINUTELY_COMPACTED"));

        result.addAll(sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                sensorId, now.minusDays(hourlyRetentionDays), now.minusHours(minutelyRetentionHours), "HOURLY_COMPACTED"));

        return result.stream()
                .sorted(Comparator.comparing(SensorData::getTimestamp).reversed())
                .distinct()
                .limit(count)
                .collect(Collectors.toList());
    }

    public SensorData getLatestCompleteSensorData(String sensorId) {
        return latestCompleteSensorDataMap.getOrDefault(sensorId,
                new AtomicReference<>(new SensorData())).get();
    }

    public List<ModbusProperties.SensorProperties> getAllConfiguredSensors() {
        return modbusProperties.getSerial().getSensors();
    }

    public long getDefaultPollIntervalMs() {
        return modbusProperties.getPollIntervalMs();
    }
}
