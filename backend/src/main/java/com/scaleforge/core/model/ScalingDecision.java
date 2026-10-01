package com.scaleforge.core.model;

import com.scaleforge.core.model.enums.ScalingAction;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "scaling_decisions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScalingDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScalingAction action;

    @Column(name = "decision_reason", nullable = false)
    private String decisionReason;

    @Column(name = "policy_passed", nullable = false)
    private Boolean policyPassed;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 36)
    private String idempotencyKey;

    @Column(name = "target_replicas", nullable = false)
    private Integer targetReplicas;

    @Column(name = "target_nodes", nullable = false)
    private Integer targetNodes;

    @Column(name = "mail_sent", nullable = false)
    private Boolean mailSent;
}
