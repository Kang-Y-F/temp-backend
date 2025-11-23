package com.neuedu.tempbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 映射 application.yml 中 modbus.* 配置的POJO类。
 * 用于集中管理Modbus串口连接和传感器配置。
 */
@Component
@ConfigurationProperties(prefix = "modbus")
public class ModbusProperties {

    private Serial serial = new Serial(); // 包含了 connections 和 sensors
    private long pollIntervalMs = 1000; // 全局默认轮询间隔，可被单个传感器覆盖

    public Serial getSerial() { return serial; }
    public void setSerial(Serial serial) { this.serial = serial; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    // ==================== 内部类映射 YAML 结构 ====================

    /**
     * 映射 modbus.serial 配置块
     */
    public static class Serial {
        // 全局串口默认配置，可被 connection 或 sensor 级别覆盖
        private Integer baudRate;
        private Integer dataBits;
        private Integer stopBits;
        private Integer parity; // 0=None, 1=Odd, 2=Even
        private String encoding = "RTU"; // 默认RTU

        private List<ConnectionProperties> connections; // 多个物理串口连接
        private List<SensorProperties> sensors;         // 所有传感器配置列表

        public Integer getBaudRate() { return baudRate; }
        public void setBaudRate(Integer baudRate) { this.baudRate = baudRate; }
        public Integer getDataBits() { return dataBits; }
        public void setDataBits(Integer dataBits) { this.dataBits = dataBits; }
        public Integer getStopBits() { return stopBits; }
        public void setStopBits(Integer stopBits) { this.stopBits = stopBits; }
        public Integer getParity() { return parity; }
        public void setParity(Integer parity) { this.parity = parity; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String encoding) { this.encoding = encoding; }
        public List<ConnectionProperties> getConnections() { return connections; }
        public void setConnections(List<ConnectionProperties> connections) { this.connections = connections; }
        public List<SensorProperties> getSensors() { return sensors; }
        public void setSensors(List<SensorProperties> sensors) { this.sensors = sensors; }
    }

    /**
     * 单个 Modbus 串口连接配置
     */
    public static class ConnectionProperties {
        private String name; // 连接的逻辑名称，如 "temp-only"
        private String port; // 串口端口，如 "COM8" 或 "/dev/ttyACM0"
        private Integer baudRate;
        private Integer dataBits;
        private Integer stopBits;
        private Integer parity;
        private String encoding; // 继承自 serial，也可在此处覆盖

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }
        public Integer getBaudRate() { return baudRate; }
        public void setBaudRate(Integer baudRate) { this.baudRate = baudRate; }
        public Integer getDataBits() { return dataBits; }
        public void setDataBits(Integer dataBits) { this.dataBits = dataBits; }
        public Integer getStopBits() { return stopBits; }
        public void setStopBits(Integer stopBits) { this.stopBits = stopBits; }
        public Integer getParity() { return parity; }
        public void setParity(Integer parity) { this.parity = parity; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String encoding) { this.encoding = encoding; }
    }

    /**
     * 映射 modbus.serial.sensors 下的单个传感器配置
     */
    public static class SensorProperties {
        private String sensorId;    // 传感器的唯一业务ID
        private String sensorName;  // 传感器的友好名称
        private String connection;  // 引用 connections 中的 name，表示该传感器连接到哪个串口
        private int slaveId;        // Modbus从站ID
        private Long pollIntervalMs; // 该传感器独立的轮询间隔，覆盖全局设置

        private RegisterConfig temperature;
        private RegisterConfig humidity;
        private RegisterConfig pressure;

        public String getSensorId() { return sensorId; }
        public void setSensorId(String sensorId) { this.sensorId = sensorId; }
        public String getSensorName() { return sensorName; }
        public void setSensorName(String sensorName) { this.sensorName = sensorName; }
        public String getConnection() { return connection; }
        public void setConnection(String connection) { this.connection = connection; }
        public int getSlaveId() { return slaveId; }
        public void setSlaveId(int slaveId) { this.slaveId = slaveId; }
        public Long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(Long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public RegisterConfig getTemperature() { return temperature; }
        public void setTemperature(RegisterConfig temperature) { this.temperature = temperature; }
        public RegisterConfig getHumidity() { return humidity; }
        public void setHumidity(RegisterConfig humidity) { this.humidity = humidity; }
        public RegisterConfig getPressure() { return pressure; }
        public void setPressure(RegisterConfig pressure) { this.pressure = pressure; }
    }

    /**
     * 映射温度、湿度、压力的寄存器配置
     */
    public static class RegisterConfig {
        private String registerType; // holding/input
        private int address;
        private int dataType;      // Modbus4j DataType code
        private double scale;      // 比例因子
        private String byteOrder;

        public String getRegisterType() { return registerType; }
        public void setRegisterType(String registerType) { this.registerType = registerType; }
        public int getAddress() { return address; }
        public void setAddress(int address) { this.address = address; }
        public int getDataType() { return dataType; }
        public void setDataType(int dataType) { this.dataType = dataType; }
        public double getScale() { return scale; }
        public void setScale(double scale) { this.scale = scale; }
        public String getByteOrder() { return byteOrder; }
        public void setByteOrder(String byteOrder) { this.byteOrder = byteOrder; }
    }
}
