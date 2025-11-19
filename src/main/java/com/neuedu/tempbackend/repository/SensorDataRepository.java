package com.neuedu.tempbackend.repository;

import com.neuedu.tempbackend.model.SensorData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SensorDataRepository extends JpaRepository<SensorData, Long> {
    // 获取所有传感器的最新N条数据 (通常是 REALTIME)
    List<SensorData> findAllByOrderByTimestampDesc(Pageable pageable);

    // 根据 sensorId 获取某个传感器的最新N条数据 (通常是 REALTIME)
    List<SensorData> findBySensorIdOrderByTimestampDesc(String sensorId, Pageable pageable);

    // 获取某个传感器的报警数据 (无论 storageLevel 是什么，只要报警都查出来)
    List<SensorData> findBySensorIdAndAlarmTriggeredTrueOrderByTimestampDesc(String sensorId, Pageable pageable);

    // 获取所有传感器的报警数据
    List<SensorData> findByAlarmTriggeredTrueOrderByTimestampDesc(Pageable pageable);

    // 获取未上传的数据 (按 storageLevel 区分)
    List<SensorData> findByIsUploadedFalseAndStorageLevel(String storageLevel);

    // 获取所有未上传的报警数据 (作为兜底)
    List<SensorData> findByAlarmTriggeredTrueAndIsUploadedFalse();

    /**
     * 获取指定时间段、指定存储级别的数据，按时间升序。
     * @param sensorId 传感器ID
     * @param start 起始时间
     * @param end 结束时间
     * @param storageLevel 存储级别 (e.g., "REALTIME")
     * @return 匹配的SensorData列表
     */
    List<SensorData> findBySensorIdAndTimestampBetweenAndStorageLevelOrderByTimestampAsc(
            String sensorId, LocalDateTime start, LocalDateTime end, String storageLevel);

    /**
     * 获取指定时间点之前、指定存储级别的数据。
     * 用于批量删除（例如在压缩后删除旧的实时数据）。
     * @param threshold 时间阈值
     * @param storageLevel 存储级别
     * @return 匹配的SensorData列表
     */
    List<SensorData> findByTimestampBeforeAndStorageLevel(LocalDateTime threshold, String storageLevel);

    /**
     * 获取指定传感器在给定时间范围内的所有存储级别的数据，按时间升序。
     * 用于组合查询历史数据，特别是预测输入。
     */
    List<SensorData> findBySensorIdAndTimestampBetweenOrderByTimestampAsc(String sensorId, LocalDateTime start, LocalDateTime end);

    /**
     * 获取指定时间范围内的所有存储级别的数据，按时间降序。
     * 用于前端展示所有传感器的历史数据。
     */
    List<SensorData> findByTimestampBetweenOrderByTimestampDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    // ==================== 批量删除旧数据的方法 (可以考虑直接在这里添加) ====================
    @Modifying
    @Transactional
    @Query("DELETE FROM SensorData sd WHERE sd.timestamp < :threshold AND sd.storageLevel = :storageLevel AND sd.isUploaded = true")
    int deleteUploadedDataByTimestampBeforeAndStorageLevel(@Param("threshold") LocalDateTime threshold, @Param("storageLevel") String storageLevel);

    @Modifying
    @Transactional
    @Query("DELETE FROM SensorData sd WHERE sd.timestamp < :threshold AND sd.storageLevel = :storageLevel")
    int deleteAllByTimestampBeforeAndStorageLevel(@Param("threshold") LocalDateTime threshold, @Param("storageLevel") String storageLevel);

    /**
     * 获取所有传感器在指定时间段、指定存储级别的数据，按时间升序。
     * 用于 getRecentSensorDataForAll 查询。
     * @param start 起始时间
     * @param end 结束时间
     * @param storageLevel 存储级别
     * @return 匹配的SensorData列表
     */
    List<SensorData> findByTimestampBetweenAndStorageLevelOrderByTimestampAsc(
            LocalDateTime start, LocalDateTime end, String storageLevel);
}
