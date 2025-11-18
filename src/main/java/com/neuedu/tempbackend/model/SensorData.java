package com.neuedu.tempbackend.model;

// 注意：如果你使用的是 Spring Boot 3.x，请使用 jakarta.* 包
// 如果是 Spring Boot 2.x，请使用 javax.persistence.* 包
// 我这里假设是 Spring Boot 2.x 的项目，所以用 javax

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table; // 明确指定表名是个好习惯
import java.time.LocalDateTime;

@Entity // JPA实体注解
@Table(name = "sensor_data") // 明确指定数据库表名
public class SensorData {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)// 推荐使用 GenerationType.IDENTITY 对于 SQLite, MySQL 等自增主键
    private Long id; // 数据ID

    private String deviceId; // 设备ID，用于标识是哪个边缘设备的数据

    // ==================== 新增字段开始 ====================
    @Column(nullable = false, length = 50, unique = false) // sensorId 应该是唯一的业务标识符，但对于 SensorData 记录，每条记录都会有
    private String sensorId; // 传感器的唯一标识，如 "cold-room-01"

    @Column(length = 100) // sensorName 用于展示，如 "冷藏库1号"
    private String sensorName; // 传感器的友好名称
    // ==================== 新增字段结束 ====================

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

    // 构造函数、Getter和Setter方法
    public SensorData() {
        this.timestamp = LocalDateTime.now(); // 默认记录当前时间
        this.alarmTriggered = false; // 默认不报警
        this.isUploaded = false; // 默认未上传
    }

    // 新增一个包含所有字段的构造函数，方便批量创建
    public SensorData(String deviceId, String sensorId, String sensorName, LocalDateTime timestamp,
                      Float temperature, Float humidity, Float pressure, Float predictedTemperature,
                      Boolean alarmTriggered, String alarmMessage, Boolean isUploaded) {
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
    }

    // Getters and Setters...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    // ==================== 新增字段的 Getter 和 Setter ====================
    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }

    public String getSensorName() { return sensorName; }
    public void setSensorName(String sensorName) { this.sensorName = sensorName; }
    // ==================== 新增字段的 Getter 和 Setter 结束 ====================

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
                '}';
    }
}
