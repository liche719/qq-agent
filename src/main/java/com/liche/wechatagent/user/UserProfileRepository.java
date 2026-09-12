package com.liche.wechatagent.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

    List<UserProfile> findByProactiveCareEnabledTrueAndNextCareAtLessThanEqual(LocalDateTime now);

    /**
     * 只更新投递相关的三列。
     *
     * <p>**不要**用「读整行 → 改两个字段 → save」：全项目没有 {@code @DynamicUpdate}，save 是
     * merge + 全列 UPDATE，会把调用方手里那份旧快照（persona / memory_enabled / coach_mode /
     * next_care_at…）一起写回去。`touchDelivery` 挂在每条入站消息上，用户刚发「陪练 面试」写下的
     * `coach_mode` 会被同一瞬间的这条消息抹掉，陪练模式静默失效。
     */
    @Modifying
    @Transactional
    @Query("update UserProfile p set p.lastBotId = :botId, p.lastChannel = :channel, "
            + "p.lastSeenAt = :now, p.updatedAt = :now where p.userId = :userId")
    int touchDelivery(@Param("userId") String userId, @Param("botId") String botId,
                      @Param("channel") String channel, @Param("now") LocalDateTime now);
}
