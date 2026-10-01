package com.scaleforge.core.repository;

import com.scaleforge.core.model.ClusterSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ClusterSnapshotRepository extends JpaRepository<ClusterSnapshot, Long> {
    List<ClusterSnapshot> findTop10ByOrderByTimestampDesc();
}
