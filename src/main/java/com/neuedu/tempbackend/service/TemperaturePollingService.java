package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.model.EdgeConfig;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ModbusProperties modbusProperties; // 从这里获取 yml 中的传感器初始配置
    // private final ConfigSyncService configSyncService; // 不再直接注入，避免循环依赖，通过 modbusProperties 访问
    // ConfigSyncService 会调用本服务的方法来动态更新轮询间隔

    @Value("${edge.deviceId:jetson-001}") private String deviceId;
    @Value("${cloud.upload.batchSize:50}") private int uploadBatchSize;
    @Value("${cloud.upload.batchIntervalMs:60000}") private long uploadBatchIntervalMs;

    // 存储每个传感器的最新采样数据，用于Controller的即时查询
    public record Sample(double value, Instant ts) {}
    private final Map<String, AtomicReference<Sample>> latestSensorDataMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<SensorData>> latestCompleteSensorDataMap = new ConcurrentHashMap<>();

    // 用于动态调度每个传感器的轮询任务
    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();


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

        // 遍历所有在 application.yml 中配置的传感器，初始化其调度
        for (ModbusProperties.SensorProperties sensorProp : modbusProperties.getSerial().getSensors()) {
            String sensorId = sensorProp.getSensorId();
            if (sensorId == null || sensorId.isEmpty()) {
                System.err.println("Sensor configuration in application.yml missing sensorId, skipping.");
                continue;
            }

            // 获取初始轮询间隔：优先使用传感器配置，其次使用全局Modbus配置
            long initialDelay = Optional.ofNullable(sensorProp.getPollIntervalMs()).orElse(modbusProperties.getPollIntervalMs());

            latestSensorDataMap.put(sensorId, new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH)));
            latestCompleteSensorDataMap.put(sensorId, new AtomicReference<>(new SensorData()));

            // 为每个传感器安排独立的轮询任务
            // ScheduledFuture 只能取消任务，不能修改延迟，修改需要取消重建
            ScheduledFuture<?> task = taskScheduler.scheduleWithFixedDelay(
                    () -> pollAndProcessSingleSensor(sensorProp), // lambda 捕获 sensorProp
                    initialDelay
            );
            scheduledTasks.put(sensorId, task);
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
        ScheduledFuture<?> existingTask = scheduledTasks.get(sensorId);
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
            scheduledTasks.put(sensorId, newTask);
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

            // 更新最新的即时数据 (用于SSE或其他即时API)
            latestSensorDataMap.get(sensorId).set(new Sample(currentTemperature, Instant.now()));
            System.out.println("传感器 [" + sensorName + " (" + sensorId + ")] 读取到: 温度 " + String.format("%.2f", currentTemperature) + "°C"
                    + (currentHumidity != null ? ", 湿度: " + String.format("%.2f", currentHumidity) + "%" : "")
                    + (currentPressure != null ? ", 压力: " + String.format("%.2f", currentPressure) + "hPa" : ""));

            // 2. 创建SensorData实体
            SensorData sensorData = new SensorData();
            sensorData.setDeviceId(deviceId);
            sensorData.setSensorId(sensorId);
            sensorData.setSensorName(sensorName);
            sensorData.setTimestamp(LocalDateTime.now());
            sensorData.setTemperature(currentTemperature);
            sensorData.setHumidity(currentHumidity);
            sensorData.setPressure(currentPressure);

            // 3. 调用预测服务
            Float predictedTemperature = predictionService.predict(currentTemperature, currentHumidity, currentPressure);
            sensorData.setPredictedTemperature(predictedTemperature);
            System.out.println("传感器 [" + sensorName + " (" + sensorId + ")] 预测温度: " + (predictedTemperature != null ? String.format("%.2f", predictedTemperature) + "°C" : "N/A"));

            // 4. 调用报警服务 (传入 sensorId 以便 AlarmService 获取动态阈值)
            boolean isAlarm = alarmService.checkAlarm(sensorId, currentTemperature, predictedTemperature);
            sensorData.setAlarmTriggered(isAlarm);
            sensorData.setAlarmMessage(alarmService.getAlarmMessage(sensorId, sensorName, currentTemperature, predictedTemperature));
            if (isAlarm) {
                System.err.println("!!! 传感器 [" + sensorName + " (" + sensorId + ")] 报警 !!! " + sensorData.getAlarmMessage());
            }

            // 5. 保存到本地数据库
            sensorDataRepository.save(sensorData);
            System.out.println("传感器 [" + sensorName + " (" + sensorId + ")] 数据已保存到本地数据库: " + sensorData.getId());
            latestCompleteSensorDataMap.get(sensorId).set(sensorData); // 更新最新完整数据

            // 6. 立即上传报警数据（高优先级）
            if (isAlarm) {
                boolean uploaded = cloudUploadService.uploadData(sensorData);
                sensorData.setUploaded(uploaded); // 更新上传状态
                sensorDataRepository.save(sensorData); // 保存更新后的状态
            }

        } catch (Exception e) {
            System.err.println("轮询或处理传感器 [" + sensorName + " (" + sensorId + ")] 数据时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // 定时批量上传未上传的数据到云端 (这个任务仍然是全局的，因为上传是批量的)
    @Scheduled(fixedDelayString = "${cloud.upload.batchIntervalMs}")
    public void uploadPendingDataToCloud() {
        System.out.println("开始检查并上传未上传数据到云端...");
        List<SensorData> pendingData = sensorDataRepository.findByIsUploadedFalse();

        if (pendingData.isEmpty()) {
            System.out.println("没有待上传数据。");
            return;
        }

        System.out.println("发现 " + pendingData.size() + " 条待上传数据。");

        List<SensorData> dataToUpload = pendingData.subList(0, Math.min(pendingData.size(), uploadBatchSize));

        boolean success = cloudUploadService.uploadBatchData(dataToUpload);
        if (success) {
            dataToUpload.forEach(data -> data.setUploaded(true));
            sensorDataRepository.saveAll(dataToUpload);
            System.out.println("成功上传并更新 " + dataToUpload.size() + " 条数据为已上传状态。");
        } else {
            System.err.println("批量上传失败，数据仍标记为未上传。");
        }
    }

    // --- 公共查询方法 ---
    public Sample latestSensorRawValue(String sensorId) {
        return latestSensorDataMap.getOrDefault(sensorId, new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH))).get();
    }
    public long getDefaultPollIntervalMs() { return modbusProperties.getPollIntervalMs(); }

    public List<SensorData> getRecentSensorDataForAll(int count) {
        // 获取所有传感器的最新数据
        return sensorDataRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, count));
    }
    public List<SensorData> getRecentSensorDataBySensorId(String sensorId, int count) {
        // 根据 sensorId 获取某个传感器的最新数据
        return sensorDataRepository.findBySensorIdOrderByTimestampDesc(sensorId, PageRequest.of(0, count));
    }
    public SensorData getLatestCompleteSensorData(String sensorId) {
        return latestCompleteSensorDataMap.getOrDefault(sensorId, new AtomicReference<>(new SensorData())).get();
    }
    // 返回所有已配置的传感器信息，用于API展示
    public List<ModbusProperties.SensorProperties> getAllConfiguredSensors() {
        return modbusProperties.getSerial().getSensors();
    }
}
