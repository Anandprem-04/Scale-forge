package com.scaleforge.core.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "cluster_snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClusterSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "active_nodes", nullable = false)
    private Integer activeNodes;

    @Column(name = "active_replicas", nullable = false)
    private Integer activeReplicas;

    @Column(name = "avg_cpu", nullable = false)
    private Double avgCpu;

    @Column(name = "avg_memory", nullable = false)
    private Double avgMemory;
}
