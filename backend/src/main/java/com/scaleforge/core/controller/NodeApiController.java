package com.scaleforge.core.controller;

import com.scaleforge.core.dto.NodeRegistrationDto;
import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.enums.NodeStatus;
import com.scaleforge.core.repository.NodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/nodes")
@Slf4j
public class NodeApiController {

    @Autowired
    private NodeRepository nodeRepository;

    @PostMapping("/register")
    public ResponseEntity<Node> registerNode(@RequestBody NodeRegistrationDto dto) {
        log.info("Node registration request received: hostname={} | IP={}", dto.getHostname(), dto.getIpAddress());

        // Locate node by hostname if it exists
        Optional<Node> existingNode = nodeRepository.findAll().stream()
                .filter(n -> n.getHostname().equalsIgnoreCase(dto.getHostname()))
                .findFirst();

        Node node;
        if (existingNode.isPresent()) {
            node = existingNode.get();
            node.setIpAddress(dto.getIpAddress());
            node.setStatus(NodeStatus.ACTIVE);
            node.setLastHeartbeat(Instant.now());
            log.info("Re-activated existing node: {} (id={})", node.getHostname(), node.getId());
        } else {
            node = Node.builder()
                    .id(UUID.randomUUID().toString())
                    .hostname(dto.getHostname())
                    .ipAddress(dto.getIpAddress())
                    .status(NodeStatus.ACTIVE)
                    .registeredAt(Instant.now())
                    .lastHeartbeat(Instant.now())
                    .build();
            log.info("Registered new node: {} (id={})", node.getHostname(), node.getId());
        }

        Node saved = nodeRepository.save(node);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
