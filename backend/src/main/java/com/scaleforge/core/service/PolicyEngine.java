package com.scaleforge.core.service;

import com.scaleforge.core.model.ScalingDecision;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.model.enums.ScalingAction;
import com.scaleforge.core.repository.NodeRepository;
import com.scaleforge.core.repository.ReplicaRepository;
import com.scaleforge.core.repository.ScalingDecisionRepository;
import com.scaleforge.core.model.enums.ReplicaStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
public class PolicyEngine {

    @Autowired
    private ScalingDecisionRepository decisionRepository;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ReplicaRepository replicaRepository;

    @Value("${scaleforge.scaling.cooldown.up-minutes:10}")
    private int cooldownUpMinutes;

    @Value("${scaleforge.scaling.cooldown.down-minutes:3}")
    private int cooldownDownMinutes;

    private static final int MIN_NODES = 1;
    private static final int MAX_NODES = 5;

    public boolean isActionAllowed(ScalingAction action, String[] reasonHolder) {
        long activeNodes = nodeRepository.countByStatus(NodeStatus.ACTIVE);
        if (activeNodes == 0) {
            activeNodes = 1; // Baseline local default
        }

        // 1. Min capacity check
        if (action == ScalingAction.SCALE_DOWN) {
            long activeReplicas = replicaRepository.countByStatus(ReplicaStatus.RUNNING);
            if (activeReplicas <= 1) {
                reasonHolder[0] = "Policy Blocked: Replica count " + activeReplicas + " is already at minimum threshold (1).";
                log.warn(reasonHolder[0]);
                return false;
            }
        }

        // 2. Max capacity check
        if (action == ScalingAction.SCALE_UP && activeNodes >= MAX_NODES) {
            reasonHolder[0] = "Policy Blocked: Node count " + activeNodes + " has reached maximum limits (" + MAX_NODES + ").";
            log.warn(reasonHolder[0]);
            return false;
        }

        // 3. Cooldown locks check
        Optional<ScalingDecision> lastDecisionOpt = decisionRepository.findFirstByOrderByTimestampDesc();
        if (lastDecisionOpt.isPresent()) {
            ScalingDecision lastDecision = lastDecisionOpt.get();
            // We only care if the last action was actually executed (policy passed) and wasn't NO_ACTION
            if (lastDecision.getPolicyPassed() && lastDecision.getAction() != ScalingAction.NO_ACTION) {
                Instant now = Instant.now();
                long secondsSinceLastAction = Duration.between(lastDecision.getTimestamp(), now).getSeconds();
                
                if (lastDecision.getAction() == ScalingAction.SCALE_UP) {
                    long cooldownLimitSeconds = cooldownUpMinutes * 60L;
                    if (secondsSinceLastAction < cooldownLimitSeconds) {
                        reasonHolder[0] = "Policy Blocked: Cooldown active. Last SCALE_UP occurred " 
                                + secondsSinceLastAction + "s ago (limit is " + cooldownLimitSeconds + "s).";
                        log.warn(reasonHolder[0]);
                        return false;
                    }
                } else if (lastDecision.getAction() == ScalingAction.SCALE_DOWN) {
                    long cooldownLimitSeconds = cooldownDownMinutes * 60L;
                    if (secondsSinceLastAction < cooldownLimitSeconds) {
                        reasonHolder[0] = "Policy Blocked: Cooldown active. Last SCALE_DOWN occurred " 
                                + secondsSinceLastAction + "s ago (limit is " + cooldownLimitSeconds + "s).";
                        log.warn(reasonHolder[0]);
                        return false;
                    }
                }
            }
        }

        reasonHolder[0] = "Policy Passed: Safe to scale.";
        return true;
    }
}
