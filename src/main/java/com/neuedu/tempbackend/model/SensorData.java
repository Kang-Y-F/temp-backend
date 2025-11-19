package com.neuedu.tempbackend.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity // JPA实体注解
@Table(name = "sensor_data") // 明确指定数据库表名
public class SensorData {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)// 推荐使用 GenerationType.IDENTITY 对于 SQLite, MySQL 等自增主键
    private Long id; // 数据ID

    private String deviceId; // 设备ID，用于标识是哪个边缘设备的数据

    @Column(nullable = false, length = 50)
    private String sensorId; // 传感器的唯一标识，如 "cold-room-01"

    @Column(length = 100)
    private String sensorName; // 传感器的友好名称

    private LocalDateTime timestamp; // 数据采集时间

    // 原始传感器数据
    private Float temperature; // 温度
    private Float humidity; // 湿度
    private Float pressure; // 压力

    // 预测相关数据
    private Float predictedTemperature; // 预测温度值
    private Boolean alarmTriggered; // 是否触发报警
    private String alarmMessage; // 报警信息

    // 上传状态
    private Boolean isUploaded; // 是否已上传到云端

    // ==================== 新增字段：storageLevel ====================
    @Column(nullable = false, length = 30) // REALTIME, MINUTELY_COMPACTED, HOURLY_COMPACTED, etc.
    private String storageLevel;
    // ================================================================

    // 构造函数、Getter和Setter方法
    public SensorData() {
        this.timestamp = LocalDateTime.now(); // 默认记录当前时间
        this.alarmTriggered = false; // 默认不报警
        this.isUploaded = false; // 默认未上传
        this.storageLevel = "REALTIME"; // 默认是实时数据
    }

    // 新增一个包含所有字段的构造函数，方便批量创建
    public SensorData(String deviceId, String sensorId, String sensorName, LocalDateTime timestamp,
                      Float temperature, Float humidity, Float pressure, Float predictedTemperature,
                      Boolean alarmTriggered, String alarmMessage, Boolean isUploaded, String storageLevel) {
        this.deviceId = deviceId;
        this.sensorId = sensorId;
        this.sensorName = sensorName;
        this.timestamp = timestamp;
        this.temperature = temperature;
        this.humidity = humidity;
        this.pressure = pressure;
        this.predictedTemperature = predictedTemperature;
        this.alarmTriggered = alarmTriggered;
        this.alarmMessage = alarmMessage;
        this.isUploaded = isUploaded;
        this.storageLevel = storageLevel;
    }

    // Getters and Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }

    public String getSensorName() { return sensorName; }
    public void setSensorName(String sensorName) { this.sensorName = sensorName; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public Float getTemperature() { return temperature; }
    public void setTemperature(Float temperature) { this.temperature = temperature; }

    public Float getHumidity() { return humidity; }
    public void setHumidity(Float humidity) { this.humidity = humidity; }

    public Float getPressure() { return pressure; }
    public void setPressure(Float pressure) { this.pressure = pressure; }

    public Float getPredictedTemperature() { return predictedTemperature; }
    public void setPredictedTemperature(Float predictedTemperature) { this.predictedTemperature = predictedTemperature; }

    public Boolean getAlarmTriggered() { return alarmTriggered; }
    public void setAlarmTriggered(Boolean alarmTriggered) { this.alarmTriggered = alarmTriggered; }

    public String getAlarmMessage() { return alarmMessage; }
    public void setAlarmMessage(String alarmMessage) { this.alarmMessage = alarmMessage; }

    public Boolean getUploaded() { return isUploaded; }
    public void setUploaded(Boolean uploaded) { isUploaded = uploaded; }

    public String getStorageLevel() { return storageLevel; }
    public void setStorageLevel(String storageLevel) { this.storageLevel = storageLevel; }

    @Override
    public String toString() {
        return "SensorData{" +
                "id=" + id +
                ", deviceId='" + deviceId + '\'' +
                ", sensorId='" + sensorId + '\'' +
                ", sensorName='" + sensorName + '\'' +
                ", timestamp=" + timestamp +
                ", temperature=" + temperature +
                ", humidity=" + humidity +
                ", pressure=" + pressure +
                ", predictedTemperature=" + predictedTemperature +
                ", alarmTriggered=" + alarmTriggered +
                ", alarmMessage='" + alarmMessage + '\'' +
                ", isUploaded=" + isUploaded +
                ", storageLevel='" + storageLevel + '\'' +
                '}';
    }
}
