package com.neuedu.tempbackend.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 映射云端 /api/device/{deviceId}/config 接口返回的整个配置结构
 */
public class EdgeConfig {
    private String deviceId;
    private LocalDateTime lastUpdated;
    private PredictionModelConfig predictionModel;
    private AlarmThresholdsConfig alarmThresholds; // 全局默认报警阈值
    private UploadScheduleConfig uploadSchedule;
    private List<SensorRuntimeConfig> sensorConfigs; // 每个传感器的运行时配置

    // Getters and Setters...
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
    public PredictionModelConfig getPredictionModel() { return predictionModel; }
    public void setPredictionModel(PredictionModelConfig predictionModel) { this.predictionModel = predictionModel; }
    public AlarmThresholdsConfig getAlarmThresholds() { return alarmThresholds; }
    public void setAlarmThresholds(AlarmThresholdsConfig alarmThresholds) { this.alarmThresholds = alarmThresholds; }
    public UploadScheduleConfig getUploadSchedule() { return uploadSchedule; }
    public void setUploadSchedule(UploadScheduleConfig uploadSchedule) { this.uploadSchedule = uploadSchedule; }
    public List<SensorRuntimeConfig> getSensorConfigs() { return sensorConfigs; }
    public void setSensorConfigs(List<SensorRuntimeConfig> sensorConfigs) { this.sensorConfigs = sensorConfigs; }

    /**
     * 预测模型配置
     */
    public static class PredictionModelConfig {
        private String version;
        private String url;
        private String checksum;

        // Getters and Setters...
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }
    }

    /**
     * 报警阈值配置 (可以是全局的，也可以是传感器的)
     */
    public static class AlarmThresholdsConfig {
        private Float upper;
        private Float lower;
        private Float deviation;

        // Getters and Setters...
        public Float getUpper() { return upper; }
        public void setUpper(Float upper) { this.upper = upper; }
        public Float getLower() { return lower; }
        public void setLower(Float lower) { this.lower = lower; }
        public Float getDeviation() { return deviation; }
        public void setDeviation(Float deviation) { this.deviation = deviation; }
    }

    /**
     * 上传调度配置
     */
    public static class UploadScheduleConfig {
        private Integer batchSize;
        private Long intervalMs;

        // Getters and Setters...
        public Integer getBatchSize() { return batchSize; }
        public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }
        public Long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(Long intervalMs) { this.intervalMs = intervalMs; }
    }

    /**
     * 单个传感器的运行时配置 (来自 sensorConfigs 列表)
     */
    public static class SensorRuntimeConfig {
        private String sensorId;
        private AlarmThresholdsConfig alarmThresholds; // 该传感器的特有报警阈值
        private Long pollIntervalMs; // 该传感器的特有轮询间隔

        // Getters and Setters...
        public String getSensorId() { return sensorId; }
        public void setSensorId(String sensorId) { this.sensorId = sensorId; }
        public AlarmThresholdsConfig getAlarmThresholds() { return alarmThresholds; }
        public void setAlarmThresholds(AlarmThresholdsConfig alarmThresholds) { this.alarmThresholds = alarmThresholds; }
        public Long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(Long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    }
}
