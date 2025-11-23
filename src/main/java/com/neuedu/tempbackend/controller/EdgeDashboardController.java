package com.neuedu.tempbackend.controller;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.service.TemperaturePollingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/edge")
public class EdgeDashboardController {

    private final TemperaturePollingService pollingService;

    public EdgeDashboardController(TemperaturePollingService pollingService) {
        this.pollingService = pollingService;
    }

    @GetMapping("/sensors")
    public List<ModbusProperties.SensorProperties> sensors() {
        return pollingService.getAllConfiguredSensors();
    }

    @GetMapping("/sensors/{sensorId}/latest")
    public SensorData latest(@PathVariable String sensorId) {
        return pollingService.getLatestCompleteSensorData(sensorId);
    }

    @GetMapping("/sensors/{sensorId}/recent")
    public List<SensorData> recent(@PathVariable String sensorId,
                                   @RequestParam(defaultValue = "200") int limit) {
        return pollingService.getRecentSensorDataBySensorId(sensorId, limit);
    }
}
