package com.scaleforge.core.service;

import com.scaleforge.core.dto.PredictRequestDto;
import com.scaleforge.core.dto.PredictResponseDto;
import com.scaleforge.core.model.ClusterSnapshot;
import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.ScalingDecision;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.model.enums.ScalingAction;
import com.scaleforge.core.repository.ClusterSnapshotRepository;
import com.scaleforge.core.repository.NodeRepository;
import com.scaleforge.core.repository.ScalingDecisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BrokerService {

    @Autowired
    private ClusterSnapshotRepository snapshotRepository;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private ScalingDecisionRepository decisionRepository;

    @Autowired
    private PolicyEngine policyEngine;
    @Autowired
    private DecisionEngine decisionEngine;
    @Autowired
    private AwsEc2Service ec2Service;

    @Value("${scaleforge.ml.url:http://localhost:8000/predict}")
    private String mlServiceUrl;

    @Value("${scaleforge.api.key}")
    private String apiKey;

    @Value("${scaleforge.scaling.threshold.up:80.0}")
    private double thresholdUp;

    @Value("${scaleforge.scaling.threshold.down:35.0}")
    private double thresholdDown;

    @Value("${scaleforge.scaling.zombie-timeout-minutes:7}")
    private int zombieTimeoutMinutes;

    private final RestTemplate restTemplate = new RestTemplate();

    @Scheduled(fixedRate = 60000, initialDelay = 10000) // Runs every 60 seconds
    public void evaluateCapacity() {
        log.info("[Capacity Broker] Evaluating cluster capacity...");

        List<ClusterSnapshot> snapshotHistory = snapshotRepository.findTop10ByOrderByTimestampDesc();
        if (snapshotHistory.isEmpty()) {
            log.info("[Capacity Broker] Telemetry history is empty. Skipping capacity check.");
            return;
        }

        double predictedCpu;
        boolean mlSuccess = false;

        // Try getting demand forecast from Python ML Service
        try {
            PredictRequestDto requestDto = buildMlRequest(snapshotHistory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", apiKey);

            HttpEntity<PredictRequestDto> entity = new HttpEntity<>(requestDto, headers);
            PredictResponseDto response = restTemplate.postForObject(mlServiceUrl, entity, PredictResponseDto.class);

            if (response != null && response.getPredictedCpu() != null) {
                double confidence = response.getConfidence() != null ? response.getConfidence() : 1.0;
                if (confidence < 0.5) {
                    predictedCpu = snapshotHistory.get(0).getAvgCpu();
                    log.info("[Capacity Broker] ML prediction confidence is low ({}). Falling back to reactive scaling (current CPU={}%).", 
                            String.format("%.4f", confidence), String.format("%.2f", predictedCpu));
                } else {
                    predictedCpu = response.getPredictedCpu();
                    log.info("[Capacity Broker] ML Forecasting Success. Predicted CPU={}% (Confidence={})", 
                            String.format("%.2f", predictedCpu), String.format("%.4f", confidence));
                }
                mlSuccess = true;
            } else {
                predictedCpu = snapshotHistory.get(0).getAvgCpu();
            }
        } catch (Exception e) {
            // Graceful Fallback: Read raw CPU from the latest snapshot
            predictedCpu = snapshotHistory.get(0).getAvgCpu();
            log.warn("[Capacity Broker] ML Service unreachable. Graceful fallback to Reactive Scaling logic. Cause: {}", e.getMessage());
        }

        ScalingAction recommendedAction = ScalingAction.NO_ACTION;
        String reason = "Cluster metrics stable at predicted CPU " + String.format("%.2f", predictedCpu) + "%.";

        if (predictedCpu > thresholdUp) {
            recommendedAction = ScalingAction.SCALE_UP;
            reason = (mlSuccess ? "ML Predictive " : "Reactive ") + "CPU spike forecast: " 
                    + String.format("%.2f", predictedCpu) + "% exceeds " + thresholdUp + "% threshold.";
        } else if (predictedCpu < thresholdDown) {
            recommendedAction = ScalingAction.SCALE_DOWN;
            reason = (mlSuccess ? "ML Predictive " : "Reactive ") + "CPU drop forecast: " 
                    + String.format("%.2f", predictedCpu) + "% falls below " + thresholdDown + "% threshold.";
        }

        if (recommendedAction == ScalingAction.NO_ACTION) {
            log.info("[Capacity Broker] Capacity evaluation finished: {}", reason);
            return;
        }

        // Validate decision against safety rules in Policy Engine
        String[] policyReasonHolder = new String[1];
        boolean policyPassed = policyEngine.isActionAllowed(recommendedAction, policyReasonHolder);
        String idempotencyKey = UUID.randomUUID().toString();

        long currentReplicas = snapshotHistory.get(0).getActiveReplicas();
        long currentNodes = snapshotHistory.get(0).getActiveNodes();

        if (policyPassed) {
            log.info("[Capacity Broker] Policy Approved action: {}. Executing capacity shift.", recommendedAction);
            if (recommendedAction == ScalingAction.SCALE_UP) {
                decisionEngine.executeScaleUp(idempotencyKey, reason);
            } else {
                decisionEngine.executeScaleDown(idempotencyKey, reason);
            }
        } else {
            log.warn("[Capacity Broker] Policy Engine REJECTED recommended scaling action: {}. Logging audit trace.", recommendedAction);
            
            // Save blocked decision as append-only audit trail
            ScalingDecision blockedDecision = ScalingDecision.builder()
                    .timestamp(Instant.now())
                    .action(recommendedAction)
                    .decisionReason(reason + " | Policy block details: " + policyReasonHolder[0])
                    .policyPassed(false)
                    .idempotencyKey(idempotencyKey)
                    .targetReplicas((int) currentReplicas)
                    .targetNodes((int) currentNodes)
                    .mailSent(false)
                    .build();
            decisionRepository.save(blockedDecision);
        }
    }

    @Scheduled(fixedRate = 60000) // Runs every 60 seconds to detect and terminate zombie VMs
    public void cleanupZombieNodes() {
        log.debug("[Zombie Safety Engine] Scanning for billing leak nodes...");
        List<Node> pendingNodes = nodeRepository.findByStatus(NodeStatus.PENDING);
        Instant now = Instant.now();

        for (Node node : pendingNodes) {
            long pendingDurationMinutes = Duration.between(node.getRegisteredAt(), now).toMinutes();
            if (pendingDurationMinutes >= zombieTimeoutMinutes) {
                log.error("[Zombie Safety Engine] Node {} (id={}) remained PENDING for {} minutes without registering. Terminating VM to prevent billing leak.", 
                        node.getHostname(), node.getId(), pendingDurationMinutes);
                ec2Service.terminateInstance(node.getId());
            }
        }
    }

    private PredictRequestDto buildMlRequest(List<ClusterSnapshot> snapshotHistory) {
        List<PredictRequestDto.SnapshotDto> dtoList = snapshotHistory.stream()
                .map(s -> new PredictRequestDto.SnapshotDto(
                        s.getTimestamp().toString(),
                        s.getAvgCpu(),
                        s.getAvgMemory(),
                        s.getActiveNodes(),
                        s.getActiveReplicas()
                ))
                .collect(Collectors.toList());
        
        // Reverse history so it flows from oldest to newest for the ML linear trend model
        List<PredictRequestDto.SnapshotDto> orderedHistory = new ArrayList<>();
        for (int i = dtoList.size() - 1; i >= 0; i--) {
            orderedHistory.add(dtoList.get(i));
        }

        return new PredictRequestDto(orderedHistory);
    }
}
