package com.smartgallery.repository;

import com.smartgallery.entity.ImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImageRepository extends JpaRepository<ImageEntity, Long> {

    Optional<ImageEntity> findByFilePath(String filePath);

    boolean existsByFilePath(String filePath);

    Optional<ImageEntity> findByFileHash(String fileHash);

    @Query("SELECT i FROM ImageEntity i WHERE i.embedding IS NOT NULL")
    List<ImageEntity> findAllWithEmbeddings();

    @Query("SELECT i.id, i.embedding FROM ImageEntity i WHERE i.embedding IS NOT NULL")
    List<Object[]> findAllEmbeddingData();

    @Query("SELECT COUNT(i) FROM ImageEntity i WHERE i.embedding IS NOT NULL")
    long countIndexed();

    List<ImageEntity> findByLastModifiedAfter(LocalDateTime since);

    @Query("SELECT i FROM ImageEntity i WHERE i.filePath LIKE :folder")
    List<ImageEntity> findByFolderPath(@Param("folder") String folder);

    long countByFilePathStartingWith(String prefix);

    @Query("SELECT i FROM ImageEntity i WHERE i.extraJson LIKE :tag")
    List<ImageEntity> findByTag(@Param("tag") String tag);

    @Query("SELECT i FROM ImageEntity i WHERE LOWER(CAST(i.extraJson AS string)) LIKE :tagPattern")
    List<ImageEntity> findByTagCaseInsensitive(@Param("tagPattern") String tagPattern);

    @Query("SELECT i FROM ImageEntity i WHERE LOWER(CAST(i.extractedText AS string)) LIKE :textPattern")
    List<ImageEntity> findByExtractedTextCaseInsensitive(@Param("textPattern") String textPattern);

    /**
     * Raw LIKE on extractedText — used for non-ASCII (Tamil, Arabic, Chinese etc.)
     * where LOWER() is a no-op.
     */
    @Query("SELECT i FROM ImageEntity i WHERE CAST(i.extractedText AS string) LIKE :textPattern")
    List<ImageEntity> findByExtractedTextRaw(@Param("textPattern") String textPattern);

    @Query("SELECT i FROM ImageEntity i WHERE LOWER(i.filePath) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<ImageEntity> findByFileNameContaining(@Param("name") String name);

    void deleteByFilePath(String filePath);

    List<ImageEntity> findByIsLovedTrue();

    long countByIsLovedTrue();

    // ── Memories / "On This Day" ─────────────────────────────────────────────

    /**
     * Images whose lastModified falls in ANY prior year on the same month+day.
     * Used for the "On This Day" / Memories feature.
     */
    @Query("SELECT i FROM ImageEntity i " +
           "WHERE FUNCTION('MONTH', i.lastModified) = :month " +
           "AND FUNCTION('DAY', i.lastModified) = :day " +
           "AND FUNCTION('YEAR', i.lastModified) < :currentYear " +
           "ORDER BY i.lastModified DESC")
    List<ImageEntity> findOnThisDay(@Param("month") int month,
                                    @Param("day") int day,
                                    @Param("currentYear") int currentYear);

    /**
     * Images whose lastModified falls within a specific date range (e.g. today).
     */
    List<ImageEntity> findByLastModifiedBetween(LocalDateTime from, LocalDateTime to);
}
