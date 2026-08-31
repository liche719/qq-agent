package com.liche.wechatagent.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

    List<UserProfile> findByProactiveCareEnabledTrueAndNextCareAtLessThanEqual(LocalDateTime now);
}
