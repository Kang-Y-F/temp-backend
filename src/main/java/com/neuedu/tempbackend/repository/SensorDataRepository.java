package com.neuedu.tempbackend.repository;

import com.neuedu.tempbackend.model.SensorData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable; // 确保导入 Pageable

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SensorDataRepository extends JpaRepository<SensorData, Long> {
    // 获取所有传感器的最新N条数据
    List<SensorData> findAllByOrderByTimestampDesc(Pageable pageable);

    // 获取未上传到云端的数据
    List<SensorData> findByIsUploadedFalse();

    // 根据 sensorId 获取某个传感器的最新N条数据
    List<SensorData> findBySensorIdOrderByTimestampDesc(String sensorId, Pageable pageable);

    // ==================== 针对图片中报错的修改 START ====================

    // 获取某个传感器的报警数据（新的方法，接受 sensorId 和 Pageable）
    // Spring Data JPA 会自动根据方法名生成查询
    List<SensorData> findBySensorIdAndAlarmTriggeredTrueOrderByTimestampDesc(String sensorId, Pageable pageable);

    // 获取所有传感器的报警数据 (旧方法只接受 count，但现在需要Pageable)
    // 已经有了 findByAlarmTriggeredTrueOrderByTimestampDesc()，现在让它接受 Pageable
    List<SensorData> findByAlarmTriggeredTrueOrderByTimestampDesc(Pageable pageable);

    // ==================== 针对图片中报错的修改 END ====================


    // 可以根据需要添加其他查询方法
    List<SensorData> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
}
