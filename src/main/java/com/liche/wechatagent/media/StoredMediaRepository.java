package com.liche.wechatagent.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface StoredMediaRepository extends JpaRepository<StoredMedia, Long> {

    List<StoredMedia> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status);

    List<StoredMedia> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status, Pageable pageable);

    List<StoredMedia> findByUserIdOrderByCreatedAtAsc(String userId);

    Optional<StoredMedia> findFirstByUserIdAndSha256AndStatus(String userId, String sha256, String status);

    Optional<StoredMedia> findByIdAndUserIdAndStatus(Long id, String userId, String status);

    List<StoredMedia> findByUserIdAndSourceMessageIdInAndStatus(String userId, List<String> sourceMessageIds, String status);
}
