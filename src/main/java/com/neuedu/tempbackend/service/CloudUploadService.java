package com.neuedu.tempbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.tempbackend.model.SensorData;
import com.neuedu.tempbackend.repository.SensorDataRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CloudUploadService {

    private final RestTemplate restTemplate;
    private final SensorDataRepository sensorDataRepository;
    private final ObjectMapper objectMapper;
    @Value("${cloud.upload.url:http://your-cloud-backend.com/api/sensor-data}")
    private String cloudUploadUrl;

    public CloudUploadService(RestTemplate restTemplate, SensorDataRepository sensorDataRepository, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.sensorDataRepository = sensorDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传单条SensorData到云端
     * @param data 要上传的SensorData对象
     * @return 是否上传成功
     */
    @Async("cloudUploadExecutor")
    @Transactional
    public void uploadData(SensorData data) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String jsonPayload = null;
        try {
            jsonPayload = objectMapper.writeValueAsString(data);
            System.out.println("====== 发送单条数据到云端 ======");
            System.out.println("目标URL: " + cloudUploadUrl);
            System.out.println("请求Body (JSON):\n" + jsonPayload);
            System.out.println("================================");

            HttpEntity<SensorData> request = new HttpEntity<>(data, headers);
            String response = restTemplate.postForObject(cloudUploadUrl, request, String.class);
            System.out.println("单条数据上传成功响应: " + response);

            data.setUploaded(true);
            sensorDataRepository.save(data);
            System.out.println("传感器 [" + data.getSensorName() + " (" + data.getSensorId() + ")] 最新数据通过 A 通道上传成功，并更新本地状态。");

        } catch (Exception e) {
            System.err.println("Failed to upload single data for sensor " + (data != null ? data.getSensorId() : "N/A") + " to cloud: " + e.getMessage());
            if (jsonPayload != null) {
                System.err.println("失败的请求Body (JSON):\n" + jsonPayload);
            }
        }
    }
    /**
     * 批量上传SensorData到云端
     * @param dataList 要上传的SensorData列表
     * @return 是否上传成功
     */
    @Async("cloudUploadExecutor") // 使用自定义的异步执行器
    @Transactional
    public void uploadBatchData(List<SensorData> dataList) { // 返回值改为 void
        if (dataList == null || dataList.isEmpty()) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<List<SensorData>> request = new HttpEntity<>(dataList, headers);
        try {
            String batchUploadUrl = cloudUploadUrl + "/batch";
            // 注意：cloudUploadUrl 应该包含完整的协议和端口
            String response = restTemplate.postForObject(batchUploadUrl, request, String.class);
            System.out.println("Data uploaded to cloud (batch): " + response + " records: " + dataList.size());

            // 批量上传成功后，更新本地数据库中所有被上传数据的 isUploaded 状态
            dataList.forEach(data -> data.setUploaded(true));
            sensorDataRepository.saveAll(dataList); // 批量更新已上传的数据状态
            System.out.println("成功上传并更新 " + dataList.size() + " 条数据为已上传状态。");

        } catch (Exception e) {
            System.err.println("Failed to upload batch data to cloud: " + e.getMessage());
            // 如果失败，数据仍标记为 isUploaded = false。
        }
    }

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

        System.out.println("发现 " + dataToUpload.size() + " 条待上传数据，提交给异步批量上传。");

        // 直接调用异步方法，它会立即返回。
        // isUploaded 状态的更新和保存逻辑已移至 uploadBatchData 的异步方法中。
        this.uploadBatchData(dataToUpload); // 注意：这里调用的是 this.uploadBatchData，而不是直接调用
        // 这样才能确保 Spring AOP 代理生效，实现异步调用。

        // 此方法不再需要等待批量上传的结果，因为结果会在异步线程中处理并更新数据库。
        System.out.println("批量上传任务已提交。");
    }
}
