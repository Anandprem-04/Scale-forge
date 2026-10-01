package com.scaleforge.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricReportDto {
    private String containerId;
    private Double cpuPercent;
    private Double memoryUsageMb;
}
