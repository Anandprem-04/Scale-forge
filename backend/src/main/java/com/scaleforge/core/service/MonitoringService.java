package com.scaleforge.core.service;

import com.scaleforge.core.dto.MetricReportDto;
import com.scaleforge.core.model.ClusterSnapshot;
import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.Replica;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.model.enums.ReplicaStatus;
import com.scaleforge.core.repository.ClusterSnapshotRepository;
import com.scaleforge.core.repository.NodeRepository;
import com.scaleforge.core.repository.ReplicaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MonitoringService {

    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private ReplicaRepository replicaRepository;
    @Autowired
    private ClusterSnapshotRepository snapshotRepository;

    // Thread-safe map to store the latest raw metric reports pushed by container replicas
    private final Map<String, MetricReportDto> rawMetricsBuffer = new ConcurrentHashMap<>();

    public void processMetricReport(MetricReportDto dto) {
        long activeReplicas = replicaRepository.countByStatus(ReplicaStatus.RUNNING);
        if (activeReplicas == 0) {
            activeReplicas = 1;
        }

        // Simulate traffic distribution: in a real cluster, Nginx distributes requests
        // so each replica handles 1 / activeReplicas of the total load.
        double distributedCpu = dto.getCpuPercent() / activeReplicas;
        
        MetricReportDto adjustedDto = new MetricReportDto(
                dto.getContainerId(),
                distributedCpu,
                dto.getMemoryUsageMb()
        );

        log.debug("Metric push from container {}: Raw CPU={}% | Distributed CPU={}% (across {} replicas)", 
                dto.getContainerId(), dto.getCpuPercent(), String.format("%.2f", distributedCpu), activeReplicas);

        rawMetricsBuffer.put(dto.getContainerId(), adjustedDto);

        // Self-Healing Auto-Registration:
        // If the container is not registered in our DB, register it as running on a default manager node
        if (!replicaRepository.existsById(dto.getContainerId())) {
            log.info("Auto-registering new target replica task: {}", dto.getContainerId());
            Replica newReplica = Replica.builder()
                    .id(dto.getContainerId())
                    .serviceName("target-service")
                    .status(ReplicaStatus.RUNNING)
                    .build();
            replicaRepository.save(newReplica);
        }
    }

    @Scheduled(fixedRate = 60000) // Run every 60 seconds
    public void aggregateSnapshots() {
        log.info("Aggregating raw metrics buffer ({} reports)...", rawMetricsBuffer.size());
        
        long activeNodeCount = nodeRepository.countByStatus(NodeStatus.ACTIVE);
        // Default to at least 1 node for local sandbox runs if DB is empty
        if (activeNodeCount == 0) {
            activeNodeCount = 1;
        }

        long activeReplicas = replicaRepository.countByStatus(ReplicaStatus.RUNNING);
        if (activeReplicas == 0) {
            activeReplicas = 1;
        }

        double totalCpu = 0.0;
        double totalMem = 0.0;
        int reportsCount = rawMetricsBuffer.size();

        if (reportsCount > 0) {
            for (MetricReportDto report : rawMetricsBuffer.values()) {
                totalCpu += report.getCpuPercent();
                totalMem += report.getMemoryUsageMb();
            }
            double avgCpu = totalCpu / reportsCount;
            double avgMem = totalMem / reportsCount;

            ClusterSnapshot snapshot = ClusterSnapshot.builder()
                    .timestamp(Instant.now())
                    .activeNodes((int) activeNodeCount)
                    .activeReplicas((int) activeReplicas)
                    .avgCpu(avgCpu)
                    .avgMemory(avgMem)
                    .build();
            
            snapshotRepository.save(snapshot);
            log.info("Saved ClusterSnapshot: Avg CPU={}% | Replicas={} | Nodes={}", 
                    String.format("%.2f", avgCpu), activeReplicas, activeNodeCount);
        } else {
            // If no replicas pushed metrics, write a baseline snapshot
            ClusterSnapshot snapshot = ClusterSnapshot.builder()
                    .timestamp(Instant.now())
                    .activeNodes((int) activeNodeCount)
                    .activeReplicas((int) activeReplicas)
                    .avgCpu(0.0)
                    .avgMemory(0.0)
                    .build();
            snapshotRepository.save(snapshot);
            log.info("Saved empty ClusterSnapshot: No active metric pushes received.");
        }

        // Clear the buffer to ensure stale metric reports from terminated containers do not skew results
        rawMetricsBuffer.clear();
    }
    
    public Map<String, MetricReportDto> getLatestReports() {
        return rawMetricsBuffer;
    }
}
