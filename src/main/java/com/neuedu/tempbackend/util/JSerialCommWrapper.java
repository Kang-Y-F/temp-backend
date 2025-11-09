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
        port = SerialPort.getCommPort(portId);
        port.setBaudRate(baudRate);
        port.setNumDataBits(dataBits);
        port.setNumStopBits(stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT);
        port.setParity(parity == 1 ? SerialPort.ODD_PARITY :
                (parity == 2 ? SerialPort.EVEN_PARITY : SerialPort.NO_PARITY));
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 1000);
        if (!port.openPort()) {
            throw new Exception("Open serial port failed: " + portId);
        }
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
