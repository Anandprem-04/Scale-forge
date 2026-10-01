package com.scaleforge.core.service;

import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.ScalingDecision;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.model.enums.ReplicaStatus;
import com.scaleforge.core.model.enums.ScalingAction;
import com.scaleforge.core.repository.NodeRepository;
import com.scaleforge.core.repository.ReplicaRepository;
import com.scaleforge.core.repository.ScalingDecisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DecisionEngine {

    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private ReplicaRepository replicaRepository;
    @Autowired
    private ScalingDecisionRepository decisionRepository;

    @Autowired
    private AwsEc2Service ec2Service;
    @Autowired
    private DockerSwarmService swarmService;
    @Autowired
    private NginxProxyService nginxService;
    @Autowired
    private EmailService emailService;

    @Value("${scaleforge.target.max-replicas-per-node:4}")
    private int maxReplicasPerNode;

    @Transactional
    public void executeScaleUp(String idempotencyKey, String reason) {
        log.info("[Decision Engine] Evaluating SCALE_UP execution path...");

        List<Node> activeNodes = nodeRepository.findByStatus(NodeStatus.ACTIVE);
        long activeNodeCount = activeNodes.isEmpty() ? 1 : activeNodes.size();
        
        long activeReplicaCount = replicaRepository.findByStatus(ReplicaStatus.RUNNING).size();
        long maxClusterCapacity = activeNodeCount * maxReplicasPerNode;

        int targetReplicas = (int) (activeReplicaCount + 1);
        int targetNodes = (int) activeNodeCount;

        if (activeReplicaCount < maxClusterCapacity) {
            // Room exists on current active nodes
            log.info("[Decision Engine] Capacity check passed: Replicas can fit on active nodes. Scaling replicas only.");
            swarmService.scaleReplicas(targetReplicas);
        } else {
            // Nodes are saturated, launch new Node first
            log.info("[Decision Engine] Capacity saturated ({} replicas on {} nodes). Scaling infrastructure.", activeReplicaCount, activeNodeCount);
            targetNodes = (int) (activeNodeCount + 1);
            
            // Launch worker node via AWS SDK
            String newIp = ec2Service.launchInstance(idempotencyKey);
            
            // Scale Swarm service
            swarmService.scaleReplicas(targetReplicas);
        }

        // Update Nginx load balancer upstream hosts list
        List<Node> updatedActiveNodes = nodeRepository.findByStatus(NodeStatus.ACTIVE);
        List<String> nodeIps = updatedActiveNodes.stream().map(Node::getIpAddress).collect(Collectors.toList());
        nginxService.updateUpstreamHosts(nodeIps);

        // Record Decision log
        ScalingDecision decision = ScalingDecision.builder()
                .timestamp(Instant.now())
                .action(ScalingAction.SCALE_UP)
                .decisionReason(reason)
                .policyPassed(true)
                .idempotencyKey(idempotencyKey)
                .targetReplicas(targetReplicas)
                .targetNodes(targetNodes)
                .mailSent(false)
                .build();
        
        ScalingDecision savedDecision = decisionRepository.save(decision);
        
        // Notify administrator
        boolean mailSuccess = emailService.sendScalingAlert(savedDecision);
        if (mailSuccess) {
            savedDecision.setMailSent(true);
            decisionRepository.save(savedDecision);
        }
    }

    @Transactional
    public void executeScaleDown(String idempotencyKey, String reason) {
        log.info("[Decision Engine] Evaluating SCALE_DOWN execution path...");

        List<Node> activeNodes = nodeRepository.findByStatus(NodeStatus.ACTIVE);
        long activeNodeCount = activeNodes.isEmpty() ? 1 : activeNodes.size();

        long activeReplicaCount = replicaRepository.findByStatus(ReplicaStatus.RUNNING).size();
        
        if (activeReplicaCount <= 1) {
            log.warn("[Decision Engine] Scale Down rejected: Already at baseline capacity of 1 replica.");
            return;
        }

        int targetReplicas = (int) (activeReplicaCount - 1);
        int targetNodes = (int) activeNodeCount;

        // Scale down container replicas first (Replica-First)
        swarmService.scaleReplicas(targetReplicas);

        // Check if we can safely release a node (Node-Second)
        // If the remaining replicas can fit comfortably on activeNodes - 1
        if (activeNodeCount > 1 && targetReplicas <= (activeNodeCount - 1) * maxReplicasPerNode) {
            log.info("[Decision Engine] Node resources are under-utilized. Selecting worker node to terminate.");
            
            // Select one worker node to terminate (avoid manager node / node index 1)
            Node nodeToTerminate = activeNodes.stream()
                    .filter(n -> !n.getHostname().equalsIgnoreCase("sf-manager") && !n.getHostname().equalsIgnoreCase("sf-worker-1"))
                    .findFirst()
                    .orElse(activeNodes.get(activeNodes.size() - 1)); // Fallback to last node if needed

            log.info("[Decision Engine] Transitioning node {} to DRAINING status...", nodeToTerminate.getHostname());
            nodeToTerminate.setStatus(NodeStatus.DRAINING);
            nodeRepository.save(nodeToTerminate);

            // Execute EC2 termination
            ec2Service.terminateInstance(nodeToTerminate.getId());
            targetNodes = (int) (activeNodeCount - 1);
        }

        // Update Nginx proxy upstream hosts
        List<Node> updatedActiveNodes = nodeRepository.findByStatus(NodeStatus.ACTIVE);
        List<String> nodeIps = updatedActiveNodes.stream().map(Node::getIpAddress).collect(Collectors.toList());
        nginxService.updateUpstreamHosts(nodeIps);

        // Record audit decision
        ScalingDecision decision = ScalingDecision.builder()
                .timestamp(Instant.now())
                .action(ScalingAction.SCALE_DOWN)
                .decisionReason(reason)
                .policyPassed(true)
                .idempotencyKey(idempotencyKey)
                .targetReplicas(targetReplicas)
                .targetNodes(targetNodes)
                .mailSent(false)
                .build();
        
        ScalingDecision savedDecision = decisionRepository.save(decision);
        
        // Notify admin
        boolean mailSuccess = emailService.sendScalingAlert(savedDecision);
        if (mailSuccess) {
            savedDecision.setMailSent(true);
            decisionRepository.save(savedDecision);
        }
    }
}
