package com.scaleforge.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeRegistrationDto {
    private String hostname;
    private String ipAddress;
}
