package com.liche.wechatagent.self;

import com.liche.wechatagent.memory.MemoryTextSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 自主模块的**三期：教训清单（它自己的可靠性）**。
 *
 * <p>ExpeL 的三段式（我做了什么 / 我当时预期 / 实际发生了什么）+ 可执行的 correction；
 * 同类不新增、按"又犯了"记账（DOWNVOTE）；{@link #reviewLessons 复查}是**程序驱动**的——
 * 模型不能给自己点赞，"没再犯"只能由"上次复查之后该类别没有新教训"推出来。
 * 注入按类别触发词筛选（{@link #lessonsInPlay}），不做全局唠叨。
 */
@Service
public class SelfLessonService {

    /** 教训的五个类别（spec §9.1） */
    public static final List<String> LESSON_CATEGORIES = List.of(
            AgentLesson.CATEGORY_TIME, AgentLesson.CATEGORY_COMMITMENT, AgentLesson.CATEGORY_GUESS,
            AgentLesson.CATEGORY_FORMAT, AgentLesson.CATEGORY_TOOL);

    /** 类别 → 触发词：只在**同类场景**注入教训，不做全局唠叨（§9.1 硬规则 3） */
    private static final Map<String, List<String>> LESSON_KEYWORDS = Map.of(
            AgentLesson.CATEGORY_TIME, List.of("时间", "日程", "提醒", "几点", "明天", "后天", "下周"),
            AgentLesson.CATEGORY_COMMITMENT, List.of("答应", "承诺", "说好", "欠着", "之前说", "记得"),
            AgentLesson.CATEGORY_GUESS, List.of("是不是", "为什么", "哪年", "版本", "网址", "政策"),
            AgentLesson.CATEGORY_FORMAT, List.of("格式", "字数", "太长", "表格", "排版", "简洁"),
            AgentLesson.CATEGORY_TOOL, List.of("工具", "下载", "搜索", "发文件", "图片", "读取"));

    /** ADD 的结果：新记一条，还是"又犯了"并进了已有那条 */
    public record LessonOutcome(AgentLesson lesson, boolean recurred) {
    }

    private final AgentLessonRepository lessonRepository;
    private final SelfCoreService core;
    private final int maxLessons;
    private final double lessonMergeSimilarity;
    private final int cleanReviewsToClose;
    private final double decay;
    private final double reviewTarget;

    public SelfLessonService(AgentLessonRepository lessonRepository,
                             SelfCoreService core,
                             @Value("${memory.self-max-lessons:30}") int maxLessons,
                             @Value("${memory.self-lesson-merge-similarity:0.72}") double lessonMergeSimilarity,
                             @Value("${memory.self-lesson-clean-reviews-to-close:3}") int cleanReviewsToClose,
                             @Value("${memory.self-fsrs-decay:-0.1542}") double decay,
                             @Value("${memory.self-review-target:0.8}") double reviewTarget) {
        this.lessonRepository = lessonRepository;
        this.core = core;
        this.maxLessons = Math.max(5, maxLessons);
        this.lessonMergeSimilarity = Math.min(0.95, Math.max(0.5, lessonMergeSimilarity));
        this.cleanReviewsToClose = Math.max(1, cleanReviewsToClose);
        this.decay = decay > 0 ? -decay : (decay == 0 ? -0.1542 : decay);
        this.reviewTarget = Math.min(0.98, Math.max(0.5, reviewTarget));
    }

    /**
     * 记一条教训（ExpeL 的 ADD）。三段必须齐、correction 必须可执行；
     * 同类（同 category + 文本足够像）**不新增**，按"又犯了"处理（DOWNVOTE）。
     */
    @Transactional
    public LessonOutcome addLesson(String category, String trigger, String whatIDid, String expected,
                                   String whatHappened, String correction, String evidence) {
        core.requireEvidence(evidence);
        String normalized = SelfText.clip(category, 24).toUpperCase(Locale.ROOT);
        if (!LESSON_CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("category 只能是 " + String.join(" / ", LESSON_CATEGORIES));
        }
        if (SelfText.isBlank(whatIDid) || SelfText.isBlank(expected) || SelfText.isBlank(whatHappened)) {
            throw new IllegalArgumentException("三段必须齐：我做了什么 / 我当时预期 / 实际发生了什么（缺一段不算教训）");
        }
        if (SelfText.isBlank(correction)) {
            throw new IllegalArgumentException("必须给可执行的 correction（以后怎么做），不要写感悟");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentLesson similar = findSimilarLesson(normalized, whatHappened, correction);
        if (similar != null) {
            similar.setRecurrenceCount((similar.getRecurrenceCount() == null ? 1 : similar.getRecurrenceCount()) + 1);
            similar.setLastSeenAt(now);
            similar.setWhatHappened(SelfText.clip(whatHappened, 500));
            similar.setStability(Fsrs.stabilityOnFailure(similar.getStability()));
            similar.setDifficulty(Fsrs.difficultyOnFailure(similar.getDifficulty()));
            similar.setCleanReviews(0);
            similar.setStatus(AgentLesson.STATUS_OPEN);
            similar.setLastReviewAt(now);
            similar.setNextReviewAt(Fsrs.nextReviewAt(now, similar.getStability(), decay, reviewTarget));
            similar.setEvidence(evidence.trim());
            similar.setUpdatedAt(now);
            AgentLesson saved = lessonRepository.save(similar);
            core.appendEvent(AgentSelfEvent.KIND_LESSON, "又犯了一次（" + saved.getCategory() + "，第 "
                    + saved.getRecurrenceCount() + " 次）：" + SelfText.clipLine(saved.getCorrection(), 500),
                    evidence, saved.getCategory(), null, 3);
            return new LessonOutcome(saved, true);
        }
        long active = countActiveLessons();
        if (active >= maxLessons) {
            throw new IllegalArgumentException("教训清单已有 " + active + " 条（上限 " + maxLessons
                    + "）：先合并或关掉旧的——清单越来越长却没变化，那是在写作文");
        }
        AgentLesson lesson = new AgentLesson();
        lesson.setCategory(normalized);
        String normalizedTrigger = SelfText.clip(trigger, 24).toUpperCase(Locale.ROOT);
        lesson.setTriggerType(normalizedTrigger.isEmpty() ? AgentLesson.TRIGGER_SELF_CHECK : normalizedTrigger);
        lesson.setWhatIDid(SelfText.clip(whatIDid, 500));
        lesson.setExpectedResult(SelfText.clip(expected, 500));
        lesson.setWhatHappened(SelfText.clip(whatHappened, 500));
        lesson.setCorrection(SelfText.clip(correction, 500));
        lesson.setFirstSeenAt(now);
        lesson.setLastSeenAt(now);
        lesson.setRecurrenceCount(1);
        lesson.setCleanReviews(0);
        lesson.setStability(1.0);
        lesson.setDifficulty(5.0);
        lesson.setLastReviewAt(now);
        lesson.setNextReviewAt(Fsrs.nextReviewAt(now, 1.0, decay, reviewTarget));
        lesson.setStatus(AgentLesson.STATUS_OPEN);
        lesson.setEvidence(evidence.trim());
        lesson.setCreatedAt(now);
        lesson.setUpdatedAt(now);
        AgentLesson saved = lessonRepository.save(lesson);
        core.appendEvent(AgentSelfEvent.KIND_LESSON, "记了一条教训（" + saved.getCategory() + "）："
                + SelfText.clipLine(saved.getCorrection(), 500), evidence, saved.getCategory(), null, 3);
        return new LessonOutcome(saved, false);
    }

    /** EDIT：把教训改得更可执行（不是写感悟）。 */
    @Transactional
    public AgentLesson editLessonCorrection(Long lessonId, String correction, String evidence) {
        core.requireEvidence(evidence);
        if (SelfText.isBlank(correction)) {
            throw new IllegalArgumentException("correction 不能为空");
        }
        AgentLesson lesson = lessonRepository.findById(lessonId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条教训：" + lessonId));
        lesson.setCorrection(SelfText.clip(correction, 500));
        lesson.setUpdatedAt(LocalDateTime.now());
        lesson.setEvidence(evidence.trim());
        AgentLesson saved = lessonRepository.save(lesson);
        core.appendEvent(AgentSelfEvent.KIND_LESSON, "改了教训的做法（" + saved.getCategory() + "）："
                + SelfText.clipLine(saved.getCorrection(), 500), evidence, saved.getCategory(), null, 2);
        return saved;
    }

    /**
     * 复查（spec §9.2）：**程序驱动**——模型不能给自己点赞。
     * 到期的教训，若"上次复查之后该类别没有新教训" → UPVOTE（没再犯）：S 变长，连续若干次干净就关闭。
     * "又犯了"由 {@link #addLesson} 合并时记账，那才是 DOWNVOTE。
     */
    @Transactional
    public int reviewLessons(LocalDateTime now) {
        int reviewed = 0;
        for (AgentLesson lesson : lessonRepository.findByStatusInAndNextReviewAtBeforeOrderByNextReviewAtAsc(
                List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING), now)) {
            LocalDateTime since = lesson.getLastReviewAt() == null ? lesson.getFirstSeenAt() : lesson.getLastReviewAt();
            boolean clean = lessonRepository.findByCategoryAndLastSeenAtAfter(lesson.getCategory(), since).stream()
                    .noneMatch(other -> !Objects.equals(other.getId(), lesson.getId()));
            if (!clean) {
                continue;
            }
            int cleanReviews = (lesson.getCleanReviews() == null ? 0 : lesson.getCleanReviews()) + 1;
            lesson.setCleanReviews(cleanReviews);
            lesson.setStability(Fsrs.stabilityOnSuccess(lesson.getStability(), lesson.getDifficulty()));
            lesson.setLastReviewAt(now);
            lesson.setUpdatedAt(now);
            lesson.setNextReviewAt(Fsrs.nextReviewAt(now, lesson.getStability(), decay, reviewTarget));
            lesson.setStatus(cleanReviews >= cleanReviewsToClose
                    ? AgentLesson.STATUS_CLOSED : AgentLesson.STATUS_IMPROVING);
            lessonRepository.save(lesson);
            reviewed++;
        }
        return reviewed;
    }

    @Transactional(readOnly = true)
    public List<AgentLesson> activeLessons() {
        return lessonRepository.findByStatusInOrderByLastSeenAtDesc(
                List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING));
    }

    @Transactional(readOnly = true)
    public long countActiveLessons() {
        return lessonRepository.countByStatusIn(List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING));
    }

    /** 这条消息踩到了哪些类别的场景——用于"只在同类场景提示教训" */
    @Transactional(readOnly = true)
    public List<AgentLesson> lessonsInPlay(String userMessage, int limit) {
        if (SelfText.isBlank(userMessage)) {
            return List.of();
        }
        List<AgentLesson> picked = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : LESSON_KEYWORDS.entrySet()) {
            if (entry.getValue().stream().noneMatch(userMessage::contains)) {
                continue;
            }
            for (AgentLesson lesson : lessonRepository.findByCategoryAndStatusInOrderByLastSeenAtDesc(
                    entry.getKey(), List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING))) {
                if (picked.stream().noneMatch(existing -> existing.getId().equals(lesson.getId()))) {
                    picked.add(lesson);
                }
            }
        }
        return picked.stream()
                .sorted(Comparator.comparingInt((AgentLesson lesson) ->
                                lesson.getRecurrenceCount() == null ? 0 : lesson.getRecurrenceCount()).reversed()
                        .thenComparing(AgentLesson::getLastSeenAt, Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private AgentLesson findSimilarLesson(String category, String whatHappened, String correction) {
        String candidate = whatHappened + " " + correction;
        for (AgentLesson lesson : lessonRepository.findByCategoryAndStatusInOrderByLastSeenAtDesc(category,
                List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING))) {
            double similarity = MemoryTextSimilarity.similarity(candidate,
                    lesson.getWhatHappened() + " " + lesson.getCorrection());
            if (similarity >= lessonMergeSimilarity) {
                return lesson;
            }
        }
        return null;
    }
}
