// src/main/java/com/neuedu/tempbackend/service/ConfigSyncService.java
package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.config.ModbusProperties;
import com.neuedu.tempbackend.model.EdgeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 负责从云端拉取配置并动态更新
 */
@Service
public class ConfigSyncService {

    private final RestTemplate restTemplate;
    private final AlarmService alarmService; // <-- 保持注入
    private final TemperaturePollingService temperaturePollingService; // <-- 保持注入

    @Value("${edge.deviceId:jetson-001}")
    private String deviceId;

    @Value("${cloud.config.url:http://your-cloud-backend.com/api/device/{deviceId}/config}")
    private String cloudConfigUrl;

    // 存储当前生效的配置 (可选，如果其他地方需要访问完整配置)
    private volatile EdgeConfig currentConfig;

    @Autowired
    public ConfigSyncService(RestTemplate restTemplate, AlarmService alarmService, TemperaturePollingService temperaturePollingService) {
        this.restTemplate = restTemplate;
        this.alarmService = alarmService;
        this.temperaturePollingService = temperaturePollingService;
    }

    // 应用启动后立即尝试同步一次配置
    @PostConstruct
    public void init() {
        syncConfigFromCloud();
    }

    /**
     * 定时从云端拉取配置。例如每5分钟拉取一次。
     * 可以配置在 application.yml 中：cloud.config.syncIntervalMs=300000
     */
    @Scheduled(fixedDelayString = "${cloud.config.syncIntervalMs:300000}") // 默认每5分钟
    public void syncConfigFromCloud() {
        System.out.println("开始从云端同步配置...");
        try {
            String url = cloudConfigUrl.replace("{deviceId}", deviceId);
            EdgeConfig fetchedConfig = restTemplate.getForObject(url, EdgeConfig.class);

            if (fetchedConfig != null) {
                this.currentConfig = fetchedConfig; // 存储完整配置
                System.out.println("成功从云端同步配置，更新时间: " + fetchedConfig.getLastUpdated());

                // 1. 更新全局报警阈值 (直接调用 AlarmService 方法)
                alarmService.updateGlobalThresholds(fetchedConfig.getAlarmThresholds());

                // 2. 遍历并更新每个传感器的特定报警阈值和轮询间隔
                // 关键点：ConfigSyncService 在这里管理要给AlarmService哪些传感器特定阈值，
                // 而不是让AlarmService反过来从ConfigSyncService中获取
                if (fetchedConfig.getSensorConfigs() != null) {
                    for (ModbusProperties.SensorProperties sensorProp : temperaturePollingService.getAllConfiguredSensors()) {
                        String sensorId = sensorProp.getSensorId();
                        // 查找云端是否有这个传感器的特定配置
                        EdgeConfig.SensorRuntimeConfig runtimeConfig = fetchedConfig.getSensorConfigs().stream()
                                .filter(sc -> sensorId.equals(sc.getSensorId()))
                                .findFirst()
                                .orElse(null);

                        if (runtimeConfig != null) {
                            // 更新传感器特定报警阈值
                            alarmService.updateSensorSpecificThresholds(sensorId, runtimeConfig.getAlarmThresholds());

                            // 更新传感器的轮询间隔
                            if (runtimeConfig.getPollIntervalMs() != null) {
                                temperaturePollingService.updateSensorPollingInterval(sensorId, runtimeConfig.getPollIntervalMs());
                            }
                        } else {
                            // 如果云端配置中没有某个传感器的runtimeConfig，清除其特定阈值
                            alarmService.updateSensorSpecificThresholds(sensorId, null);
                        }
                    }
                } else {
                    // 如果云端完全没有 sensorConfigs 列表，清除所有传感器的特定阈值
                    for (ModbusProperties.SensorProperties sensorProp : temperaturePollingService.getAllConfiguredSensors()) {
                        alarmService.updateSensorSpecificThresholds(sensorProp.getSensorId(), null);
                    }
                }
                // (可选) 可以在这里更新其他配置，如 predictionModel、uploadSchedule 等
            } else {
                System.out.println("从云端获取的配置为空，保持现有配置不变。");
                // 如果云端返回空配置，可以考虑清空所有动态设置，回退到 application.yml 默认值
                alarmService.updateGlobalThresholds(new EdgeConfig.AlarmThresholdsConfig()); // 清空全局云端阈值
                for (ModbusProperties.SensorProperties sensorProp : temperaturePollingService.getAllConfiguredSensors()) {
                    alarmService.updateSensorSpecificThresholds(sensorProp.getSensorId(), null);
                }
            }
        } catch (Exception e) {
            System.err.println("从云端同步配置失败: " + e.getMessage());
            // e.printStackTrace(); // 调试时可以打开
        }
    }

    /**
     * 注意：这个方法已经不再需要了，因为AlarmService现在直接从自身持有的Map获取阈值。
     * 但如果将来有其他服务需要访问传感器的报警阈值，可以保留或重新设计。
     * 这里为了防止编译错误，暂时保留一个空的实现。
     */
    @Deprecated // 标记为已废弃，表示不再推荐使用
    public EdgeConfig.AlarmThresholdsConfig getAlarmThresholdsForSensor(String sensorId) {
        // 由于 AlarmService 不再直接依赖 ConfigSyncService 来获取此Map，
        // 而是 ConfigSyncService 调用 AlarmService 的 setter 来更新 AlarmService 内部的 Map。
        // 所以这个方法在这里就没有直接作用了，或者说它的职责已经转移。
        // 如果有其他 Bean 仍然依赖这个方法，可能需要重新考虑设计。
        // 为避免循环依赖，这个方法不应该直接访问 alarmService.sensorSpecificThresholds
        return null; // 或者抛出 UnsupportedOperationException
    }

    public EdgeConfig getCurrentConfig() {
        return currentConfig;
    }
}

