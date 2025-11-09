package com.neuedu.tempbackend.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.service.TemperaturePollingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController
@RequestMapping("/api")
public class TemperatureController {

    private final TemperaturePollingService svc;
    private final ModbusRtuManager manager;

    public TemperatureController(TemperaturePollingService svc, ModbusRtuManager manager) {
        this.svc = svc;
        this.manager = manager;
    }

    // 1. 普通 GET：返回当前温度
    @GetMapping("/temperature")
    public Map<String, Object> temperature() {
        var s = svc.latest();
        return Map.of(
                "value", s.value(),
                "unit", "°C",
                "ts", s.ts().toString()
        );
    }

    // 2. SSE 实时流


    @GetMapping(path = "/temperature/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // 默认超时：30 秒，可以自己改；这里设成 0 表示不过期
        SseEmitter emitter = new SseEmitter(0L);

        Timer timer = new Timer(true);
        DateTimeFormatter fmt =
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    var s = svc.latest();
                    String json = "{\"value\":" + s.value()
                            + ",\"unit\":\"°C\",\"ts\":\"" + fmt.format(s.ts()) + "\"}";

                    // SSE 格式：data: xxx\n\n
                    emitter.send(json + "\n\n");
                } catch (Exception e) {
                    emitter.completeWithError(e);
                    cancel(); // 停掉这个定时任务
                }
            }
        }, 0, svc.pollIntervalMs());

        return emitter;
    }

    // 3. 列出本机串口，用于前端下拉框
    @GetMapping("/serial/ports")
    public List<Map<String, String>> ports() {
        var list = new ArrayList<Map<String,String>>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            list.add(Map.of(
                    "id", p.getSystemPortName(),
                    "name", p.getDescriptivePortName()
            ));
        }
        return list;
    }

    // 4. 从网页修改串口配置
    public record SerialCfg(String port, int baudRate, int dataBits, int stopBits, int parity) {}

    @PostMapping("/serial/config")
    public Map<String, Object> config(@RequestBody SerialCfg cfg) throws Exception {
        manager.reconfigure(cfg.port(), cfg.baudRate(), cfg.dataBits(), cfg.stopBits(), cfg.parity());
        return Map.of("ok", true);
    }
}
