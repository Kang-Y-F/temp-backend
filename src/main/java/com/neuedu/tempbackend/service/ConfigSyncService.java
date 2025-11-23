package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.model.EdgeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;

/**
 * 负责从云端拉取配置并动态更新报警阈值等。
 * 注意：为保证轮询频率统一，这里不再动态修改轮询周期，
 * 轮询周期由 application.yml 中的 modbus.pollIntervalMs 决定。
 */
@Service
public class ConfigSyncService {

    private final RestTemplate restTemplate;
    private final AlarmService alarmService;
    private final TemperaturePollingService temperaturePollingService;

    @Value("${edge.deviceId:jetson-001}")
    private String deviceId;

    @Value("${cloud.config.url:http://your-cloud-backend.com/api/device/{deviceId}/config}")
    private String cloudConfigUrl;

    // 当前生效的完整配置（可选）
    private volatile EdgeConfig currentConfig;

    @Autowired
    public ConfigSyncService(RestTemplate restTemplate,
                             AlarmService alarmService,
                             TemperaturePollingService temperaturePollingService) {
        this.restTemplate = restTemplate;
        this.alarmService = alarmService;
        this.temperaturePollingService = temperaturePollingService;
    }

    @PostConstruct
    public void init() {
        syncConfigFromCloud();
    }

    @Scheduled(fixedDelayString = "${cloud.config.syncIntervalMs:300000}") // 默认5分钟
    @Async("cloudConfigSyncExecutor")
    public void syncConfigFromCloud() {
        System.out.println("开始从云端同步配置...");
        try {
            String url = cloudConfigUrl.replace("{deviceId}", deviceId);
            EdgeConfig fetchedConfig = restTemplate.getForObject(url, EdgeConfig.class);

            if (fetchedConfig != null) {
                this.currentConfig = fetchedConfig;
                System.out.println("成功从云端同步配置，更新时间: " + fetchedConfig.getLastUpdated());

                // 1. 更新全局报警阈值
                alarmService.updateGlobalThresholds(fetchedConfig.getAlarmThresholds());

                // 2. 每个传感器应用其特定阈值（如果有）
                for (ModbusProperties.SensorProperties sensorProp :
                        temperaturePollingService.getAllConfiguredSensors()) {

                    String sensorId = sensorProp.getSensorId();
                    EdgeConfig.SensorRuntimeConfig runtimeConfig = null;
                    if (fetchedConfig.getSensorConfigs() != null) {
                        runtimeConfig = fetchedConfig.getSensorConfigs().stream()
                                .filter(sc -> sensorId.equals(sc.getSensorId()))
                                .findFirst()
                                .orElse(null);
                    }

                    if (runtimeConfig != null) {
                        // 更新传感器特定报警阈值
                        alarmService.updateSensorSpecificThresholds(sensorId,
                                runtimeConfig.getAlarmThresholds());
                        // 轮询间隔保持统一，不在此处修改
                    } else {
                        // 云端未配置该传感器 runtimeConfig，则清除特定阈值
                        alarmService.updateSensorSpecificThresholds(sensorId, null);
                    }
                }
            } else {
                System.out.println("从云端获取的配置为空，保持现有配置不变。");
                alarmService.updateGlobalThresholds(null);
                for (ModbusProperties.SensorProperties sensorProp :
                        temperaturePollingService.getAllConfiguredSensors()) {
                    alarmService.updateSensorSpecificThresholds(sensorProp.getSensorId(), null);
                }
            }
        } catch (Exception e) {
            System.err.println("从云端同步配置失败: " + e.getMessage());
        }
    }

    public EdgeConfig getCurrentConfig() {
        return currentConfig;
    }
}
