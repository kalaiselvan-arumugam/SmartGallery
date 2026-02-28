package com.smartgallery.repository;

import com.smartgallery.entity.SettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettingRepository extends JpaRepository<SettingEntity, String> {

    Optional<SettingEntity> findByKey(String key);

    boolean existsByKey(String key);

    void deleteByKey(String key);
}
