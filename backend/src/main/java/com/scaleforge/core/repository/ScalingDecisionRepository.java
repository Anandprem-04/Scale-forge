package com.scaleforge.core.repository;

import com.scaleforge.core.model.ScalingDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ScalingDecisionRepository extends JpaRepository<ScalingDecision, Long> {
    Optional<ScalingDecision> findFirstByOrderByTimestampDesc();
}
