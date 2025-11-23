package com.neuedu.tempbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "data.retention")
public class RetentionProperties {

    private int realtimeMinutes;
    private int minutelyHours;
    private int hourlyDays;

    public int getRealtimeMinutes() {
        return realtimeMinutes;
    }

    public void setRealtimeMinutes(int realtimeMinutes) {
        this.realtimeMinutes = realtimeMinutes;
    }

    public int getMinutelyHours() {
        return minutelyHours;
    }

    public void setMinutelyHours(int minutelyHours) {
        this.minutelyHours = minutelyHours;
    }

    public int getHourlyDays() {
        return hourlyDays;
    }

    public void setHourlyDays(int hourlyDays) {
        this.hourlyDays = hourlyDays;
    }
}
