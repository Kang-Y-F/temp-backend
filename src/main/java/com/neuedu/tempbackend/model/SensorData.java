package com.neuedu.tempbackend.model;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.time.LocalDateTime;

@Entity // JPA实体注解
public class SensorData {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id; // 数据ID

    private String deviceId; // 设备ID，用于标识是哪个边缘设备的数据
    private LocalDateTime timestamp; // 数据采集时间

    // 原始传感器数据
    private Float temperature; // 温度
    private Float humidity; // 湿度 (预留，目前设为null)
    private Float pressure; // 压力 (预留，目前设为null)

    // 预测相关数据
    private Float predictedTemperature; // 预测温度值
    private Boolean alarmTriggered; // 是否触发报警
    private String alarmMessage; // 报警信息

    // 上传状态
    private Boolean isUploaded; // 是否已上传到云端

    // 构造函数、Getter和Setter方法
    public SensorData() {
        this.timestamp = LocalDateTime.now(); // 默认记录当前时间
        this.alarmTriggered = false; // 默认不报警
        this.isUploaded = false; // 默认未上传
    }

    // Getters and Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
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

    @Override
    public String toString() {
        return "SensorData{" +
                "id=" + id +
                ", deviceId='" + deviceId + '\'' +
                ", timestamp=" + timestamp +
                ", temperature=" + temperature +
                ", humidity=" + humidity +
                ", pressure=" + pressure +
                ", predictedTemperature=" + predictedTemperature +
                ", alarmTriggered=" + alarmTriggered +
                ", alarmMessage='" + alarmMessage + '\'' +
                ", isUploaded=" + isUploaded +
                '}';
    }
}
