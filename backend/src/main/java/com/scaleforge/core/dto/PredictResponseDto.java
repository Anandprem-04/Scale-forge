package com.scaleforge.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PredictResponseDto {
    private Double predictedCpu;
    private Double confidence;
}
