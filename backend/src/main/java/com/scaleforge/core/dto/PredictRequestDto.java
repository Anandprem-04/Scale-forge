package com.scaleforge.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PredictRequestDto {
    private List<SnapshotDto> history;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotDto {
        private String timestamp;
        private Double avgCpu;
        private Double avgMemory;
        private Integer activeNodes;
        private Integer activeReplicas;
    }
}
