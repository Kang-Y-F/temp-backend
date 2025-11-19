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
            System.out.println("No Modbus serial connections configured.");
            return;
        }

        for (ModbusProperties.ConnectionProperties connProp : modbusProperties.getSerial().getConnections()) {
            try {
                // 合并全局默认值和连接特定值
                // 注意：如果全局默认没有设置，而连接特定也没有设置，这里可能会出现NPE。
                // 推荐在application.yml中提供全局默认值，或者在这里给个兜底
                String port = connProp.getPort();
                int baudRate = Optional.ofNullable(connProp.getBaudRate()).orElse(modbusProperties.getSerial().getBaudRate() != null ? modbusProperties.getSerial().getBaudRate() : 9600); // 兜底值
                int dataBits = Optional.ofNullable(connProp.getDataBits()).orElse(modbusProperties.getSerial().getDataBits() != null ? modbusProperties.getSerial().getDataBits() : 8); // 兜底值
                int stopBits = Optional.ofNullable(connProp.getStopBits()).orElse(modbusProperties.getSerial().getStopBits() != null ? modbusProperties.getSerial().getStopBits() : 1); // 兜底值
                int parity = Optional.ofNullable(connProp.getParity()).orElse(modbusProperties.getSerial().getParity() != null ? modbusProperties.getSerial().getParity() : 0); // 兜底值
                String encoding = Optional.ofNullable(connProp.getEncoding()).orElse(modbusProperties.getSerial().getEncoding() != null ? modbusProperties.getSerial().getEncoding() : "RTU"); // 兜底值

                // 创建并初始化Modbus Master
                ModbusMaster master = createAndInitMaster(port, baudRate, dataBits, stopBits, parity, encoding);
                masters.put(connProp.getName(), master);
                masterLocks.put(connProp.getName(), new ReentrantLock());
                System.out.println("Modbus Master '" + connProp.getName() + "' initialized for port: " + port + ", baud: " + baudRate + ", encoding: " + encoding);
            } catch (Exception e) {
                System.err.println("Failed to initialize Modbus Master for connection '" + connProp.getName() + "' on port " + connProp.getPort() + ": " + e.getMessage());
                // 这里可以选择是否中断应用启动，或者只是记录错误并跳过
            }
        }
    }

    /**
     * 根据配置创建并初始化单个 Modbus Master
     * @param port 串口名称
     * @param baudRate 波特率
     * @param dataBits 数据位
     * @param stopBits 停止位
     * @param parity 校验位
     * @param encoding 编码 (RTU/ASCII)
     * @return 初始化后的 ModbusMaster
     * @throws ModbusInitException 如果初始化失败
     */
    private ModbusMaster createAndInitMaster(String port, int baudRate, int dataBits, int stopBits, int parity, String encoding) throws ModbusInitException {
        // Modbus4j 的 JSerialCommWrapper 不直接支持 encoding 参数，默认为 RTU。
        // 如果需要ASCII，需要手动创建对应的 ASCII master
        JSerialCommWrapper wrapper = new JSerialCommWrapper(port, baudRate, dataBits, stopBits, parity);
        ModbusMaster master;
        if ("ASCII".equalsIgnoreCase(encoding)) {
            master = new ModbusFactory().createAsciiMaster(wrapper);
            System.out.println("Creating ASCII master for port: " + port);
        } else { // 默认为 RTU
            master = new ModbusFactory().createRtuMaster(wrapper);
            System.out.println("Creating RTU master for port: " + port);
        }

        master.setTimeout(2000); // 设置超时时间
        master.setRetries(1);    // 设置重试次数
        master.init();           // 初始化
        return master;
    }

    /**
     * 获取指定连接名称的 ModbusMaster。
     * @param connectionName 连接名称
     * @return ModbusMaster 实例
     * @throws IllegalArgumentException 如果 connectionName 不存在或未初始化
     */
    public ModbusMaster getMaster(String connectionName) {
        ModbusMaster master = masters.get(connectionName);
        if (master == null) {
            throw new IllegalArgumentException("Modbus Master for connection '" + connectionName + "' not found or not initialized.");
        }
        return master;
    }

    /**
     * 读取指定传感器寄存器的值。
     * @param connectionName 串口连接名称
     * @param slaveId Modbus从站ID
     * @param registerConfig 寄存器配置（地址、类型、数据类型、比例因子）
     * @return 读取到的值（已应用比例因子）
     */
    public Optional<Float> readSensorRegister(String connectionName, int slaveId, ModbusProperties.RegisterConfig registerConfig) {
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
        if (lock == null) { // 理论上不会发生，因为masters和masterLocks会同时初始化
            System.err.println("No lock found for connection: " + connectionName);
            return Optional.empty();
        }

        lock.lock(); // 锁定当前串口，确保并发安全
        try {
            BaseLocator<Number> locator;
            if ("holding".equalsIgnoreCase(registerConfig.getRegisterType())) {
                locator = BaseLocator.holdingRegister(slaveId, registerConfig.getAddress(), registerConfig.getDataType());
            } else if ("input".equalsIgnoreCase(registerConfig.getRegisterType())) {
                locator = BaseLocator.inputRegister(slaveId, registerConfig.getAddress(), registerConfig.getDataType());
            } else {
                System.err.println("Invalid register type for sensor [conn=" + connectionName + ", slave=" + slaveId + "]: " + registerConfig.getRegisterType());
                return Optional.empty();
            }
            Number raw = m.getValue(locator);
            return Optional.ofNullable(raw)
                    .map(Number::floatValue)
                    .map(value -> (float) (value * registerConfig.getScale()));

        } catch (ModbusTransportException e) {
            System.err.println("Modbus transport error for sensor [conn=" + connectionName + ", slave=" + slaveId + ", addr=" + registerConfig.getAddress() + "]: " + e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            System.err.println("Error reading Modbus register for sensor [conn=" + connectionName + ", slave=" + slaveId + ", addr=" + registerConfig.getAddress() + "]: " + e.getMessage());
            return Optional.empty();
        } finally {
            lock.unlock(); // 解锁
        }
    }


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
                    System.err.println("Error destroying Modbus Master '" + connectionName + "': " + e.getMessage());
                }
            }
        }
        masters.clear();
        masterLocks.clear();
        System.out.println("All Modbus Masters shut down.");
    }
}
