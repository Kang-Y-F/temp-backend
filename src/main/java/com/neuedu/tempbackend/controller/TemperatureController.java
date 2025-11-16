package com.neuedu.tempbackend.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.model.SensorData; // 导入SensorData
import com.neuedu.tempbackend.service.TemperaturePollingService;
import com.neuedu.tempbackend.repository.SensorDataRepository; // 导入Repository
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.time.ZoneId; // 用于SseEmitter的时间格式化

@RestController
@RequestMapping("/api")
public class TemperatureController {

    private final TemperaturePollingService pollingService;
    private final ModbusRtuManager manager;
    private final SensorDataRepository sensorDataRepository; // 注入SensorDataRepository

    public TemperatureController(
            TemperaturePollingService pollingService,
            ModbusRtuManager manager,
            SensorDataRepository sensorDataRepository) {
        this.pollingService = pollingService;
        this.manager = manager;
        this.sensorDataRepository = sensorDataRepository;
    }

    // 1. 普通 GET：返回最新的SensorData对象 (包含预测和报警信息)
    @GetMapping("/current-data")
    public SensorData currentSensorData() {
        // 直接从 pollingService 获取最新处理并存储的完整 SensorData 对象
        // 这种方式比从数据库中再查询一次更快，因为数据可能还在内存中
        return pollingService.getLatestCompleteSensorData();
    }


    // 2. SSE 实时流
    @GetMapping(path = "/data/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSensorData() {
        SseEmitter emitter = new SseEmitter(0L);

        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    // 从 pollingService 获取最新的完整 SensorData 对象发送给SSE客户端
                    SensorData latestData = pollingService.getLatestCompleteSensorData();

                    emitter.send(SseEmitter.event()
                            .name("sensorData")
                            .data(latestData));

                } catch (IOException e) {
                    System.err.println("SSE stream error: " + e.getMessage());
                    emitter.completeWithError(e);
                    cancel();
                } catch (Exception e) {
                    System.err.println("Error sending SSE event: " + e.getMessage());
                    emitter.completeWithError(e);
                    cancel();
                }
            }
        }, 0, pollingService.pollIntervalMs());

        return emitter;
    }


    // 3. 列出本机串口，用于前端下拉框 (保持不变)
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

    // 4. 从网页修改串口配置 (保持不变)
    public record SerialCfg(String port, int baudRate, int dataBits, int stopBits, int parity) {}

    @PostMapping("/serial/config")
    public Map<String, Object> config(@RequestBody SerialCfg cfg) throws Exception {
        manager.reconfigure(cfg.port(), cfg.baudRate(), cfg.dataBits(), cfg.stopBits(), cfg.parity());
        return Map.of("ok", true);
    }

    // 5. 获取最近N条历史数据
    @GetMapping("/data/history/{count}")
    public List<SensorData> getHistoryData(@PathVariable int count) {
        // 直接调用 service 的新方法
        return pollingService.getRecentSensorData(count);
    }

    // 6. 获取报警数据 (例如，最近100条报警)
    @GetMapping("/data/alarms")
    public List<SensorData> getAlarmData() {
        // 这里可以从数据库查询报警数据
        // 为简化，直接调用repository获取报警数据，可以再封装一层service
        return sensorDataRepository.findByAlarmTriggeredTrueOrderByTimestampDesc();
    }
}
