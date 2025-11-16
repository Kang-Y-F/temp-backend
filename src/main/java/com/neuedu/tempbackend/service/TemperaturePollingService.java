package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TemperaturePollingService {

    private final ModbusRtuManager manager;
    private final SensorDataRepository sensorDataRepository;
    private final PredictionService predictionService;
    private final AlarmService alarmService;
    private final CloudUploadService cloudUploadService;

    @Value("${modbus.slaveId}")          private int slaveId; // 从ModbusRtuManager获取，这里可以移除
    // @Value("${modbus.register.type}")    private String regType; // 从ModbusRtuManager获取，这里可以移除
    // @Value("${modbus.register.address}") private int address; // 从ModbusRtuManager获取，这里可以移除
    // @Value("${modbus.register.length}")  private int length; // 从ModbusRtuManager获取，这里可以移除
    // @Value("${modbus.register.scale}")   private double scale; // 从ModbusRtuManager获取，这里可以移除

    @Value("${modbus.pollIntervalMs}")   private long pollMs;
    @Value("${edge.deviceId:jetson-001}") private String deviceId; // 边缘设备ID，用于数据标识
    @Value("${cloud.upload.batchSize:50}") private int uploadBatchSize; // 批量上传数量
    @Value("${cloud.upload.batchIntervalMs:60000}") private long uploadBatchIntervalMs; // 批量上传间隔

    // 存储最新的采样数据，用于Controller的即时查询
    public record Sample(double value, Instant ts) {}
    private final AtomicReference<Sample> latest = new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH));
    private final AtomicReference<SensorData> latestCompleteSensorData = new AtomicReference<>(new SensorData());


    @Autowired // 构造器注入所有依赖
    public TemperaturePollingService(
            ModbusRtuManager manager,
            SensorDataRepository sensorDataRepository,
            PredictionService predictionService,
            AlarmService alarmService,
            CloudUploadService cloudUploadService) {
        this.manager = manager;
        this.sensorDataRepository = sensorDataRepository;
        this.predictionService = predictionService;
        this.alarmService = alarmService;
        this.cloudUploadService = cloudUploadService;
    }

    // 定时轮询传感器数据，并处理、存储、预测、报警和上传
    @Scheduled(fixedDelayString = "${modbus.pollIntervalMs}")
    public void pollAndProcessSensorData() {
        try {
            System.out.println("开始轮询传感器数据...");

            // 1. 读取传感器数据
            Optional<Float> tempOpt = manager.readTemperature();
            Optional<Float> humidityOpt = manager.readHumidity();
            Optional<Float> pressureOpt = manager.readPressure();

            if (tempOpt.isEmpty()) {
                System.err.println("未读取到有效温度数据，跳过本次处理。");
                return;
            }

            Float currentTemperature = tempOpt.get();
            Float currentHumidity = humidityOpt.orElse(null);
            Float currentPressure = pressureOpt.orElse(null);

            // 更新最新的即时数据 (用于SSE或其他即时API)
            latest.set(new Sample(currentTemperature, Instant.now()));
            System.out.println("读取到温度：" + currentTemperature + "°C"
                    + (currentHumidity != null ? ", 湿度: " + currentHumidity + "%" : "")
                    + (currentPressure != null ? ", 压力: " + currentPressure + "hPa" : ""));


            // 2. 创建SensorData实体
            SensorData sensorData = new SensorData();
            sensorData.setDeviceId(deviceId);
            sensorData.setTimestamp(LocalDateTime.now());
            sensorData.setTemperature(currentTemperature);
            sensorData.setHumidity(currentHumidity);
            sensorData.setPressure(currentPressure);

            // 3. 调用预测服务
            Float predictedTemperature = predictionService.predict(currentTemperature, currentHumidity, currentPressure);
            sensorData.setPredictedTemperature(predictedTemperature);
            System.out.println("预测温度: " + (predictedTemperature != null ? predictedTemperature + "°C" : "N/A"));

            // 4. 调用报警服务
            boolean isAlarm = alarmService.checkAlarm(currentTemperature, predictedTemperature);
            sensorData.setAlarmTriggered(isAlarm);
            sensorData.setAlarmMessage(alarmService.getAlarmMessage(currentTemperature, predictedTemperature));
            if (isAlarm) {
                System.err.println("!!! 报警 !!! " + sensorData.getAlarmMessage());
            }

            // 5. 保存到本地数据库
            sensorDataRepository.save(sensorData);
            System.out.println("数据已保存到本地数据库: " + sensorData.getId());
            latestCompleteSensorData.set(sensorData); // 更新最新完整数据
            // 6. 立即上传报警数据（高优先级）
            if (isAlarm) {
                boolean uploaded = cloudUploadService.uploadData(sensorData);
                sensorData.setUploaded(uploaded); // 更新上传状态
                sensorDataRepository.save(sensorData); // 保存更新后的状态
            }

        } catch (Exception e) {
            System.err.println("轮询或处理传感器数据时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 定时批量上传未上传的数据到云端
    @Scheduled(fixedDelayString = "${cloud.upload.batchIntervalMs}")
    public void uploadPendingDataToCloud() {
        System.out.println("开始检查并上传未上传数据到云端...");
        List<SensorData> pendingData = sensorDataRepository.findByIsUploadedFalse();

        if (pendingData.isEmpty()) {
            System.out.println("没有待上传数据。");
            return;
        }

        System.out.println("发现 " + pendingData.size() + " 条待上传数据。");

        // 批量上传
        // 可以根据uploadBatchSize进行分批，这里为了简化先一次性上传
        List<SensorData> dataToUpload = pendingData.subList(0, Math.min(pendingData.size(), uploadBatchSize));

        boolean success = cloudUploadService.uploadBatchData(dataToUpload);
        if (success) {
            // 更新已上传数据的状态
            dataToUpload.forEach(data -> data.setUploaded(true));
            sensorDataRepository.saveAll(dataToUpload); // 批量更新
            System.out.println("成功上传并更新 " + dataToUpload.size() + " 条数据为已上传状态。");
        } else {
            System.err.println("批量上传失败，数据仍标记为未上传。");
        }
    }


    public Sample latest() { return latest.get(); }
    public long pollIntervalMs() { return pollMs; }

    // 提供获取历史数据的方法，供本地API使用
    public List<SensorData> getRecentSensorData(int count) {
        // 使用 PageRequest 创建一个 Pageable 对象，页码从0开始，每页 count 条数据
        return sensorDataRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, count)); // <--- 修改这里
    }
    // 新增：提供获取最新完整 SensorData 对象的方法
    public SensorData getLatestCompleteSensorData() {
        return latestCompleteSensorData.get();
    }
}
