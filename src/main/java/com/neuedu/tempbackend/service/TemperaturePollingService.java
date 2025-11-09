package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.serotonin.modbus4j.ModbusMaster;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.locator.BaseLocator;

@Service
public class TemperaturePollingService {

    private final ModbusRtuManager manager;

    @Value("${modbus.slaveId}")          private int slaveId;
    @Value("${modbus.register.type}")    private String regType;
    @Value("${modbus.register.address}") private int address;
    @Value("${modbus.register.length}")  private int length;   // 2
    @Value("${modbus.register.scale}")   private double scale; // 0.0001
    @Value("${modbus.pollIntervalMs}")   private long pollMs;

    public record Sample(double value, Instant ts) {}
    private final AtomicReference<Sample> latest =
            new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH));

    public TemperaturePollingService(ModbusRtuManager manager) {
        this.manager = manager;
    }

    @Scheduled(fixedDelayString = "${modbus.pollIntervalMs}")
    public void poll() {
        try {
            ModbusMaster m = manager.getOrInit();

            // 根据配置决定读保持寄存器还是输入寄存器
            BaseLocator<Number> locator;
            if ("holding".equalsIgnoreCase(regType)) {
                locator = BaseLocator.holdingRegister(
                        slaveId,
                        address,
                        DataType.FOUR_BYTE_INT_SIGNED   // 32 位有符号整数
                );
            } else if ("input".equalsIgnoreCase(regType)) {
                locator = BaseLocator.inputRegister(
                        slaveId,
                        address,
                        DataType.FOUR_BYTE_INT_SIGNED
                );
            } else {
                throw new IllegalArgumentException("regType only support holding/input");
            }

            // 直接读取一个 Number，内部会自动发 03/04 功能码，读 2 个寄存器
            System.out.println("开始轮询...");
            Number raw = m.getValue(locator);
            System.out.println("raw = " + raw);
            double temp = raw.intValue() * scale;
            System.out.println("读取到温度：" + temp);
            latest.set(new Sample(temp, Instant.now()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    public Sample latest() { return latest.get(); }
    public long pollIntervalMs() { return pollMs; }
}
