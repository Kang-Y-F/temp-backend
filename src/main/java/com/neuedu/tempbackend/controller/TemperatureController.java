package com.neuedu.tempbackend.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.config.ModbusRtuManager;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.service.TemperaturePollingService;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class TemperatureController {

    private final TemperaturePollingService pollingService;
    private final ModbusRtuManager manager;
    private final SensorDataRepository sensorDataRepository;

    private final Map<String, ConcurrentHashMap<SseEmitter, ScheduledExecutorService>> sseEmitters = new ConcurrentHashMap<>();


    public TemperatureController(
            TemperaturePollingService pollingService,
            ModbusRtuManager manager,
            SensorDataRepository sensorDataRepository) {
        this.pollingService = pollingService;
        this.manager = manager;
        this.sensorDataRepository = sensorDataRepository;
    }

    // 1. 获取所有已配置的传感器列表
    @GetMapping("/sensors")
    public List<ModbusProperties.SensorProperties> getAllConfiguredSensors() {
        return pollingService.getAllConfiguredSensors();
    }

    // 2. 获取单个传感器的最新 SensorData 对象 (包含预测和报警信息)
    @GetMapping("/sensors/{sensorId}/current-data")
    public ResponseEntity<SensorData> currentSensorData(@PathVariable String sensorId) {
        SensorData latestData = pollingService.getLatestCompleteSensorData(sensorId);
        if (latestData == null || latestData.getSensorId() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(latestData);
    }

    // 3. SSE 实时流：获取某个传感器的实时数据流
    @GetMapping(path = "/sensors/{sensorId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSensorData(@PathVariable String sensorId) {
        SseEmitter emitter = new SseEmitter(0L); // 0L表示永不超时，或者设置一个合理的时间

        if (pollingService.getAllConfiguredSensors().stream().noneMatch(s -> s.getSensorId().equals(sensorId))) {
            emitter.completeWithError(new IllegalArgumentException("Sensor ID " + sensorId + " not configured."));
            return emitter;
        }

        // 为每个 SSE 连接创建独立的调度器
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // 使用 computeIfAbsent 确保每个 sensorId 都有一个 ConcurrentHashMap
        sseEmitters.computeIfAbsent(sensorId, k -> new ConcurrentHashMap<>()).put(emitter, scheduler);

        emitter.onCompletion(() -> {
            scheduler.shutdown(); // 完成时关闭调度器
            sseEmitters.get(sensorId).remove(emitter);
            System.out.println("SSE emitter for " + sensorId + " completed.");
        });
        emitter.onError(e -> {
            scheduler.shutdown(); // 发生错误时关闭调度器
            sseEmitters.get(sensorId).remove(emitter);
            System.err.println("SSE emitter for " + sensorId + " error: " + e.getMessage());
            emitter.completeWithError(e); // 确保emitter关闭
        });
        emitter.onTimeout(() -> {
            scheduler.shutdown(); // 超时时关闭调度器
            sseEmitters.get(sensorId).remove(emitter);
            System.err.println("SSE emitter for " + sensorId + " timed out.");
            emitter.complete(); // 确保emitter关闭
        });


        scheduler.scheduleAtFixedRate(() -> {
            try {
                SensorData latestData = pollingService.getLatestCompleteSensorData(sensorId);
                // 确保数据有效且为实时数据，避免发送聚合数据到实时流
                if (latestData != null && latestData.getSensorId() != null && "REALTIME".equalsIgnoreCase(latestData.getStorageLevel())) {
                    emitter.send(SseEmitter.event()
                            .name("sensorData")
                            .data(latestData));
                }
            } catch (IOException e) {
                System.err.println("SSE stream for " + sensorId + " send error: " + e.getMessage());
                emitter.completeWithError(e);
                // scheduler.shutdown(); // 发生 IOException，外部 onError 会处理
            } catch (Exception e) {
                System.err.println("Error in SSE event for " + sensorId + ": " + e.getMessage());
                emitter.completeWithError(e);
                // scheduler.shutdown(); // 发生其他异常，外部 onError 会处理
            }
        }, 0, pollingService.getDefaultPollIntervalMs(), TimeUnit.MILLISECONDS); // 使用传感器的默认轮询间隔作为发送频率

        return emitter;
    }


    // 4. 列出本机串口，用于前端下拉框
    @GetMapping("/serial/ports")
    public List<Map<String, String>> getAvailableSerialPorts() {
        var list = new ArrayList<Map<String,String>>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            list.add(Map.of(
                    "id", p.getSystemPortName(),
                    "name", p.getDescriptivePortName()
            ));
        }
        return list;
    }

    // 5. 从网页修改串口配置 (此API已过时，现在配置通过 application.yml 或云端下发)
    public record SerialCfg(String port, int baudRate, int dataBits, int stopBits, int parity) {}
    @PostMapping("/serial/config")
    public ResponseEntity<Map<String, Object>> configSerialPort(@RequestBody SerialCfg cfg) {
        return ResponseEntity.badRequest().body(Map.of("error", "This API is deprecated. Serial port configuration is managed via application.yml or cloud sync."));
    }


    // 6. 获取某个传感器的最近N条历史数据
    @GetMapping("/sensors/{sensorId}/history/{count}")
    public List<SensorData> getHistoryDataBySensorId(@PathVariable String sensorId, @PathVariable int count) {
        return pollingService.getRecentSensorDataBySensorId(sensorId, count);
    }

    // 7. 获取所有传感器的最近N条历史数据 (可选)
    @GetMapping("/data/history/{count}")
    public List<SensorData> getHistoryDataForAllSensors(@PathVariable int count) {
        return pollingService.getRecentSensorDataForAll(count);
    }


    // 8. 获取某个传感器的报警数据 (例如，最近N条报警)
    @GetMapping("/sensors/{sensorId}/alarms/{count}")
    public List<SensorData> getAlarmDataBySensorId(@PathVariable String sensorId, @PathVariable int count) {
        // 使用 PageRequest.of(0, count) 创建 Pageable 对象，页码从0开始
        return sensorDataRepository.findBySensorIdAndAlarmTriggeredTrueOrderByTimestampDesc(sensorId, PageRequest.of(0, count));
    }

    // 9. 获取所有传感器的报警数据 (例如，最近N条报警)
    @GetMapping("/data/alarms/{count}")
    public List<SensorData> getAlarmDataForAllSensors(@PathVariable int count) {
        // 使用 PageRequest.of(0, count) 创建 Pageable 对象
        return sensorDataRepository.findByAlarmTriggeredTrueOrderByTimestampDesc(PageRequest.of(0, count));
    }

    // 10. 获取某个传感器指定时间范围内的历史数据 (用于图表或更精细分析)
    @GetMapping("/sensors/{sensorId}/history/range")
    public List<SensorData> getHistoryDataBySensorIdAndRange(
            @PathVariable String sensorId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return sensorDataRepository.findBySensorIdAndTimestampBetweenOrderByTimestampAsc(sensorId, start, end);
    }
}
