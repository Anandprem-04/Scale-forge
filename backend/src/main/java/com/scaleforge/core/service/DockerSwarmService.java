package com.scaleforge.core.service;

import com.scaleforge.core.model.Replica;
import com.scaleforge.core.model.enums.ReplicaStatus;
import com.scaleforge.core.repository.ReplicaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DockerSwarmService {

    @Autowired
    private ReplicaRepository replicaRepository;

    public void scaleReplicas(int targetCount) {
        log.info("[Docker Swarm Simulator] Executing: docker service scale target_web-service={}", targetCount);

        List<Replica> activeReplicas = replicaRepository.findByStatus(ReplicaStatus.RUNNING);
        int currentCount = activeReplicas.size();

        if (targetCount > currentCount) {
            int toCreate = targetCount - currentCount;
            log.info("[Docker Swarm Simulator] Spawning {} new replica containers...", toCreate);
            for (int i = 0; i < toCreate; i++) {
                Replica replica = Replica.builder()
                        .id(UUID.randomUUID().toString())
                        .serviceName("target-service")
                        .status(ReplicaStatus.RUNNING)
                        .build();
                replicaRepository.save(replica);
            }
        } else if (targetCount < currentCount) {
            int toRemove = currentCount - targetCount;
            log.info("[Docker Swarm Simulator] Terminating {} container tasks...", toRemove);
            for (int i = 0; i < toRemove; i++) {
                Replica replica = activeReplicas.get(i);
                replica.setStatus(ReplicaStatus.TERMINATED);
                replicaRepository.save(replica);
            }
        } else {
            log.info("[Docker Swarm Simulator] Already at target replica count ({}). No actions needed.", targetCount);
        }
    }
}
