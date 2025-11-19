package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // 导入事务注解

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Service
public class DataCompactionService {

    @Autowired
    private SensorDataRepository sensorDataRepository;

    // 配置数据保留策略
    @Value("${data.retention.realtimeMinutes:10}")
    private int realtimeRetentionMinutes; // 秒级数据保留时长

    @Value("${data.retention.minutelyHours:24}")
    private int minutelyRetentionHours; // 分钟级数据保留时长

    @Value("${data.retention.hourlyDays:7}")
    private int hourlyRetentionDays; // 小时级数据保留时长


    @Scheduled(fixedRateString = "${data.compaction.intervalMs:60000}") // 默认每1分钟运行一次
    @Transactional // 确保整个任务在事务中，聚合和删除操作要么都成功，要么都回滚
    public void compactData() {
        System.out.println("开始执行数据稀疏化任务...");
        LocalDateTime now = LocalDateTime.now();

        // 策略1：将 REALTIME 数据聚合为 MINUTELY_COMPACTED
        // 将早于 realtimeRetentionMinutes 且晚于 minutelyRetentionHours 的 REALTIME 数据聚合为 MINUTELY
        LocalDateTime realtimeAggregateStart = now.minusMinutes(realtimeRetentionMinutes); // 聚合的开始时间
        LocalDateTime minutelyRetentionEnd = now.minusHours(minutelyRetentionHours); // 目标 MINUTELY 数据的最远保留时间
        compact(realtimeAggregateStart, minutelyRetentionEnd, "REALTIME", "MINUTELY_COMPACTED", ChronoUnit.MINUTES);
        // 清理超出 REALTIME 保留范围且未被聚合的 REALTIME 数据 (例如，报警数据可能只保留 REALTIME 级别)
        purgeOldData(realtimeAggregateStart, "REALTIME");

        // 策略2：将 MINUTELY_COMPACTED 数据聚合为 HOURLY_COMPACTED
        // 将早于 minutelyRetentionHours 且晚于 hourlyRetentionDays 的 MINUTELY 数据聚合为 HOURLY
        LocalDateTime minutelyAggregateStart = now.minusHours(minutelyRetentionHours); // 聚合的开始时间
        LocalDateTime hourlyRetentionEnd = now.minusDays(hourlyRetentionDays); // 目标 HOURLY 数据的最远保留时间
        compact(minutelyAggregateStart, hourlyRetentionEnd, "MINUTELY_COMPACTED", "HOURLY_COMPACTED", ChronoUnit.HOURS);
        // 清理超出 MINUTELY_COMPACTED 保留范围且未被聚合的 MINUTELY_COMPACTED 数据
        purgeOldData(minutelyAggregateStart, "MINUTELY_COMPACTED");

        // 策略3：清理 HOURLY_COMPACTED 数据
        // 清理超出 HOURLY_COMPACTED 保留范围的数据
        purgeOldData(hourlyRetentionEnd, "HOURLY_COMPACTED");


        System.out.println("数据稀疏化任务执行完毕。");
    }

    /**
     * 执行数据聚合和清理
     * @param aggregateStartTime 源级别数据开始聚合的时间（早于此时间的数据将进行聚合）
     * @param targetRetentionEndTime 目标存储级别数据的最远保留时间。聚合后的数据不会超过这个时间。
     * @param sourceStorageLevel 源存储级别
     * @param targetStorageLevel 目标存储级别
     * @param aggregationUnit 聚合单位 (ChronoUnit.MINUTES, ChronoUnit.HOURS等)
     */
    private void compact(LocalDateTime aggregateStartTime, LocalDateTime targetRetentionEndTime,
                         String sourceStorageLevel, String targetStorageLevel, ChronoUnit aggregationUnit) {

        // 1. 获取需要聚合的源级别数据
        // 获取早于 aggregateStartTime 且晚于 targetRetentionEndTime 的源级别数据
        List<SensorData> dataToAggregate = sensorDataRepository.findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                targetRetentionEndTime, aggregateStartTime, sourceStorageLevel);

        if (dataToAggregate.isEmpty()) {
            return;
        }

        // 2. 按传感器ID和聚合单位分组
        Map<String, Map<LocalDateTime, List<SensorData>>> groupedBySensorAndInterval = dataToAggregate.stream()
                .collect(Collectors.groupingBy(SensorData::getSensorId,
                        Collectors.groupingBy(data -> {
                            // 确保按正确的单位截断时间戳，并使用 UTC
                            long epochSecond = data.getTimestamp().toEpochSecond(ZoneOffset.UTC);
                            long roundedSecond;
                            if (aggregationUnit == ChronoUnit.MINUTES) {
                                roundedSecond = (epochSecond / 60) * 60;
                            } else if (aggregationUnit == ChronoUnit.HOURS) {
                                roundedSecond = (epochSecond / 3600) * 3600;
                            } else { // 默认为分钟
                                roundedSecond = (epochSecond / 60) * 60;
                            }
                            return LocalDateTime.ofEpochSecond(roundedSecond, 0, ZoneOffset.UTC);
                        }, LinkedHashMap::new, Collectors.toList())));


        int aggregatedCount = 0;
        int deletedSourceCount = 0;
        List<SensorData> toDeleteFromSource = new ArrayList<>(); // 收集要删除的源数据

        for (Map.Entry<String, Map<LocalDateTime, List<SensorData>>> sensorEntry : groupedBySensorAndInterval.entrySet()) {
            for (Map.Entry<LocalDateTime, List<SensorData>> intervalEntry : sensorEntry.getValue().entrySet()) {
                LocalDateTime intervalStart = intervalEntry.getKey();
                List<SensorData> intervalData = intervalEntry.getValue();

                if (!intervalData.isEmpty()) {
                    // 检查是否已经存在目标级别的聚合数据，避免重复创建（幂等性）
                    List<SensorData> existingAggregated = sensorDataRepository.findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
                            intervalData.get(0).getSensorId(), intervalStart, intervalStart.plus(1, aggregationUnit), targetStorageLevel);

                    if (!existingAggregated.isEmpty()) {
                        System.out.println("跳过已存在的聚合数据 " + targetStorageLevel + " for " + intervalData.get(0).getSensorId() + " at " + intervalStart);
                        toDeleteFromSource.addAll(intervalData); // 尽管已存在，但旧的源数据仍需删除
                        deletedSourceCount += intervalData.size();
                        continue;
                    }

                    // 计算平均值
                    double avgTemp = intervalData.stream().filter(d -> d.getTemperature() != null).mapToDouble(SensorData::getTemperature).average().orElse(Double.NaN);
                    double avgHumidity = intervalData.stream().filter(d -> d.getHumidity() != null).mapToDouble(SensorData::getHumidity).average().orElse(Double.NaN);
                    double avgPressure = intervalData.stream().filter(d -> d.getPressure() != null).mapToDouble(SensorData::getPressure).average().orElse(Double.NaN);

                    SensorData aggregatedData = new SensorData();
                    aggregatedData.setDeviceId(intervalData.get(0).getDeviceId());
                    aggregatedData.setSensorId(intervalData.get(0).getSensorId());
                    aggregatedData.setSensorName(intervalData.get(0).getSensorName());
                    aggregatedData.setTimestamp(intervalStart);
                    aggregatedData.setTemperature(Double.isNaN(avgTemp) ? null : (float) avgTemp);
                    aggregatedData.setHumidity(Double.isNaN(avgHumidity) ? null : (float) avgHumidity);
                    aggregatedData.setPressure(Double.isNaN(avgPressure) ? null : (float) avgPressure);
                    aggregatedData.setStorageLevel(targetStorageLevel);
                    aggregatedData.setAlarmTriggered(false); // 聚合数据默认不触发报警
                    aggregatedData.setAlarmMessage(null); // 清除报警信息
                    aggregatedData.setUploaded(false); // 聚合数据视为新数据，待上传

                    sensorDataRepository.save(aggregatedData);
                    aggregatedCount++;
                    toDeleteFromSource.addAll(intervalData); // 收集要删除的源数据
                    deletedSourceCount += intervalData.size();
                }
            }
        }
        if (aggregatedCount > 0) {
            System.out.println("已将 " + deletedSourceCount + " 条 " + sourceStorageLevel + " 数据聚合为 " + aggregatedCount + " 条 " + targetStorageLevel + " 数据。");
        }

        // 批量删除源数据
        if (!toDeleteFromSource.isEmpty()) {
            sensorDataRepository.deleteAll(toDeleteFromSource);
            System.out.println("批量删除 " + toDeleteFromSource.size() + " 条旧的 " + sourceStorageLevel + " 数据。");
        }
    }

    /**
     * 清理早于指定阈值的特定 storageLevel 的数据。
     * 对于已上传的数据，直接删除。对于未上传的数据，进行警告并保留。
     * @param threshold 时间阈值
     * @param storageLevel 存储级别
     */
    private void purgeOldData(LocalDateTime threshold, String storageLevel) {
        // 先尝试删除已上传的旧数据
        int deletedCount = sensorDataRepository.deleteUploadedDataByTimestampBeforeAndStorageLevel(threshold, storageLevel);
        if (deletedCount > 0) {
            System.out.println("已清理 " + deletedCount + " 条早于 " + threshold + " 的已上传 " + storageLevel + " 级别数据。");
        }

        // 检查是否还有未上传的旧数据，如果有则记录警告
        List<SensorData> pendingUploadData = sensorDataRepository.findByTimestampBeforeAndStorageLevel(threshold, storageLevel);
        if (!pendingUploadData.isEmpty()) {
            System.err.println("警告：存在 " + pendingUploadData.size() + " 条早于 " + threshold + " 且未上传的 " + storageLevel + " 级别数据。请检查上传服务！");
            // 这里不进行删除，以便批量上传任务有机会重试
        }
    }
}
