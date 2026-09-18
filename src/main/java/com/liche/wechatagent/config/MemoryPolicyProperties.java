package com.liche.wechatagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Memory-specific limits and heuristics. These are deployment policies rather
 * than facts about a particular user or business domain.
 */
@ConfigurationProperties(prefix = "memory")
public class MemoryPolicyProperties {

    public static final int DEFAULT_EXTRACTION_MAX_CANDIDATES = 32;
    public static final int DEFAULT_EXTRACTION_MAX_CONTENT_CHARS = 4_000;
    public static final int DEFAULT_EXTRACTION_MAX_KEYWORDS = 8;
    public static final int DEFAULT_EXTRACTION_CONFIDENCE = 85;
    public static final int DEFAULT_CONVERSATION_MAX_RETRIEVAL_TERMS = 8;
    public static final int DEFAULT_CONVERSATION_FORGET_SCAN_BATCH = 250;
    public static final int DEFAULT_CORE_MAX_CONTENT_CHARS = 4_000;
    public static final int DEFAULT_WORK_MAX_CONTENT_CHARS = 2_000;
    public static final int CORE_CONTENT_COLUMN_MAX_CHARS = 4_000;
    public static final int WORK_CONTENT_COLUMN_MAX_CHARS = 2_000;
    public static final int DEFAULT_LINKED_MEDIA_MAX_PER_MEMORY = 3;
    public static final int DEFAULT_LINKED_MEDIA_SUMMARY_MAX_CHARS = 80;
    public static final int DEFAULT_HISTORICAL_ITEM_MAX_CHARS = 1_200;
    public static final int DEFAULT_EXTRACTION_RECENT_TURNS = 20;
    public static final int DEFAULT_MIN_CONFIDENCE = 60;
    public static final int DEFAULT_WORK_PRIORITY = 3;
    public static final int DEFAULT_CORE_IMPORTANCE = 5;
    public static final int DEFAULT_WORK_IMPORTANCE = 3;
    private static final List<String> DEFAULT_HISTORY_MARKERS = List.of(
            "之前", "上次", "以前", "曾经", "历史", "记得", "聊过", "说过", "那时候", "过去", "还记得");
    private static final List<String> DEFAULT_CONVERSATION_RETRIEVAL_NOISE = List.of(
            "之前", "上次", "以前", "曾经", "历史", "记得", "聊过", "说过", "那时候", "过去", "还记得",
            "用户", "助手", "帮我", "请问", "这个", "那个", "什么", "怎么", "一下", "看看");
    private static final List<String> DEFAULT_NEGATION_MARKERS = List.of(
            "不再", "不想", "不会", "取消", "放弃", "停止", "改考", "不喜欢", "不需要");

    private int extractionMaxCandidates = DEFAULT_EXTRACTION_MAX_CANDIDATES;
    private int extractionMaxContentChars = DEFAULT_EXTRACTION_MAX_CONTENT_CHARS;
    private int extractionMaxKeywords = DEFAULT_EXTRACTION_MAX_KEYWORDS;
    private int extractionDefaultConfidence = DEFAULT_EXTRACTION_CONFIDENCE;
    private double dedupThreshold = 0.8d;
    private int conversationMaxRetrievalTerms = DEFAULT_CONVERSATION_MAX_RETRIEVAL_TERMS;
    private int conversationForgetScanBatch = DEFAULT_CONVERSATION_FORGET_SCAN_BATCH;
    private int coreMaxContentChars = DEFAULT_CORE_MAX_CONTENT_CHARS;
    private int workMaxContentChars = DEFAULT_WORK_MAX_CONTENT_CHARS;
    private int linkedMediaMaxPerMemory = DEFAULT_LINKED_MEDIA_MAX_PER_MEMORY;
    private int linkedMediaSummaryMaxChars = DEFAULT_LINKED_MEDIA_SUMMARY_MAX_CHARS;
    private int historicalItemMaxChars = DEFAULT_HISTORICAL_ITEM_MAX_CHARS;
    private int extractionRecentTurns = DEFAULT_EXTRACTION_RECENT_TURNS;
    private int minConfidence = DEFAULT_MIN_CONFIDENCE;
    private int defaultWorkPriority = DEFAULT_WORK_PRIORITY;
    private int defaultCoreImportance = DEFAULT_CORE_IMPORTANCE;
    private int defaultWorkImportance = DEFAULT_WORK_IMPORTANCE;
    private List<String> historyMarkers = new ArrayList<>(DEFAULT_HISTORY_MARKERS);
    private List<String> conversationRetrievalNoise = new ArrayList<>(DEFAULT_CONVERSATION_RETRIEVAL_NOISE);
    private List<String> negationMarkers = new ArrayList<>(DEFAULT_NEGATION_MARKERS);

    public int getExtractionMaxCandidates() {
        return extractionMaxCandidates;
    }

    public void setExtractionMaxCandidates(int extractionMaxCandidates) {
        this.extractionMaxCandidates = extractionMaxCandidates;
    }

    public int getExtractionMaxContentChars() {
        return extractionMaxContentChars;
    }

    public void setExtractionMaxContentChars(int extractionMaxContentChars) {
        this.extractionMaxContentChars = extractionMaxContentChars;
    }

    public int getExtractionMaxKeywords() {
        return extractionMaxKeywords;
    }

    public void setExtractionMaxKeywords(int extractionMaxKeywords) {
        this.extractionMaxKeywords = extractionMaxKeywords;
    }

    public int getExtractionDefaultConfidence() {
        return extractionDefaultConfidence;
    }

    public void setExtractionDefaultConfidence(int extractionDefaultConfidence) {
        this.extractionDefaultConfidence = extractionDefaultConfidence;
    }

    public double getDedupThreshold() {
        return dedupThreshold;
    }

    public void setDedupThreshold(double dedupThreshold) {
        this.dedupThreshold = dedupThreshold;
    }

    public int getConversationMaxRetrievalTerms() {
        return conversationMaxRetrievalTerms;
    }

    public void setConversationMaxRetrievalTerms(int conversationMaxRetrievalTerms) {
        this.conversationMaxRetrievalTerms = conversationMaxRetrievalTerms;
    }

    public int getConversationForgetScanBatch() {
        return conversationForgetScanBatch;
    }

    public void setConversationForgetScanBatch(int conversationForgetScanBatch) {
        this.conversationForgetScanBatch = conversationForgetScanBatch;
    }

    public int getCoreMaxContentChars() {
        return coreMaxContentChars;
    }

    public void setCoreMaxContentChars(int coreMaxContentChars) {
        this.coreMaxContentChars = coreMaxContentChars;
    }

    public int getWorkMaxContentChars() {
        return workMaxContentChars;
    }

    public void setWorkMaxContentChars(int workMaxContentChars) {
        this.workMaxContentChars = workMaxContentChars;
    }

    public int getLinkedMediaMaxPerMemory() {
        return linkedMediaMaxPerMemory;
    }

    public void setLinkedMediaMaxPerMemory(int linkedMediaMaxPerMemory) {
        this.linkedMediaMaxPerMemory = linkedMediaMaxPerMemory;
    }

    public int getLinkedMediaSummaryMaxChars() {
        return linkedMediaSummaryMaxChars;
    }

    public void setLinkedMediaSummaryMaxChars(int linkedMediaSummaryMaxChars) {
        this.linkedMediaSummaryMaxChars = linkedMediaSummaryMaxChars;
    }

    public int getHistoricalItemMaxChars() {
        return historicalItemMaxChars;
    }

    public void setHistoricalItemMaxChars(int historicalItemMaxChars) {
        this.historicalItemMaxChars = historicalItemMaxChars;
    }




    public int getExtractionRecentTurns() {
        return extractionRecentTurns;
    }

    public void setExtractionRecentTurns(int extractionRecentTurns) {
        this.extractionRecentTurns = extractionRecentTurns;
    }

    public int getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(int minConfidence) {
        this.minConfidence = minConfidence;
    }

    public int getDefaultWorkPriority() {
        return defaultWorkPriority;
    }

    public void setDefaultWorkPriority(int defaultWorkPriority) {
        this.defaultWorkPriority = defaultWorkPriority;
    }

    public int getDefaultCoreImportance() {
        return defaultCoreImportance;
    }

    public void setDefaultCoreImportance(int defaultCoreImportance) {
        this.defaultCoreImportance = defaultCoreImportance;
    }

    public int getDefaultWorkImportance() {
        return defaultWorkImportance;
    }

    public void setDefaultWorkImportance(int defaultWorkImportance) {
        this.defaultWorkImportance = defaultWorkImportance;
    }

    public List<String> getHistoryMarkers() {
        return List.copyOf(historyMarkers);
    }

    public void setHistoryMarkers(List<String> historyMarkers) {
        this.historyMarkers = normalizedTerms(historyMarkers, DEFAULT_HISTORY_MARKERS);
    }

    public List<String> getConversationRetrievalNoise() {
        return List.copyOf(conversationRetrievalNoise);
    }

    public void setConversationRetrievalNoise(List<String> conversationRetrievalNoise) {
        this.conversationRetrievalNoise = normalizedTerms(conversationRetrievalNoise,
                DEFAULT_CONVERSATION_RETRIEVAL_NOISE);
    }

    public List<String> getNegationMarkers() {
        return List.copyOf(negationMarkers);
    }

    public void setNegationMarkers(List<String> negationMarkers) {
        this.negationMarkers = normalizedTerms(negationMarkers, DEFAULT_NEGATION_MARKERS);
    }

    private List<String> normalizedTerms(List<String> values, List<String> fallback) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>(fallback);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized.isEmpty() ? new ArrayList<>(fallback) : new ArrayList<>(normalized);
    }
}
