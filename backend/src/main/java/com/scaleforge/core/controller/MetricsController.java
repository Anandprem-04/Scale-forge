package com.scaleforge.core.controller;

import com.scaleforge.core.dto.MetricReportDto;
import com.scaleforge.core.service.MonitoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    @Autowired
    private MonitoringService monitoringService;

    @PostMapping("/report")
    public ResponseEntity<Void> reportMetrics(@RequestBody MetricReportDto dto) {
        monitoringService.processMetricReport(dto);
        return ResponseEntity.ok().build();
    }
}
