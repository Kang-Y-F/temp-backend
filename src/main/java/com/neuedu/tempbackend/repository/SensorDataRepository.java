package com.neuedu.tempbackend.repository;

import com.neuedu.tempbackend.model.SensorData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SensorDataRepository extends JpaRepository<SensorData, Long> {
    // 获取最新的N条数据
    List<SensorData> findAllByOrderByTimestampDesc(Pageable pageable);

    // 获取未上传到云端的数据
    List<SensorData> findByIsUploadedFalse();

    // 可以根据需要添加其他查询方法
    List<SensorData> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
    List<SensorData> findByAlarmTriggeredTrueOrderByTimestampDesc();
}
