package com.neuedu.tempbackend.config;

import com.neuedu.tempbackend.util.JSerialCommWrapper;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
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
            System.out.println("[ModbusRtuManager] No Modbus serial connections configured.");
            return;
        }

        for (ModbusProperties.ConnectionProperties connProp : modbusProperties.getSerial().getConnections()) {
            try {
                // 合并全局默认值和连接特定值
                String port = connProp.getPort();
                int baudRate = Optional.ofNullable(connProp.getBaudRate()).orElse(modbusProperties.getSerial().getBaudRate() != null ? modbusProperties.getSerial().getBaudRate() : 9600);
                int dataBits = Optional.ofNullable(connProp.getDataBits()).orElse(modbusProperties.getSerial().getDataBits() != null ? modbusProperties.getSerial().getDataBits() : 8);
                int stopBits = Optional.ofNullable(connProp.getStopBits()).orElse(modbusProperties.getSerial().getStopBits() != null ? modbusProperties.getSerial().getStopBits() : 1);
                int parity = Optional.ofNullable(connProp.getParity()).orElse(modbusProperties.getSerial().getParity() != null ? modbusProperties.getSerial().getParity() : 0);
                String encoding = Optional.ofNullable(connProp.getEncoding()).orElse(modbusProperties.getSerial().getEncoding() != null ? modbusProperties.getSerial().getEncoding() : "RTU");

                ModbusMaster master = createAndInitMaster(port, baudRate, dataBits, stopBits, parity, encoding);
                masters.put(connProp.getName(), master);
                masterLocks.put(connProp.getName(), new ReentrantLock());
                System.out.println("[ModbusRtuManager] Modbus Master '" + connProp.getName() + "' initialized for port: " + port + ", baud: " + baudRate + ", encoding: " + encoding);
            } catch (Exception e) {
                System.err.println("[ModbusRtuManager] Failed to initialize Modbus Master for connection '" + connProp.getName() + "' on port " + connProp.getPort() + ": " + e.getMessage());
                e.printStackTrace(); // 打印完整堆栈，以便调试
            }
        }
    }

    private ModbusMaster createAndInitMaster(String port, int baudRate, int dataBits, int stopBits, int parity, String encoding) throws ModbusInitException {
        JSerialCommWrapper wrapper = new JSerialCommWrapper(port, baudRate, dataBits, stopBits, parity);
        ModbusMaster master;
        if ("ASCII".equalsIgnoreCase(encoding)) {
            master = new ModbusFactory().createAsciiMaster(wrapper);
            System.out.println("[ModbusRtuManager] Creating ASCII master for port: " + port);
        } else {
            master = new ModbusFactory().createRtuMaster(wrapper);
            System.out.println("[ModbusRtuManager] Creating RTU master for port: " + port);
        }

        master.setTimeout(modbusProperties.getTimeout() != null ? modbusProperties.getTimeout() : 1500); // 使用配置的超时，或默认1500
        master.setRetries(modbusProperties.getRetries() != null ? modbusProperties.getRetries() : 0); // 使用配置的重试，或默认0

        System.out.println("[ModbusRtuManager] Initializing ModbusMaster for port: " + port + ", Timeout: " + master.getTimeout() + ", Retries: " + master.getRetries());
        master.init();
        System.out.println("[ModbusRtuManager] ModbusMaster initialized successfully for port: " + port);
        return master;
    }

    public ModbusMaster getMaster(String connectionName) {
        ModbusMaster master = masters.get(connectionName);
        if (master == null) {
            throw new IllegalArgumentException("Modbus Master for connection '" + connectionName + "' not found or not initialized.");
        }
        return master;
    }

    public Optional<Float> readSensorRegister(String connectionName, int slaveId, ModbusProperties.RegisterConfig registerConfig) {
        if (registerConfig == null || registerConfig.getAddress() == -1) {
            System.err.println("[ModbusRtuManager] Register config is invalid or address is -1 for connection: " + connectionName);
            return Optional.empty();
        }
        ModbusMaster m = masters.get(connectionName);
        if (m == null) {
            System.err.println("[ModbusRtuManager] Modbus Master not found for connection: " + connectionName);
            return Optional.empty();
        }

        ReentrantLock lock = masterLocks.get(connectionName);
        if (lock == null) {
            System.err.println("[ModbusRtuManager] No lock found for connection: " + connectionName + ". This indicates an initialization issue.");
            return Optional.empty();
        }

        System.out.println(String.format("[ModbusRtuManager] Attempting to read sensor [conn=%s, slave=%d, regType=%s, addr=%d, dataType=%s, scale=%.2f]",
                connectionName, slaveId, registerConfig.getRegisterType(), registerConfig.getAddress(), registerConfig.getDataType(), registerConfig.getScale()));

        lock.lock();
        try {
            BaseLocator<Number> locator;
            if ("holding".equalsIgnoreCase(registerConfig.getRegisterType())) {
                locator = BaseLocator.holdingRegister(slaveId, registerConfig.getAddress(), registerConfig.getDataType());
            } else if ("input".equalsIgnoreCase(registerConfig.getRegisterType())) {
                locator = BaseLocator.inputRegister(slaveId, registerConfig.getAddress(), registerConfig.getDataType());
            } else {
                System.err.println("[ModbusRtuManager] Invalid register type for sensor [conn=" + connectionName + ", slave=" + slaveId + "]: " + registerConfig.getRegisterType());
                return Optional.empty();
            }
            Number raw = m.getValue(locator);

            if (raw != null) {
                float scaledValue = (float) (raw.floatValue() * registerConfig.getScale());
                System.out.println(String.format("[ModbusRtuManager] Successfully read raw value %s (scaled: %.2f) for sensor [conn=%s, slave=%d, addr=%d]",
                        raw.toString(), scaledValue, connectionName, slaveId, registerConfig.getAddress()));
                return Optional.of(scaledValue);
            } else {
                System.err.println(String.format("[ModbusRtuManager] Read returned null for sensor [conn=%s, slave=%d, addr=%d]. Check device connection or register configuration.",
                        connectionName, slaveId, registerConfig.getAddress()));
                return Optional.empty();
            }

        } catch (ModbusTransportException e) {
            System.err.println("[ModbusRtuManager] Modbus transport error for sensor [conn=" + connectionName + ", slave=" + slaveId + ", addr=" + registerConfig.getAddress() + "]: " + e.getMessage());
            e.printStackTrace(); // 打印完整堆栈，以便调试
            return Optional.empty();
        } catch (Exception e) {
            System.err.println("[ModbusRtuManager] General error reading Modbus register for sensor [conn=" + connectionName + ", slave=" + slaveId + ", addr=" + registerConfig.getAddress() + "]: " + e.getMessage());
            e.printStackTrace(); // 打印完整堆栈，以便调试
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }


    @Override
    public void destroy() {
        System.out.println("[ModbusRtuManager] Shutting down all Modbus Masters...");
        for (Map.Entry<String, ModbusMaster> entry : masters.entrySet()) {
            String connectionName = entry.getKey();
            ModbusMaster master = entry.getValue();
            if (master != null) {
                try {
                    master.destroy();
                    System.out.println("[ModbusRtuManager] Modbus Master '" + connectionName + "' destroyed.");
                } catch (Exception e) {
                    System.err.println("[ModbusRtuManager] Error destroying Modbus Master '" + connectionName + "': " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        masters.clear();
        masterLocks.clear();
        System.out.println("[ModbusRtuManager] All Modbus Masters shut down.");
    }
}
