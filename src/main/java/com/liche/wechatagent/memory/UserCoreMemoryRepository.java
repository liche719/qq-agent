package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserCoreMemoryRepository extends JpaRepository<UserCoreMemory, Long> {

    List<UserCoreMemory> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<UserCoreMemory> findByUserIdOrderByCreatedAtAsc(String userId);

    long countByUserId(String userId);
}
