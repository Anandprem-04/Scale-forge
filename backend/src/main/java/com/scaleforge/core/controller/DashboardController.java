package com.scaleforge.core.controller;

import com.scaleforge.core.model.ClusterSnapshot;
import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.Replica;
import com.scaleforge.core.model.ScalingDecision;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.model.enums.ReplicaStatus;
import com.scaleforge.core.repository.ClusterSnapshotRepository;
import com.scaleforge.core.repository.NodeRepository;
import com.scaleforge.core.repository.ReplicaRepository;
import com.scaleforge.core.repository.ScalingDecisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@Slf4j
public class DashboardController {

    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private ReplicaRepository replicaRepository;
    @Autowired
    private ClusterSnapshotRepository snapshotRepository;
    @Autowired
    private ScalingDecisionRepository decisionRepository;

    @GetMapping("/")
    public String indexRedirect() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String showDashboard() {
        return "dashboard"; // Renders src/main/resources/templates/dashboard.html
    }

    @GetMapping("/dashboard/api/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDashboardStatus() {
        Map<String, Object> data = new HashMap<>();

        List<Node> nodes = nodeRepository.findAll();
        List<Replica> replicas = replicaRepository.findByStatus(ReplicaStatus.RUNNING);
        List<ClusterSnapshot> snapshots = snapshotRepository.findAll();
        List<ScalingDecision> decisions = decisionRepository.findAll();

        long activeNodes = nodes.stream().filter(n -> n.getStatus() == NodeStatus.ACTIVE).count();
        if (activeNodes == 0) {
            activeNodes = 1;
        }

        data.put("activeNodes", activeNodes);
        data.put("activeReplicas", replicas.size());
        data.put("nodesList", nodes);
        data.put("replicasCount", replicas.size());
        
        // Return latest 30 snapshots for charts
        List<ClusterSnapshot> chartSnapshots = snapshots.stream()
                .skip(Math.max(0, snapshots.size() - 30))
                .toList();
        data.put("snapshots", chartSnapshots);

        // Return full decisions list for client-side search and 24-hour filtering
        data.put("decisions", decisions);

        // Build simulated round-robin load distribution list for active container replicas
        List<Map<String, Object>> replicaLoads = new java.util.ArrayList<>();
        double currentCpu = chartSnapshots.isEmpty() ? 0.0 : chartSnapshots.get(chartSnapshots.size() - 1).getAvgCpu();
        double distributedLoad = currentCpu / Math.max(1, replicas.size());
        
        for (Replica r : replicas) {
            Map<String, Object> rMap = new HashMap<>();
            rMap.put("id", r.getId());
            rMap.put("serviceName", r.getServiceName());
            rMap.put("status", r.getStatus().toString());
            rMap.put("cpuLoad", distributedLoad);
            
            // Resolve host node hostname
            String hostNode = "sf-manager";
            if (r.getNodeId() != null) {
                java.util.Optional<Node> node = nodeRepository.findById(r.getNodeId());
                if (node.isPresent()) {
                    hostNode = node.get().getHostname();
                }
            }
            rMap.put("node", hostNode);
            replicaLoads.add(rMap);
        }
        data.put("replicaLoads", replicaLoads);

        return ResponseEntity.ok(data);
    }

    @PostMapping("/dashboard/api/simulate-spike")
    @ResponseBody
    public ResponseEntity<Map<String, String>> simulateSpike(@RequestParam(defaultValue = "15") int seconds) {
        log.info("[Dashboard Controller] Proxying simulated load spike to target-service for {} seconds...", seconds);
        Map<String, String> responseMap = new HashMap<>();
        try {
            RestTemplate restTemplate = new RestTemplate();
            String targetServiceUrl = "http://localhost:8081/load?seconds=" + seconds;
            String targetResponse = restTemplate.postForObject(targetServiceUrl, null, String.class);
            responseMap.put("status", "SUCCESS");
            responseMap.put("message", "Target response: " + targetResponse);
            return ResponseEntity.ok(responseMap);
        } catch (Exception e) {
            log.error("Failed to proxy load spike request: {}", e.getMessage());
            responseMap.put("status", "FAILED");
            responseMap.put("error", e.getMessage());
            return ResponseEntity.status(500).body(responseMap);
        }
    }
}
