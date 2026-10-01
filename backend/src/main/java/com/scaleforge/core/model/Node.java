package com.scaleforge.core.model;

import com.scaleforge.core.model.enums.NodeStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "nodes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Node {
    @Id
    @Column(length = 36)
    private String id; // UUID string representation

    @Column(nullable = false, length = 100)
    private String hostname;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NodeStatus status;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat;

    @Version
    private Long version;
}
