package com.liche.wechatagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.config.EmbeddingClient;
import com.liche.wechatagent.config.LlmScenario;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 记忆事实层（v2，2026-09-18）——**一条记忆 = 一个事实**，靠语义（pgvector 向量）而不是字面去找"这是不是同一件事"。
 *
 * <p>为什么要有这一层：v1 的记忆是"一段话"，改一个教室要么整条换掉、要么新增一条互相矛盾的话；
 * 而"课表/教室/时间"这类**会变**的信息恰恰最需要被记住（用户 2026-09-18 明确要求）。
 * 拆成一条一事实 + 按属性槽改写之后：改教室只碰教室那一条，其余字段不可能被误伤，
 * 旧值也留痕（SUPERSEDED，不删行），所以"记错了"是可逆的。
 *
 * <p>一次 {@link #apply} 的流程（每个候选独立、互不影响）：
 * <ol>
 *   <li>把新事实的 content 取向量，在**该用户当前有效的事实**里按余弦相似度召回 top-k（不做字面匹配）；</li>
 *   <li>把新事实 + 召回结果交给模型做四分类：SAME（同值，不新增）/ SUPERSEDES（同属性，新值取代旧值）/
 *       SUPPLEMENT（补充别的属性）/ UNRELATED；</li>
 *   <li>SAME → 只刷新旧行的 updated_at/confidence；SUPERSEDES → 旧行置 SUPERSEDED + superseded_by；
 *       其余 → 新增。**唯一例外**：旧行是 DOC 基线（从图片/文件看出来的）、新值来自用户本人时，
 *       基线行保持 ACTIVE——这样"图上写的是 303、你后来补充是 305"两句话都还在，能如实回答；</li>
 *   <li>新行算向量写回，并写一条 {@code memory_change_log}（layer=FACT）。</li>
 * </ol>
 *
 * <p><b>没有向量模型也能用</b>：{@link EmbeddingClient#isEnabled()} 为 false 时降级成"只新增不合并"
 * （不召回、不判矛盾），事实照样入库。
 */
@Service
public class MemoryFactService {

    private static final Logger log = LoggerFactory.getLogger(MemoryFactService.class);
    private static final String CHANGE_LAYER = "FACT";
    /** 补齐向量时一次最多处理多少条（用户后填 api-key 时靠它自愈，见 reindexMissing） */
    private static final int REINDEX_BATCH = 20;

    private final MemoryFactRepository factRepository;
    private final MemoryFactVectorStore vectorStore;
    private final MemoryChangeLogRepository changeLogRepository;
    private final EmbeddingClient embeddingClient;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final int recallLimit;
    private final double recallMinScore;

    public MemoryFactService(MemoryFactRepository factRepository,
                             MemoryFactVectorStore vectorStore,
                             MemoryChangeLogRepository changeLogRepository,
                             EmbeddingClient embeddingClient,
                             ChatModel chatModel,
                             ObjectMapper objectMapper,
                             @Value("${memory.fact-recall-limit:6}") int recallLimit,
                             @Value("${memory.fact-recall-min-score:0.5}") double recallMinScore) {
        this.factRepository = factRepository;
        this.vectorStore = vectorStore;
        this.changeLogRepository = changeLogRepository;
        this.embeddingClient = embeddingClient;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.recallLimit = Math.max(1, Math.min(20, recallLimit));
        this.recallMinScore = Math.max(0d, Math.min(0.99d, recallMinScore));
    }

    /** 模型对"新事实 vs 某条已有事实"的关系判定 */
    private record Relation(Long id, String relation, String reason) {
    }

    /**
     * 把一批候选写进事实层。**每条独立 try/catch**：一条炸了不影响其余（记忆入库不该因为一条脏数据整批失败）。
     *
     * @return 实际新增的事实条数
     */
    public int apply(String userId, List<MemoryFactCandidate> candidates) {
        if (userId == null || userId.isBlank() || candidates == null || candidates.isEmpty()) {
            return 0;
        }
        if (embeddingClient.isEnabled()) {
            // 自愈：没向量的事实永远召回不到（用户是后来才填 api-key 的），每次顺手补一小批
            reindexMissing(userId, REINDEX_BATCH);
        }
        int written = 0;
        Set<String> seenSlots = new HashSet<>();
        for (MemoryFactCandidate raw : candidates) {
            if (raw == null) {
                continue;
            }
            try {
                MemoryFactCandidate candidate = raw.withComposedContent();
                if (!candidate.usable()) {
                    continue;
                }
                // 同一次提取里同一个属性槽只留最后一条（模型偶尔会重复输出）
                if (!seenSlots.add(candidate.slotKey())) {
                    continue;
                }
                // 主调用直接给了合并结论（提示词里带了已有事实卡片清单）→ 不再召回、不再单独判定，
                // 省掉"每条事实一次小调用"。没给结论才走召回 + 模型判关系那条老路。
                boolean ok = candidate.hasDecision()
                        ? writeWithDecision(userId, candidate)
                        : writeOne(userId, candidate);
                if (ok) {
                    written++;
                }
            } catch (Exception e) {
                log.warn("写入事实失败 user={} subject={}: {}", userId, raw.subject(), e.getMessage());
            }
        }
        return written;
    }

    /**
     * 主调用已经判过关系：按它的结论写回（**不再调模型**）。
     *
     * <p>定位"合并目标"用的是 {@code targetSubject + predicate} 精确查槽——**不用向量、也不用 id**，
     * 因为提示词里给模型的卡片清单就是这些 subject，模型只要照抄那个名字即可。
     * 找不到目标时保守地当新建（宁可多一条，也不猜着改）。
     */
    private boolean writeWithDecision(String userId, MemoryFactCandidate candidate) {
        String relation = candidate.relation();
        String target = candidate.targetSubject();
        if (MemoryFact.RELATION_NEW.equals(relation) || target == null || target.isBlank()) {
            return persist(userId, candidate, List.of(), "新增");
        }
        MemoryFact existing = factRepository
                .findFirstByUserIdAndSubjectIgnoreCaseAndPredicateIgnoreCaseAndStatus(
                        userId, target, candidate.predicate(), MemoryFact.STATUS_ACTIVE)
                .orElse(null);
        if (existing == null) {
            // 模型说要合并、但按它给的名字+属性**精确查不到**目标（多半是它没照抄卡片的写法）。
            // **不做字面/相似度之类的猜测**（用户 2026-09-18 明确否掉：那是硬编码阈值）——
            // 直接退回"向量召回 + 让模型判关系"那条路，由模型自己决定是同一件事还是新的一件事。
            log.info("主调用给的合并目标查不到（subject={} predicate={}），退回召回+判定 user={}",
                    target, candidate.predicate(), userId);
            MemoryFactCandidate plain = new MemoryFactCandidate(candidate.subject(), candidate.predicate(),
                    candidate.object(), candidate.content(), candidate.source(), candidate.confidence(),
                    candidate.docMediaId(), candidate.keywords(), candidate.sourceMessageIds());
            return writeOne(userId, plain);
        }
        if (MemoryFact.RELATION_SAME.equals(relation)) {
            touch(userId, existing, candidate, "主调用判定：同一个值");
            return false;
        }
        if (MemoryFact.RELATION_SUPERSEDES.equals(relation)) {
            return persist(userId, candidate, List.of(existing), "主调用判定：同一属性被改写");
        }
        // SUPPLEMENT：补充**另一个属性**，旧信息仍然成立 → 沿用目标那件事的 subject（拼法也照它的来，
        // 免得"第一周·周二晚·数学课"和"周二晚数学课"各成一张卡），只新增这一行
        String subject = existing.getSubject();
        try {
            subject = factRepository
                    .findFirstByUserIdAndSubjectIgnoreCaseAndStatus(userId, target, MemoryFact.STATUS_ACTIVE)
                    .map(MemoryFact::getSubject)
                    .orElse(existing.getSubject());
        } catch (Exception e) {
            log.warn("沿用卡片 subject 失败（按模型给的原样写）: {}", e.getMessage());
        }
        MemoryFactCandidate merged = new MemoryFactCandidate(subject, candidate.predicate(),
                candidate.object(), candidate.content(), candidate.source(), candidate.confidence(),
                candidate.docMediaId(), candidate.keywords(), candidate.sourceMessageIds(), null, null);
        return persist(userId, merged, List.of(), "补充同一件事的另一个属性");
    }

    /** 返回 true = 新增了一条（SAME 的刷新不算） */
    private boolean writeOne(String userId, MemoryFactCandidate candidate) {
        List<MemoryFact> recalled = recall(userId, candidate.content());
        List<Relation> relations = classify(candidate, recalled);

        Optional<Relation> same = relations.stream().filter(r -> "SAME".equals(r.relation())).findFirst();
        if (same.isPresent()) {
            MemoryFact existing = findOwned(userId, same.get().id());
            if (existing != null) {
                touch(userId, existing, candidate, same.get().reason());
                return false;
            }
        }

        // subject 是模型每次现编的字符串（"第一周·周二晚·数学课" / "周二晚数学课" 都可能出现）。
        // **只要落到了某条已有事实上，就沿用那条的 subject**——否则同一个东西会以不同说法各成一张卡，
        // 越积越多，注入/面板也会看起来像两件事（用户 2026-09-18 提的问题）。
        // predicate 只在"同一个属性被改写"（SUPERSEDES）时沿用：补充（SUPPLEMENT）本来就是**另一个属性**，
        // 沿用会把"教师=王老师"错写成"教室=王老师"。
        MemoryFact anchor = anchorFact(userId, relations);
        if (anchor != null) {
            boolean sameSlot = relations.stream().anyMatch(r -> "SUPERSEDES".equals(r.relation())
                    && anchor.getId().equals(r.id()));
            candidate = new MemoryFactCandidate(anchor.getSubject(),
                    sameSlot ? anchor.getPredicate() : candidate.predicate(), candidate.object(),
                    candidate.content(), candidate.source(), candidate.confidence(), candidate.docMediaId(),
                    candidate.keywords(), candidate.sourceMessageIds());
        }
        List<MemoryFact> targets = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Relation relation : relations) {
            if (!"SUPERSEDES".equals(relation.relation())) {
                continue;
            }
            MemoryFact target = findOwned(userId, relation.id());
            if (target != null) {
                targets.add(target);
                reasons.add(relation.reason());
            }
        }
        return persist(userId, candidate, targets, reasons.isEmpty() ? "新增" : String.join("；", reasons));
    }

    /**
     * 定稿并落库（**所有写入路径的最后一步**）：
     * ① 先确定最终 subject/predicate/content；② 插新行；③ 把该作废的目标作废（含"同槽兜底"）；
     * ④ **按最终 content 只算一次向量**存起来；⑤ 写变更留痕。
     *
     * <p>为什么向量放在最后、只算一次：召回那次算的向量对应的是"模型刚吐出来的文本"，
     * 而这里入库的文本可能已经被收敛过（沿用了已有卡片的 subject）。**向量必须对应最终入库的文本**，
     * 否则读取时按内容召回的语义就对不上了。旧行被取代时**不重算**——行不动、向量不动，
     * 召回只查 {@code status='ACTIVE'}，旧向量不会再被用到。
     */
    private boolean persist(String userId, MemoryFactCandidate candidate, List<MemoryFact> supersedeTargets,
                            String reason) {
        MemoryFact fact = new MemoryFact();
        fact.setUserId(userId);
        fact.setSubject(candidate.subject());
        fact.setPredicate(candidate.predicate());
        fact.setObject(candidate.object());
        fact.setContent(candidate.content());
        fact.setSource(candidate.source());
        fact.setConfidence(Math.max(0, Math.min(100, candidate.confidence())));
        fact.setStatus(MemoryFact.STATUS_ACTIVE);
        fact.setValidFrom(LocalDateTime.now());
        fact.setDocMediaId(candidate.docMediaId());
        fact.setKeywords(candidate.keywords().isEmpty() ? null : truncate(String.join(",", candidate.keywords()), 500));
        fact.setCreatedAt(LocalDateTime.now());
        fact.setUpdatedAt(LocalDateTime.now());
        MemoryFact saved = factRepository.save(fact);

        Set<Long> superseded = new HashSet<>();
        for (int i = 0; i < supersedeTargets.size(); i++) {
            MemoryFact target = supersedeTargets.get(i);
            if (target != null && !target.getId().equals(saved.getId())) {
                supersede(userId, target, saved, reasons(i, reason));
                superseded.add(target.getId());
            }
        }
        // 精确同槽兜底（向量没召回到、或模型漏判时的最后一道网）：subject+predicate 完全一样、值不同 → 也算改写
        exactSlot(userId, saved, superseded);
        saveVector(saved);
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", CHANGE_LAYER, saved.getId(), null,
                saved.getContent(), candidate.source(), "AUTO"));
        log.info("事实入库 user={} id={} source={} subject={} predicate={} object={} 依据={}", userId, saved.getId(),
                saved.getSource(), saved.getSubject(), saved.getPredicate(), saved.getObject(), reason);
        return true;
    }

    private String reasons(int index, String fallback) {
        return fallback == null || fallback.isBlank() ? "被新值取代" : fallback;
    }

    /** 同一个属性槽上还有别的有效值（且 LLM 已经忘了/没召回到它）→ 按"被改写"处理 */
    private void exactSlot(String userId, MemoryFact saved, Set<Long> alreadySuperseded) {
        if (saved.getSubject() == null || saved.getPredicate() == null) {
            return;
        }
        try {
            factRepository.findFirstByUserIdAndSubjectIgnoreCaseAndPredicateIgnoreCaseAndStatus(
                            userId, saved.getSubject(), saved.getPredicate(), MemoryFact.STATUS_ACTIVE)
                    .filter(other -> !other.getId().equals(saved.getId()))
                    .filter(other -> !alreadySuperseded.contains(other.getId()))
                    .filter(other -> !sameObject(other.getObject(), saved.getObject()))
                    .ifPresent(other -> {
                        supersede(userId, other, saved, "同一个属性（" + saved.getPredicate() + "）上出现了新值");
                        alreadySuperseded.add(other.getId());
                    });
        } catch (Exception e) {
            log.warn("同槽兜底检查失败（不影响新事实入库）: {}", e.getMessage());
        }
    }

    private boolean sameObject(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        return a.equalsIgnoreCase(b);
    }

    /** 这条新事实该挂到哪张卡上：优先 SUPERSEDES（同一属性被改写），其次 SUPPLEMENT（补充同一件事） */
    private MemoryFact anchorFact(String userId, List<Relation> relations) {
        for (String kind : List.of("SUPERSEDES", "SUPPLEMENT")) {
            for (Relation relation : relations) {
                if (kind.equals(relation.relation())) {
                    MemoryFact fact = findOwned(userId, relation.id());
                    if (fact != null) {
                        return fact;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 旧值被新值取代。**DOC 基线被用户的口述取代时不下线旧行**——"图上写的是 X，你后来补充是 Y"
     * 这个区别本身就是要如实回答的信息（用户 2026-09-18 定的"DOC 基线 + USER 补丁"）。
     */
    private void supersede(String userId, MemoryFact target, MemoryFact replacement, String reason) {
        boolean keepBaseline = MemoryFact.SOURCE_DOC.equals(target.getSource())
                && !MemoryFact.SOURCE_DOC.equals(replacement.getSource());
        if (keepBaseline) {
            changeLogRepository.save(new MemoryChangeLog(userId, "SUPERSEDE", CHANGE_LAYER, target.getId(),
                    target.getContent(), replacement.getContent(), "DOC 基线保留：" + reason, "AUTO"));
            return;
        }
        target.setStatus(MemoryFact.STATUS_SUPERSEDED);
        target.setSupersededBy(replacement.getId());
        target.setValidTo(LocalDateTime.now());
        target.setUpdatedAt(LocalDateTime.now());
        factRepository.save(target);
        changeLogRepository.save(new MemoryChangeLog(userId, "SUPERSEDE", CHANGE_LAYER, target.getId(),
                target.getContent(), replacement.getContent(), reason, "AUTO"));
        log.info("事实被取代 user={} old={} new={} reason={}", userId, target.getId(), replacement.getId(), reason);
    }

    /** 同值重复：不新增，只确认一次（刷新 updated_at / confidence） */
    private void touch(String userId, MemoryFact existing, MemoryFactCandidate candidate, String reason) {
        existing.setConfidence(Math.max(existing.getConfidence() == null ? 0 : existing.getConfidence(),
                Math.max(0, Math.min(100, candidate.confidence()))));
        existing.setUpdatedAt(LocalDateTime.now());
        factRepository.save(existing);
        changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", CHANGE_LAYER, existing.getId(),
                existing.getContent(), candidate.content(), reason == null ? "同值重复" : reason, "AUTO"));
    }

    /** 语义召回：取回"最像的几条当前有效事实"（低于 min-score 的一律当无关，宁可新增不要错并） */
    private List<MemoryFact> recall(String userId, String queryText) {
        return recallFacts(userId, queryText, recallLimit, recallMinScore);
    }

    /**
     * 按语义召回事实（注入到提示词时也用这条路——**不做字面匹配**，用户问"明天在哪上课"和
     * 记忆里的"第一周周二晚数学课的教室是 303"字面上一个词都不重合，但向量上很近）。
     */
    public List<MemoryFact> recallFacts(String userId, String queryText, int limit, double minScore) {
        if (!embeddingClient.isEnabled() || userId == null || queryText == null || queryText.isBlank() || limit <= 0) {
            return List.of();
        }
        float[] query = embeddingClient.embedOne(queryText);
        if (query == null) {
            return List.of();
        }
        List<MemoryFactVectorStore.FactHit> hits = vectorStore.search(userId, query, limit, minScore);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, MemoryFact> byId = new LinkedHashMap<>();
        for (MemoryFact fact : factRepository.findByIdInAndUserId(hits.stream()
                .map(MemoryFactVectorStore.FactHit::id).toList(), userId)) {
            byId.put(fact.getId(), fact);
        }
        // 保持相似度顺序（模型看的时候也是"最像的在前"）
        List<MemoryFact> ordered = new ArrayList<>();
        for (MemoryFactVectorStore.FactHit hit : hits) {
            MemoryFact fact = byId.get(hit.id());
            if (fact != null && MemoryFact.STATUS_ACTIVE.equals(fact.getStatus())) {
                ordered.add(fact);
            }
        }
        return ordered;
    }

    private List<Relation> classify(MemoryFactCandidate candidate, List<MemoryFact> recalled) {
        if (recalled.isEmpty()) {
            return List.of();
        }
        try {
            String response = LlmScenario.run(LlmScenario.EXTRACT,
                    () -> chatModel.chat(buildClassifyPrompt(candidate, recalled)));
            return parseRelations(response);
        } catch (Exception e) {
            // 判不了就当无关（新增），保守但不会丢信息
            log.warn("事实关系判定失败（按新增处理）: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildClassifyPrompt(MemoryFactCandidate candidate, List<MemoryFact> recalled) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是长期记忆的「事实合并器」。下面有一条**新事实**，以及从记忆库里按语义召回的最相关已有事实（带 id）。\n")
                .append("请判断新事实与每条已有事实的关系。\n\n");
        prompt.append("新事实：\n")
                .append("subject=").append(candidate.subject())
                .append(" | predicate=").append(candidate.predicate())
                .append(" | object=").append(candidate.object())
                .append(" | content=").append(candidate.content()).append("\n\n");
        prompt.append("已有事实：\n");
        for (MemoryFact fact : recalled) {
            prompt.append(fact.getId()).append(": subject=").append(fact.getSubject())
                    .append(" | predicate=").append(fact.getPredicate())
                    .append(" | object=").append(fact.getObject())
                    .append(" | source=").append(fact.getSource())
                    .append(" | content=").append(fact.getContent()).append("\n");
        }
        prompt.append("\n关系只能取这四种：\n")
                .append("- SAME：同一件事的**同一个值**（换了说法、写得更细也算），不需要新增。\n")
                .append("- SUPERSEDES：同一件事的**同一个属性**，新值取代旧值（例：教室 303 → 305）。\n")
                .append("- SUPPLEMENT：同一件事的**另一个属性**或额外细节，旧信息仍然成立（例：已有「教室」，新事实是「教师」）。\n")
                .append("- UNRELATED：不是同一件事。\n")
                .append("判断「同一件事」看 subject 是不是指同一个对象，不要因为措辞不同就判无关；")
                .append("同一个属性上新旧值不能同时成立时，判 SUPERSEDES（矛盾优先于 SAME）。\n")
                .append("只需列出你判断过的条目，没列出的按 UNRELATED 处理；都不相关时 items 为空数组。\n\n")
                .append("只输出 JSON，不要解释：\n")
                .append("{\"items\":[{\"id\":12,\"relation\":\"SUPERSEDES\",\"reason\":\"教室从303改成305\"}]}");
        return prompt.toString();
    }

    private List<Relation> parseRelations(String response) {
        String text = response == null ? "" : response.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("```(json)?", "").trim();
            int end = text.lastIndexOf("```");
            if (end >= 0) {
                text = text.substring(0, end).trim();
            }
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            List<Relation> relations = new ArrayList<>();
            for (JsonNode node : root.path("items")) {
                long id = node.path("id").asLong(0);
                String relation = node.path("relation").asText("UNRELATED").trim().toUpperCase();
                if (id > 0 && ("SAME".equals(relation) || "SUPERSEDES".equals(relation)
                        || "SUPPLEMENT".equals(relation))) {
                    relations.add(new Relation(id, relation, truncate(node.path("reason").asText(""), 512)));
                }
            }
            return relations;
        } catch (Exception e) {
            log.warn("解析事实关系失败（按新增处理）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 给一条事实算向量写回（失败只记日志，事实本身已经落库） */
    private void saveVector(MemoryFact fact) {
        if (!embeddingClient.isEnabled() || fact == null) {
            return;
        }
        float[] vector = embeddingClient.embedOne(fact.getContent());
        if (vector != null) {
            vectorStore.saveEmbedding(fact.getId(), vector, embeddingClient.model());
        }
    }

    /**
     * 给"还没向量"的有效事实补向量。为什么需要：api-key 是用户后填的，
     * 之前入库的事实没有向量就**永远召回不到**（表现为"改教室"改不动，因为找不到旧那条）。
     */
    public int reindexMissing(String userId, int max) {
        if (!embeddingClient.isEnabled() || userId == null || max <= 0) {
            return 0;
        }
        try {
            Set<Long> embedded = vectorStore.idsWithEmbedding(userId);
            List<MemoryFact> pending = new ArrayList<>();
            for (MemoryFact fact : factRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId,
                    MemoryFact.STATUS_ACTIVE)) {
                if (!embedded.contains(fact.getId())) {
                    pending.add(fact);
                    if (pending.size() >= max) {
                        break;
                    }
                }
            }
            if (pending.isEmpty()) {
                return 0;
            }
            List<float[]> vectors = embeddingClient.embedAll(pending.stream().map(MemoryFact::getContent).toList());
            if (vectors == null || vectors.size() != pending.size()) {
                return 0;
            }
            for (int i = 0; i < pending.size(); i++) {
                vectorStore.saveEmbedding(pending.get(i).getId(), vectors.get(i), embeddingClient.model());
            }
            log.info("补齐事实向量 user={} count={}", userId, pending.size());
            return pending.size();
        } catch (Exception e) {
            log.warn("补齐事实向量失败 user={}: {}", userId, e.getMessage());
            return 0;
        }
    }

    /** 当前有效事实（面板与注入用），按 subject 聚合前的原始列表 */
    public List<MemoryFact> activeFacts(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<MemoryFact> all = factRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryFact.STATUS_ACTIVE);
        return limit > 0 && all.size() > limit ? all.subList(0, limit) : all;
    }

    public long countActive(String userId) {
        return factRepository.countByUserIdAndStatus(userId, MemoryFact.STATUS_ACTIVE);
    }

    public long countMissingEmbedding(String userId) {
        return vectorStore.countMissingEmbedding(userId);
    }

    /** 已经有向量的事实 id（面板显示"向量 有/无"用；embedding 列没映射进实体，只能这么问） */
    public Set<Long> embeddedIds(String userId) {
        return vectorStore.idsWithEmbedding(userId);
    }

    /**
     * 一张"卡片"= 一件事（一个 subject）的当前值。面板和召回工具共用同一份渲染，避免两处口径不一样。
     *
     * @param valuesText  形如 `教室 506（图上写的是 303）；教师 王老师`
     * @param sourcesText 形如 `用户说的` / `图片基线 + 用户补丁`
     */
    public record FactCard(String subject, String valuesText, String sourcesText, LocalDateTime updatedAt,
                           boolean hasVector, int factCount) {
    }

    /** 按 subject 聚合出卡片（面板「记忆」页用：一眼看出它以为的课表/安排是什么） */
    public List<FactCard> cards(String userId, int limit) {
        List<MemoryFact> facts = activeFacts(userId, 500);
        if (facts.isEmpty()) {
            return List.of();
        }
        Set<Long> embedded = embeddedIds(userId);
        Map<String, List<MemoryFact>> bySubject = new LinkedHashMap<>();
        for (MemoryFact fact : facts) {
            bySubject.computeIfAbsent(fact.getSubject() == null ? "（未命名）" : fact.getSubject(),
                    key -> new ArrayList<>()).add(fact);
        }
        List<FactCard> cards = new ArrayList<>();
        for (Map.Entry<String, List<MemoryFact>> entry : bySubject.entrySet()) {
            if (cards.size() >= limit) {
                break;
            }
            List<MemoryFact> group = entry.getValue();
            LocalDateTime updated = null;
            boolean vector = false;
            boolean doc = false;
            boolean user = false;
            for (MemoryFact fact : group) {
                if (fact.getUpdatedAt() != null && (updated == null || fact.getUpdatedAt().isAfter(updated))) {
                    updated = fact.getUpdatedAt();
                }
                vector = vector || embedded.contains(fact.getId());
                doc = doc || MemoryFact.SOURCE_DOC.equals(fact.getSource());
                user = user || !MemoryFact.SOURCE_DOC.equals(fact.getSource());
            }
            String sources = doc && user ? "图片基线 + 用户补丁" : doc ? "图片/文件（基线）" : "用户说的（补丁）";
            cards.add(new FactCard(entry.getKey(), renderSubjectValues(group), sources, updated, vector, group.size()));
        }
        return cards;
    }

    /** 把一组事实渲染成"属性 值"的一行（召回工具与面板共用） */
    public String renderSubjectValues(List<MemoryFact> facts) {
        // 同一个属性槽可能有多条：用户说的（USER/AUTO）压过图上看到的（DOC），DOC 原值放括号里
        Map<String, MemoryFact> primary = new LinkedHashMap<>();
        Map<String, MemoryFact> baseline = new LinkedHashMap<>();
        for (MemoryFact fact : facts) {
            String key = (fact.getPredicate() == null || fact.getPredicate().isBlank() ? "备注" : fact.getPredicate())
                    .toLowerCase();
            MemoryFact current = primary.get(key);
            if (MemoryFact.SOURCE_DOC.equals(fact.getSource())) {
                if (current == null) {
                    primary.put(key, fact);
                } else if (!sameObject(current.getObject(), fact.getObject())) {
                    baseline.put(key, fact);
                }
                continue;
            }
            if (current == null || MemoryFact.SOURCE_DOC.equals(current.getSource())) {
                primary.put(key, fact);
            }
        }
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, MemoryFact> entry : primary.entrySet()) {
            if (line.length() > 0) {
                line.append("；");
            }
            line.append(entry.getKey()).append(' ').append(entry.getValue().getObject());
            MemoryFact base = baseline.get(entry.getKey());
            if (base != null) {
                line.append("（图上写的是 ").append(base.getObject()).append("）");
            }
        }
        return line.length() == 0 ? "（没有有效值）" : line.toString();
    }

    /** 按 subject 聚合成多行文本（召回工具用） */
    public String renderCards(List<MemoryFact> facts, int charLimit) {
        Map<String, List<MemoryFact>> bySubject = new LinkedHashMap<>();
        for (MemoryFact fact : facts) {
            bySubject.computeIfAbsent(fact.getSubject() == null ? "（未命名）" : fact.getSubject(),
                    key -> new ArrayList<>()).add(fact);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<MemoryFact>> entry : bySubject.entrySet()) {
            text.append("· ").append(entry.getKey()).append('：')
                    .append(renderSubjectValues(entry.getValue())).append('\n');
            if (text.length() > charLimit) {
                text.append("…（还有没列完的，需要更具体的可以再查）\n");
                break;
            }
        }
        return text.toString().trim();
    }

    private MemoryFact findOwned(String userId, Long id) {
        if (id == null) {
            return null;
        }
        return factRepository.findById(id)
                .filter(fact -> userId.equals(fact.getUserId()))
                .orElse(null);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
