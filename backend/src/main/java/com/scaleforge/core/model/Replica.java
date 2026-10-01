package com.scaleforge.core.model;

import com.scaleforge.core.model.enums.ReplicaStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "replicas")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Replica {
    @Id
    @Column(length = 36)
    private String id; // Container/Task ID

    @Column(name = "node_id", length = 36)
    private String nodeId; // FK to Node.id (optional, can be null during scheduler deployment)

    @Column(name = "service_name", nullable = false, length = 100)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReplicaStatus status;

    @Version
    private Long version;
}
