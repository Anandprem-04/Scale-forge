package com.scaleforge.core.repository;

import com.scaleforge.core.model.Replica;
import com.scaleforge.core.model.enums.ReplicaStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReplicaRepository extends JpaRepository<Replica, String> {
    long countByStatus(ReplicaStatus status);
    List<Replica> findByStatus(ReplicaStatus status);
    List<Replica> findByNodeId(String nodeId);
}
