package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryArchiveRepository extends JpaRepository<MemoryArchive, Long> {

    List<MemoryArchive> findByUserIdOrderByCreatedAtDesc(String userId);
}
