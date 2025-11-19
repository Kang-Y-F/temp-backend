package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CloudUploadService {

    private final RestTemplate restTemplate;
    private final SensorDataRepository sensorDataRepository;

    @Value("${cloud.upload.url:http://your-cloud-backend.com/api/sensor-data}")
    private String cloudUploadUrl;

    public CloudUploadService(RestTemplate restTemplate, SensorDataRepository sensorDataRepository) {
        this.restTemplate = restTemplate;
        this.sensorDataRepository = sensorDataRepository;
    }

    /**
     * 上传单条SensorData到云端
     * @param data 要上传的SensorData对象
     * @return 是否上传成功
     */
    public boolean uploadData(SensorData data) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SensorData> request = new HttpEntity<>(data, headers);
        try {
            String response = restTemplate.postForObject(cloudUploadUrl, request, String.class);
            System.out.println("Data uploaded to cloud (single for " + data.getSensorId() + ", level: " + data.getStorageLevel() + "): " + response);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to upload single data for sensor " + data.getSensorId() + " to cloud: " + e.getMessage());
            return false;
        }
    }

    /**
     * 批量上传SensorData到云端
     * @param dataList 要上传的SensorData列表
     * @return 是否上传成功
     */
    public boolean uploadBatchData(List<SensorData> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return true;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<SensorData>> request = new HttpEntity<>(dataList, headers);
        try {
            String batchUploadUrl = cloudUploadUrl + "/batch";
            String response = restTemplate.postForObject(batchUploadUrl, request, String.class);
            System.out.println("Data uploaded to cloud (batch): " + response + " records: " + dataList.size());
            return true;
        } catch (Exception e) {
            System.err.println("Failed to upload batch data to cloud: " + e.getMessage());
            return false;
        }
    }

    /**
     * 定时批量上传所有未上传的数据到云端。
     * 这包括实时数据、报警数据、分钟级和小时级聚合数据。
     */
    public void uploadPendingDataToCloudTask(int uploadBatchSize) {
        System.out.println("开始检查并上传未上传数据到云端...");

        List<SensorData> allPendingData = new ArrayList<>();

        // 1. 查找 REALTIME 存储级别且未上传的数据
        allPendingData.addAll(sensorDataRepository.findByIsUploadedFalseAndStorageLevel("REALTIME"));

        // 2. 查找 MINUTELY_COMPACTED 存储级别且未上传的数据
        allPendingData.addAll(sensorDataRepository.findByIsUploadedFalseAndStorageLevel("MINUTELY_COMPACTED"));

        // 3. 查找 HOURLY_COMPACTED 存储级别且未上传的数据
        allPendingData.addAll(sensorDataRepository.findByIsUploadedFalseAndStorageLevel("HOURLY_COMPACTED"));

        // 4. 查找所有未上传的报警数据 (作为兜底，防止通过 A 通道上传失败)
        // 注意：这里的报警数据可能已经包含在上面的列表中，需要去重
        allPendingData.addAll(sensorDataRepository.findByAlarmTriggeredTrueAndIsUploadedFalse());

        // 去重并限制上传数量
        List<SensorData> dataToUpload = allPendingData.stream()
                .distinct() // 根据 equals/hashCode 去重
                .limit(uploadBatchSize)
                .collect(Collectors.toList());

        if (dataToUpload.isEmpty()) {
            System.out.println("没有待上传数据。");
            return;
        }

        System.out.println("发现 " + dataToUpload.size() + " 条待上传数据。");

        boolean success = uploadBatchData(dataToUpload);
        if (success) {
            // 更新本地数据库中所有被上传数据的 isUploaded 状态
            dataToUpload.forEach(data -> data.setUploaded(true));
            sensorDataRepository.saveAll(dataToUpload); // 批量更新已上传的数据状态
            System.out.println("成功上传并更新 " + dataToUpload.size() + " 条数据为已上传状态。");
        } else {
            System.err.println("批量上传失败，数据仍标记为未上传。");
        }
    }
}
