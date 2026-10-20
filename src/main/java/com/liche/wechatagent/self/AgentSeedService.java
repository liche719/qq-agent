package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 「念头」的读写（2026-10-20 加）。
 *
 * <p>它自己的活动之间靠这个接头：刷视频记一条，研究时接着用；研究出来的新疑点再记一条。
 * 这里**只做存取**，怎么用是各个活动（以及注入的提示词）的事。
 */
@Service
public class AgentSeedService {

    private static final Logger log = LoggerFactory.getLogger(AgentSeedService.class);

    private final AgentSeedRepository repository;

    public AgentSeedService(AgentSeedRepository repository) {
        this.repository = repository;
    }

    /** 记一个新念头（内容重复就返回旧的，别刷屏） */
    public AgentSeed note(String content, String source) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("念头是空的");
        }
        String trimmed = text.length() > 300 ? text.substring(0, 299) + "…" : text;
        Optional<AgentSeed> same = repository.findByStatusOrderByUpdatedAtDesc(
                        AgentSeed.STATUS_OPEN, PageRequest.of(0, 50))
                .stream().filter(seed -> seed.getContent().equals(trimmed)).findFirst();
        if (same.isPresent()) {
            return same.get();
        }
        AgentSeed seed = new AgentSeed();
        seed.setContent(trimmed);
        seed.setSource(source == null ? "" : source.trim());
        seed.setStatus(AgentSeed.STATUS_OPEN);
        seed.setCreatedAt(LocalDateTime.now());
        seed.setUpdatedAt(LocalDateTime.now());
        AgentSeed saved = repository.save(seed);
        log.info("记下一个念头 #{}（{}）：{}", saved.getId(), saved.getSource(),
                saved.getContent().length() > 40 ? saved.getContent().substring(0, 40) + "…" : saved.getContent());
        return saved;
    }

    /** 关掉一个念头：搞清了 / 不做了 / 升格成方向了 */
    public AgentSeed close(Long id, String status, Long questId) {
        AgentSeed seed = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("没有这个念头：#" + id));
        String normalized = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of(AgentSeed.STATUS_DONE, AgentSeed.STATUS_DROPPED, AgentSeed.STATUS_QUEST).contains(normalized)) {
            throw new IllegalArgumentException("状态只能是 DONE / DROPPED / QUEST");
        }
        seed.setStatus(normalized);
        if (questId != null && questId > 0) {
            seed.setQuestId(questId);
        }
        seed.setUpdatedAt(LocalDateTime.now());
        AgentSeed saved = repository.save(seed);
        log.info("念头 #{} 收口：{}", saved.getId(), normalized);
        return saved;
    }

    /** 还没消化的念头（注入提示词用，老的优先——别让它永远只盯着最新的） */
    public List<AgentSeed> openSeeds(int limit) {
        return repository.findByStatusOrderByUpdatedAtDesc(AgentSeed.STATUS_OPEN,
                PageRequest.of(0, Math.max(1, Math.min(20, limit))));
    }

    public long openCount() {
        return repository.countByStatus(AgentSeed.STATUS_OPEN);
    }

    public Optional<AgentSeed> find(Long id) {
        return repository.findById(id);
    }
}
