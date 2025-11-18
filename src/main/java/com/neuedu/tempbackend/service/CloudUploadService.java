package com.neuedu.tempbackend.service;

import com.neuedu.tempbackend.model.SensorData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class CloudUploadService {

    private final RestTemplate restTemplate;

    @Value("${cloud.upload.url:http://your-cloud-backend.com/api/sensor-data}")
    private String cloudUploadUrl;

    public CloudUploadService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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
            System.out.println("Data uploaded to cloud (single for " + data.getSensorId() + "): " + response);
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
}
