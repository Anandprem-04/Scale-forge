package com.scaleforge.core.service;

import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.repository.NodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AwsEc2Service {

    @Autowired
    private NodeRepository nodeRepository;

    private final ScheduledExecutorService bootstrapScheduler = Executors.newScheduledThreadPool(2);

    // Track active clientTokens to enforce launch idempotence
    private static final Map<String, String> tokenToNodeIpMap = new ConcurrentHashMap<>();

    public String launchInstance(String clientToken) {
        log.info("[AWS SDK Simulator] LaunchInstances requested. clientToken={}", clientToken);

        // Idempotency check: If this token was already processed, return the mapped IP
        if (tokenToNodeIpMap.containsKey(clientToken)) {
            String existingIp = tokenToNodeIpMap.get(clientToken);
            log.info("[AWS SDK Idempotency] Recognized clientToken {}. Returning existing instance IP: {}", clientToken, existingIp);
            return existingIp;
        }

        String nodeId = UUID.randomUUID().toString();
        int nodeIndex = (int) (nodeRepository.count() + 1);
        String hostname = "sf-worker-" + nodeIndex;
        String ipAddress = "192.168.1." + (100 + nodeIndex);

        tokenToNodeIpMap.put(clientToken, ipAddress);

        log.info("[AWS SDK Simulator] Provisioning EC2 VM Instance... ID={} | Hostname={} | IP={}", nodeId, hostname, ipAddress);

        // Create the node in PENDING state to test bootstrap & zombie safety checks
        Node pendingNode = Node.builder()
                .id(nodeId)
                .hostname(hostname)
                .ipAddress(ipAddress)
                .status(NodeStatus.PENDING)
                .registeredAt(Instant.now())
                .lastHeartbeat(Instant.now())
                .build();
        nodeRepository.save(pendingNode);

        // Simulate a 15-second bootstrap delay, then trigger registration API call
        bootstrapScheduler.schedule(() -> {
            try {
                log.info("[Bootstrap Simulator] Node {} finished OS bootstrap. Triggering /api/nodes/register registration check-in...", hostname);
                Optional<Node> nodeOpt = nodeRepository.findById(nodeId);
                if (nodeOpt.isPresent()) {
                    Node node = nodeOpt.get();
                    if (node.getStatus() == NodeStatus.PENDING) {
                        node.setStatus(NodeStatus.ACTIVE);
                        node.setLastHeartbeat(Instant.now());
                        nodeRepository.save(node);
                        log.info("[Bootstrap Simulator] Node {} status transitioned from PENDING to ACTIVE.", hostname);
                    }
                }
            } catch (Exception e) {
                log.error("Error during simulated node registration: {}", e.getMessage());
            }
        }, 15, TimeUnit.SECONDS);

        return ipAddress;
    }

    public void terminateInstance(String nodeId) {
        Optional<Node> nodeOpt = nodeRepository.findById(nodeId);
        if (nodeOpt.isPresent()) {
            Node node = nodeOpt.get();
            log.info("[AWS SDK Simulator] Terminating EC2 VM Instance: {} (id={})", node.getHostname(), node.getId());
            node.setStatus(NodeStatus.TERMINATED);
            nodeRepository.save(node);
        }
    }
}
