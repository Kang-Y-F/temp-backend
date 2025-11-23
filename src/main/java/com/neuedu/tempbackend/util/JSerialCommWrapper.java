package com.neuedu.tempbackend.util;

import com.fazecast.jSerialComm.SerialPort;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.io.InputStream;
import java.io.OutputStream;

public class JSerialCommWrapper implements SerialPortWrapper {

    private final String portId;
    private final int baudRate, dataBits, stopBits, parity;
    private SerialPort port;

    public JSerialCommWrapper(String portId, int baudRate, int dataBits, int stopBits, int parity) {
        this.portId = portId;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
    }
    public String getPortId() {
        return portId;
    }
    @Override
    public void open() throws Exception {
        // ... (保持你之前为了解决端口未找到错误而添加的日志和 portReallyFound 检查逻辑) ...

        port = SerialPort.getCommPort(portId); // 确保这里获取了端口对象

        // ... (省略 Available serial ports 打印和 portReallyFound 检查)


        port.setBaudRate(baudRate);
        port.setNumDataBits(dataBits);
        // 保持原来的 stopBits 逻辑，它和你配置的 1 停止位是匹配的
        port.setNumStopBits(stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT);
        port.setParity(parity == 1 ? SerialPort.ODD_PARITY :
                (parity == 2 ? SerialPort.EVEN_PARITY : SerialPort.NO_PARITY));

        // --- 关键修改：恢复到原始的超时设置，不显式启用 TIMEOUT_WRITE_BLOCKING ---
        // 读超时 100ms，写超时 100ms，并且不强制阻塞写入超时模式
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 100, 100);

        if (!port.openPort()) {
            System.err.println("[JSerialCommWrapper] Failed to open serial port: " + portId + ". Error code: " + port.getLastErrorCode());
            throw new Exception("Open serial port failed: " + portId + ". Error code: " + port.getLastErrorCode());
        }
        System.out.println("[JSerialCommWrapper] Successfully opened port: " + portId + " @ " + baudRate + "bps");
    }


    @Override
    public void close() {
        if (port != null && port.isOpen()) {
            port.closePort();
        }
    }

    @Override public InputStream getInputStream() { return port.getInputStream(); }
    @Override public OutputStream getOutputStream() { return port.getOutputStream(); }
    @Override public int getBaudRate() { return baudRate; }
    @Override public int getFlowControlIn() { return 0; }
    @Override public int getFlowControlOut() { return 0; }
    @Override public int getDataBits() { return dataBits; }
    @Override public int getStopBits() { return stopBits; }
    @Override public int getParity() { return parity; }
}
