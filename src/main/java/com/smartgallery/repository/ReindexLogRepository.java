package com.smartgallery.repository;

import com.smartgallery.entity.ReindexLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReindexLogRepository extends JpaRepository<ReindexLogEntity, Long> {

    List<ReindexLogEntity> findByImageId(Long imageId);

    List<ReindexLogEntity> findByStatus(String status);

    @Query("SELECT r FROM ReindexLogEntity r ORDER BY r.processedAt DESC")
    List<ReindexLogEntity> findRecentLogs(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COUNT(r) FROM ReindexLogEntity r WHERE r.status = 'ERROR'")
    long countErrors();
}
