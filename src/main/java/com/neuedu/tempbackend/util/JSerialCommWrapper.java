package com.neuedu.tempbackend.util;

import com.fazecast.jSerialComm.SerialPort;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.io.IOException; // 确保导入，如果之前删了
import java.io.InputStream;
import java.io.OutputStream;
// import java.util.Arrays; // 如果不需要 bytesToHex，这个也可以删掉

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
        System.out.println("[JSerialCommWrapper] Initialized for port: " + portId +
                ", Baud: " + baudRate + ", DataBits: " + dataBits +
                ", StopBits: " + stopBits + ", Parity: " + parity);
    }

    public String getPortId() {
        return portId;
    }

    @Override
    public void open() throws Exception {
        System.out.println("[JSerialCommWrapper] Attempting to open port: " + portId);
        port = SerialPort.getCommPort(portId);

        SerialPort[] commPorts = SerialPort.getCommPorts();
        System.out.println("[JSerialCommWrapper] Available serial ports:");
        boolean portReallyFound = false;
        for (SerialPort p : commPorts) {
            System.out.println("  - " + p.getSystemPortName() + " (" + p.getDescriptivePortName() + ")");
            if (portId.equals(p.getSystemPortName()) ||
                    portId.equals("/dev/" + p.getSystemPortName()))
            {
                portReallyFound = true;
            }
        }

        if (!portReallyFound) {
            throw new Exception("Serial port '" + portId + "' was not found in the list of available system ports. " +
                    "Please check the port name and ensure the device is connected and drivers are installed.");
        }

        port.setBaudRate(baudRate);
        port.setNumDataBits(dataBits);
        port.setNumStopBits(stopBits == 2 ? SerialPort.TWO_STOP_BITS :
                (stopBits == 1 ? SerialPort.ONE_STOP_BIT : SerialPort.ONE_POINT_FIVE_STOP_BITS));
        port.setParity(parity == 1 ? SerialPort.ODD_PARITY :
                (parity == 2 ? SerialPort.EVEN_PARITY : SerialPort.NO_PARITY));

        // 增大超时时间，可能有助于减少ModbusTransportException，这里你可以继续保持 2000, 1000
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 2000, 1000);

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
            System.out.println("[JSerialCommWrapper] Closed port: " + portId);
        } else if (port != null) {
            System.out.println("[JSerialCommWrapper] Port " + portId + " was already closed or not open.");
        } else {
            System.out.println("[JSerialCommWrapper] Port object is null for " + portId + ", no need to close.");
        }
    }

    // --- 恢复原始的 getInputStream() 方法 ---
    @Override public InputStream getInputStream() { return port.getInputStream(); }

    // --- 恢复原始的 getOutputStream() 方法 ---
    @Override public OutputStream getOutputStream() { return port.getOutputStream(); }

    // --- 移除或注释掉 bytesToHex 辅助方法，因为它不再被使用 ---
    /*
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
    */

    @Override public int getBaudRate() { return baudRate; }
    @Override public int getFlowControlIn() { return 0; }
    @Override public int getFlowControlOut() { return 0; }
    @Override public int getDataBits() { return dataBits; }
    @Override public int getStopBits() { return stopBits; }
    @Override public int getParity() { return parity; }
}
