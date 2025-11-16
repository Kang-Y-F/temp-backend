package com.neuedu.tempbackend.config;

import com.neuedu.tempbackend.util.JSerialCommWrapper;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.code.DataType; // 导入DataType
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ModbusRtuManager {

    private final ReentrantLock lock = new ReentrantLock();
    private ModbusMaster master;

    @Value("${modbus.serial.port}")     private String port;
    @Value("${modbus.serial.baudRate}") private int baud;
    @Value("${modbus.serial.dataBits}") private int dataBits;
    @Value("${modbus.serial.stopBits}") private int stopBits;
    @Value("${modbus.serial.parity}")   private int parity;

    @Value("${modbus.slaveId}") private int slaveId;

    // 温度寄存器配置 (保持不变)
    @Value("${modbus.register.type}")    private String tempRegType; // holding/input
    @Value("${modbus.register.address}") private int tempAddress;
    @Value("${modbus.register.dataType}") private int tempDataType; // 新增：数据类型配置，方便统一管理
    @Value("${modbus.register.scale}")   private double tempScale;

    // 湿度寄存器配置 (新增)
    @Value("${modbus.humidity.regType:holding}")    private String humidityRegType;
    @Value("${modbus.humidity.address:-1}")         private int humidityAddress; // 默认-1表示未配置
    @Value("${modbus.humidity.dataType:5}")         private int humidityDataType; // 默认DataType.FOUR_BYTE_INT_SIGNED
    @Value("${modbus.humidity.scale:0.01}")         private double humidityScale;

    // 压力寄存器配置 (新增)
    @Value("${modbus.pressure.regType:holding}")    private String pressureRegType;
    @Value("${modbus.pressure.address:-1}")         private int pressureAddress; // 默认-1表示未配置
    @Value("${modbus.pressure.dataType:5}")         private int pressureDataType; // 默认DataType.FOUR_BYTE_INT_SIGNED
    @Value("${modbus.pressure.scale:0.01}")         private double pressureScale;


    // 从 ModbusMaster 获取或初始化连接 (保持不变)
    public ModbusMaster getOrInit() throws Exception {
        lock.lock();
        try {
            if (master == null) {
                var wrapper = new JSerialCommWrapper(port, baud, dataBits, stopBits, parity);
                master = new ModbusFactory().createRtuMaster(wrapper);
                master.setTimeout(2000);
                master.setRetries(1);
                master.init();
            }
            return master;
        } finally {
            lock.unlock();
        }
    }

    // 重配置连接 (保持不变)
    public void reconfigure(String port, int baudRate, int dataBits, int stopBits, int parity) throws Exception {
        lock.lock();
        try {
            if (master != null) {
                master.destroy();
                master = null;
            }
            this.port = port;
            this.baud = baudRate;
            this.dataBits = dataBits;
            this.stopBits = stopBits;
            this.parity = parity;
            getOrInit();
        } finally {
            lock.unlock();
        }
    }

    // 辅助方法：读取指定寄存器的值
    private Optional<Number> readModbusValue(String regType, int address, int dataType) {
        if (address == -1) { // 如果地址未配置，则不读取
            return Optional.empty();
        }
        try {
            ModbusMaster m = getOrInit();
            BaseLocator<Number> locator;
            if ("holding".equalsIgnoreCase(regType)) {
                locator = BaseLocator.holdingRegister(slaveId, address, dataType);
            } else if ("input".equalsIgnoreCase(regType)) {
                locator = BaseLocator.inputRegister(slaveId, address, dataType);
            } else {
                System.err.println("Invalid register type: " + regType);
                return Optional.empty();
            }
            Number raw = m.getValue(locator);
            return Optional.ofNullable(raw);
        } catch (Exception e) {
            System.err.println("Error reading Modbus register [type=" + regType + ", address=" + address + "]: " + e.getMessage());
            // e.printStackTrace(); // 调试时可以打开
            return Optional.empty();
        }
    }

    // 读取温度
    public Optional<Float> readTemperature() {
        return readModbusValue(tempRegType, tempAddress, tempDataType)
                .map(Number::floatValue)
                .map(raw -> (float) (raw * tempScale));
    }

    // 读取湿度 (新增)
    public Optional<Float> readHumidity() {
        if (humidityAddress == -1) {
            return Optional.empty(); // 湿度寄存器未配置
        }
        return readModbusValue(humidityRegType, humidityAddress, humidityDataType)
                .map(Number::floatValue)
                .map(raw -> (float) (raw * humidityScale));
    }

    // 读取压力 (新增)
    public Optional<Float> readPressure() {
        if (pressureAddress == -1) {
            return Optional.empty(); // 压力寄存器未配置
        }
        return readModbusValue(pressureRegType, pressureAddress, pressureDataType)
                .map(Number::floatValue)
                .map(raw -> (float) (raw * pressureScale));
    }
}
