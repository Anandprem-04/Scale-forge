package com.scaleforge.core.repository;

import com.scaleforge.core.model.Node;
import com.scaleforge.core.model.enums.NodeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NodeRepository extends JpaRepository<Node, String> {
    long countByStatus(NodeStatus status);
    List<Node> findByStatus(NodeStatus status);
}
