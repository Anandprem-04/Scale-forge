package com.scaleforge.core.config;

import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.Replica;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.model.enums.ReplicaStatus;
import com.scaleforge.core.repository.NodeRepository;
import com.scaleforge.core.repository.ReplicaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import java.time.Instant;
import java.util.UUID;

@Configuration
@Slf4j
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Pre-populates the local database on startup with the baseline cluster state:
     * - 1 Manager Node (ACTIVE)
     * - 1 Target Service Replica (RUNNING)
     */
    @Bean
    public CommandLineRunner initializeDatabase(NodeRepository nodeRepository, ReplicaRepository replicaRepository) {
        return args -> {
            if (nodeRepository.count() == 0) {
                log.info("[Initialization] Database is empty. Creating baseline cluster infrastructure...");
                
                String managerNodeId = UUID.randomUUID().toString();
                Node managerNode = Node.builder()
                        .id(managerNodeId)
                        .hostname("sf-manager")
                        .ipAddress("192.168.1.100")
                        .status(NodeStatus.ACTIVE)
                        .registeredAt(Instant.now())
                        .lastHeartbeat(Instant.now())
                        .build();
                nodeRepository.save(managerNode);
                log.info("[Initialization] Default Manager Node registered: sf-manager @ 192.168.1.100");

                String replicaId = "replica-1";
                Replica defaultReplica = Replica.builder()
                        .id(replicaId)
                        .nodeId(managerNodeId)
                        .serviceName("target-service")
                        .status(ReplicaStatus.RUNNING)
                        .build();
                replicaRepository.save(defaultReplica);
                log.info("[Initialization] Default Target Replica task registered: replica-1 on sf-manager");
            }
        };
    }
}
