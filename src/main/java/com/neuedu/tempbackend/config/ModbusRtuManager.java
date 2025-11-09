package com.neuedu.tempbackend.config;

import com.neuedu.tempbackend.util.JSerialCommWrapper;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    // 用于网页切换端口/波特率时重建连接
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
}
