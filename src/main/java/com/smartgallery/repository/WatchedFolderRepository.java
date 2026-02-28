package com.smartgallery.repository;

import com.smartgallery.entity.WatchedFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchedFolderRepository extends JpaRepository<WatchedFolderEntity, Long> {

    List<WatchedFolderEntity> findByActiveTrue();

    Optional<WatchedFolderEntity> findByFolderPath(String folderPath);

    boolean existsByFolderPath(String folderPath);
}
