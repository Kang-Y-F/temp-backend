package com.neuedu.tempbackend.config;

import com.neuedu.tempbackend.util.JSerialCommWrapper;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ModbusRtuManager implements DisposableBean {

    // 使用 ConcurrentHashMap 存储多个 ModbusMaster，key 是 connection name
    private final Map<String, ModbusMaster> masters = new ConcurrentHashMap<>();
    // 为每个master维护一个锁，确保线程安全
    private final Map<String, ReentrantLock> masterLocks = new ConcurrentHashMap<>();

    private final ModbusProperties modbusProperties;

    @Autowired
    public ModbusRtuManager(ModbusProperties modbusProperties) {
        this.modbusProperties = modbusProperties;
    }

    // 在bean初始化后，根据配置创建并初始化所有ModbusMaster
    @PostConstruct
    public void init() {
        if (modbusProperties.getSerial() == null || modbusProperties.getSerial().getConnections() == null) {
            System.out.println("No Modbus serial connections configured.");
            return;
        }

        for (ModbusProperties.ConnectionProperties connProp : modbusProperties.getSerial().getConnections()) {
            try {
                // 合并全局默认值和连接特定值
                String port = connProp.getPort();
                int baudRate = Optional.ofNullable(connProp.getBaudRate())
                        .orElse(modbusProperties.getSerial().getBaudRate() != null
                                ? modbusProperties.getSerial().getBaudRate() : 9600);
                int dataBits = Optional.ofNullable(connProp.getDataBits())
                        .orElse(modbusProperties.getSerial().getDataBits() != null
                                ? modbusProperties.getSerial().getDataBits() : 8);
                int stopBits = Optional.ofNullable(connProp.getStopBits())
                        .orElse(modbusProperties.getSerial().getStopBits() != null
                                ? modbusProperties.getSerial().getStopBits() : 1);
                int parity = Optional.ofNullable(connProp.getParity())
                        .orElse(modbusProperties.getSerial().getParity() != null
                                ? modbusProperties.getSerial().getParity() : 0);
                String encoding = Optional.ofNullable(connProp.getEncoding())
                        .orElse(modbusProperties.getSerial().getEncoding() != null
                                ? modbusProperties.getSerial().getEncoding() : "RTU");

                // 创建并初始化Modbus Master
                ModbusMaster master = createAndInitMaster(port, baudRate, dataBits, stopBits, parity, encoding);
                masters.put(connProp.getName(), master);
                masterLocks.put(connProp.getName(), new ReentrantLock());
                System.out.println("Modbus Master '" + connProp.getName() + "' initialized for port: "
                        + port + ", baud: " + baudRate + ", encoding: " + encoding);
            } catch (Exception e) {
                System.err.println("Failed to initialize Modbus Master for connection '" + connProp.getName()
                        + "' on port " + connProp.getPort() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 根据配置创建并初始化单个 Modbus Master
     */
    private ModbusMaster createAndInitMaster(String port, int baudRate, int dataBits,
                                             int stopBits, int parity, String encoding)
            throws ModbusInitException {

        JSerialCommWrapper wrapper = new JSerialCommWrapper(port, baudRate, dataBits, stopBits, parity);
        ModbusMaster master;
        if ("ASCII".equalsIgnoreCase(encoding)) {
            master = new ModbusFactory().createAsciiMaster(wrapper);
            System.out.println("Creating ASCII master for port: " + port);
        } else { // 默认为 RTU
            master = new ModbusFactory().createRtuMaster(wrapper);
            System.out.println("Creating RTU master for port: " + port);
        }

        master.setTimeout(3000); // 超时时间（ms）
        master.setRetries(2);    // 重试次数，可按需要改大一点
        master.init();
        return master;
    }

    /**
     * 获取指定连接名称的 ModbusMaster。
     */
    public ModbusMaster getMaster(String connectionName) {
        ModbusMaster master = masters.get(connectionName);
        if (master == null) {
            throw new IllegalArgumentException(
                    "Modbus Master for connection '" + connectionName + "' not found or not initialized.");
        }
        return master;
    }

    /**
     * 读取指定传感器寄存器的值（原有逻辑，保持不动）。
     */
    public Optional<Float> readSensorRegister(String connectionName, int slaveId,
                                              ModbusProperties.RegisterConfig registerConfig) {
        if (registerConfig == null || registerConfig.getAddress() == -1) {
            return Optional.empty(); // 寄存器未配置或地址无效
        }
        // 获取对应连接的 ModbusMaster 和锁
        ModbusMaster m = masters.get(connectionName);
        if (m == null) {
            System.err.println("Modbus Master not found for connection: " + connectionName);
            return Optional.empty();
        }

        ReentrantLock lock = masterLocks.get(connectionName);
        if (lock == null) {
            System.err.println("No lock found for connection: " + connectionName);
            return Optional.empty();
        }

        lock.lock(); // 锁定当前串口，确保并发安全
        try {
            BaseLocator<Number> locator;
            if ("holding".equalsIgnoreCase(registerConfig.getRegisterType())) {
                // BaseLocator.holdingRegister -> 功能码 03
                locator = BaseLocator.holdingRegister(
                        slaveId, registerConfig.getAddress(), registerConfig.getDataType());
            } else if ("input".equalsIgnoreCase(registerConfig.getRegisterType())) {
                // BaseLocator.inputRegister -> 功能码 04
                locator = BaseLocator.inputRegister(
                        slaveId, registerConfig.getAddress(), registerConfig.getDataType());
            } else {
                System.err.println("Invalid register type for sensor [conn=" + connectionName
                        + ", slave=" + slaveId + "]: " + registerConfig.getRegisterType());
                return Optional.empty();
            }
            Number raw = m.getValue(locator);
            return Optional.ofNullable(raw)
                    .map(Number::floatValue)
                    .map(value -> (float) (value * registerConfig.getScale()));

        } catch (ModbusTransportException e) {
            System.err.println("Modbus transport error for sensor [conn=" + connectionName
                    + ", slave=" + slaveId + ", addr=" + registerConfig.getAddress() + "]: "
                    + e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            System.err.println("Error reading Modbus register for sensor [conn=" + connectionName
                    + ", slave=" + slaveId + ", addr=" + registerConfig.getAddress() + "]: "
                    + e.getMessage());
            return Optional.empty();
        } finally {
            lock.unlock(); // 解锁
        }
    }

    // ================== 新增：一次性读取 TH11S 温湿度压力（功能码 03） ==================

    /**
     * 一次性读取 TH11S 的温度 / 湿度 / 压力。
     *
     * 使用功能码 03（Read Holding Registers），和原厂软件一致：
     *   请求形如：01 03 00 00 00 03 CRC
     */
    public static class Th11sReadings {
        public final Float temperature;  // ℃（已经乘以 scale）
        public final Float humidity;     // %RH
        public final Float pressure;     // hPa

        public Th11sReadings(Float temperature, Float humidity, Float pressure) {
            this.temperature = temperature;
            this.humidity = humidity;
            this.pressure = pressure;
        }
    }

    public Optional<Th11sReadings> readTh11sAll(
            String connectionName,
            int slaveId,
            ModbusProperties.RegisterConfig tempCfg,
            ModbusProperties.RegisterConfig humCfg,
            ModbusProperties.RegisterConfig presCfg
    ) {
        // 任何一个寄存器都没配置就不用读了
        List<Integer> addrs = new ArrayList<>();
        if (tempCfg != null && tempCfg.getAddress() >= 0) {
            addrs.add(tempCfg.getAddress());
        }
        if (humCfg != null && humCfg.getAddress() >= 0) {
            addrs.add(humCfg.getAddress());
        }
        if (presCfg != null && presCfg.getAddress() >= 0) {
            addrs.add(presCfg.getAddress());
        }
        if (addrs.isEmpty()) {
            return Optional.empty();
        }

        ModbusMaster m = masters.get(connectionName);
        if (m == null) {
            System.err.println("Modbus Master not found for connection: " + connectionName);
            return Optional.empty();
        }
        ReentrantLock lock = masterLocks.get(connectionName);
        if (lock == null) {
            System.err.println("No lock found for connection: " + connectionName);
            return Optional.empty();
        }

        lock.lock();
        try {
            int start = Collections.min(addrs);
            int end = Collections.max(addrs);
            int count = end - start + 1;

            // ★★ 这里使用 ReadHoldingRegistersRequest —— 功能码 03 ★★
            ReadHoldingRegistersRequest req =
                    new ReadHoldingRegistersRequest(slaveId, start, count);
            ReadHoldingRegistersResponse resp =
                    (ReadHoldingRegistersResponse) m.send(req);

            if (resp.isException()) {
                System.err.println("Modbus exception for TH11S [conn=" + connectionName
                        + ", slave=" + slaveId + "]: " + resp.getExceptionMessage());
                return Optional.empty();
            }

            short[] data = resp.getShortData();
            if (data == null || data.length < count) {
                System.err.println("Invalid TH11S data length: " + (data == null ? 0 : data.length)
                        + ", expected >= " + count);
                return Optional.empty();
            }

            Float t = extractAndScale(start, data, tempCfg);
            Float h = extractAndScale(start, data, humCfg);
            Float p = extractAndScale(start, data, presCfg);

            return Optional.of(new Th11sReadings(t, h, p));
        } catch (Exception e) {
            System.err.println("readTh11sAll error [conn=" + connectionName
                    + ", slave=" + slaveId + "]: " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    private Float extractAndScale(int start, short[] data, ModbusProperties.RegisterConfig cfg) {
        if (cfg == null || cfg.getAddress() < 0) {
            return null;
        }
        int idx = cfg.getAddress() - start;
        if (idx < 0 || idx >= data.length) {
            return null;
        }
        short raw = data[idx];
        double rawScale = cfg.getScale();   // double，不可能为null
        double scale = (rawScale == 0.0 ? 1.0 : rawScale);

        return (float) (raw * scale);
    }

    // ========================================================================

    /**
     * Spring应用销毁时，关闭所有Modbus Master连接
     */
    @Override
    public void destroy() {
        System.out.println("Shutting down all Modbus Masters...");
        for (Map.Entry<String, ModbusMaster> entry : masters.entrySet()) {
            String connectionName = entry.getKey();
            ModbusMaster master = entry.getValue();
            if (master != null) {
                try {
                    master.destroy();
                    System.out.println("Modbus Master '" + connectionName + "' destroyed.");
                } catch (Exception e) {
                    System.err.println("Error destroying Modbus Master '" + connectionName
                            + "': " + e.getMessage());
                }
            }
        }
        masters.clear();
        masterLocks.clear();
        System.out.println("All Modbus Masters shut down.");
    }
}
