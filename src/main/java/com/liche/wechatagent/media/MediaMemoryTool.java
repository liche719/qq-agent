package com.liche.wechatagent.media;

import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaMemoryTool implements com.liche.wechatagent.tool.AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MediaMemoryTool.class);

    private final MediaStorageService storageService;
    private final MediaToolContextService mediaContext;
    private final ToolStatusService statusService;
    /** 一次任务最多往模型请求里塞几张已保存图片（防止模型把候选一个个看完把上下文撑爆） */
    private final int maxImagesPerTask;

    public MediaMemoryTool(MediaStorageService storageService,
                           MediaToolContextService mediaContext,
                           ToolStatusService statusService,
                           @Value("${media.context.max-images-per-task:3}") int maxImagesPerTask) {
        this.storageService = storageService;
        this.mediaContext = mediaContext;
        this.statusService = statusService;
        this.maxImagesPerTask = Math.max(1, Math.min(20, maxImagesPerTask));
    }

    @Tool(value = "仅当本条消息上传，或已先通过 inspectRecentUnstoredMedia 明确取回的图片或文件，对用户未来仍有明显复用价值时保存。请根据内容、用户语境和是否已有等价副本判断；临时、低价值、重复或用途不明的媒体不要保存。mediaIndex 必须来自工具已提供的媒体候选；fileName 由你按内容清晰命名；summary 和 importanceReason 必须具体，而且**要带上以后可能被搜到的关键词**（是什么、属于哪门课或哪个主题、涉及的时间/人名）——以后用户只会用关键词来找它，搜的就是名字和摘要。")
    @com.liche.wechatagent.tool.NonIdempotentTool
    public String saveImportantMedia(int mediaIndex, String fileName, String summary, String importanceReason) {
        String userId = requireCurrentUser();
        if (!userId.equals(mediaContext.currentUserId())) {
            throw new IllegalStateException("媒体与当前用户上下文不一致");
        }
        MediaCandidate candidate = mediaContext.requireCandidate(mediaIndex);
        MediaStorageService.SaveOutcome outcome = storageService.saveDetailed(userId,
                mediaContext.currentMediaSourceMessageId(), candidate, fileName, summary, importanceReason);
        mediaContext.recordSaved(mediaIndex == 0 ? 1 : mediaIndex, outcome.fileName(), outcome.duplicate(), outcome.olderVersionPreserved());
        return outcome.message();
    }

    @Tool(value = "仅当用户当前明确指代刚才、上一条、此前刚发但尚未保存的图片或文件时调用。userReference 必须原样填写用户本条消息中实际出现的指代语。它会取回当前会话自己的近期未保存媒体，供后续查看或保存；返回内容一定属于此前上传，不是本条消息的新附件。普通聊天、无明确指代时不得调用。")
    public String inspectRecentUnstoredMedia(String userReference) {
        String userId = requireCurrentUser();
        if (!userId.equals(mediaContext.currentUserId())) {
            throw new IllegalStateException("媒体与当前用户上下文不一致");
        }
        return mediaContext.inspectRecentUnstoredMedia(userReference);
    }

    @Tool(value = "按文件名、摘要、重要原因或已提取文档内容，检索当前用户自己长期保存的图片和文件。query 可为空，空值列出最近文件；如果结果提示没有精确匹配但给出了最近文件候选，必须先核对候选的文件名、摘要或内容，不能直接断言文件不存在。")
    public String listStoredMedia(String query) {
        return storageService.list(requireCurrentUser(), query);
    }

    @Tool(value = "读取当前用户已保存资料的内容。先用 listStoredMedia 找到 mediaId；文本文件会提供提取内容，图片会重新交给视觉模型查看。用户询问此前保存的图片或文件内容时调用。不能用于删除审阅。看完图片后必须调用 noteStoredMediaContent 记一句里面是什么。")
    public ToolBusinessResult readStoredMedia(Long mediaId) {
        String userId = requireCurrentUser();
        try {
            MediaStorageService.ReadOutcome outcome = storageService.readForAssistant(userId, mediaId);
            boolean image = outcome.imageDataUrl() != null && !outcome.imageDataUrl().isBlank();
            if (image && mediaContext.reserveImageRead() > maxImagesPerTask) {
                log.warn("本轮读图已达上限 {}，拒绝继续塞图 mediaId={} user={}", maxImagesPerTask, mediaId, userId);
                return ToolBusinessResult.failure("这条消息里我已经看过 " + maxImagesPerTask + " 张图了，再看下去会把上下文撑爆。"
                        + "先用已经看到的内容回答用户；确实还要看别的，就告诉用户下一句点名要哪一张（或者按名字/时间筛一下）。");
            }
            mediaContext.addReadableMedia(outcome.description(), outcome.imageDataUrl());
            if (!image) {
                return ToolBusinessResult.success(outcome.description());
            }
            return ToolBusinessResult.success(outcome.description()
                    + "\n看完这张图后，请调用 noteStoredMediaContent 用一句话记下里面到底是什么"
                    + "（带上课表/科目/时间这类关键词，以后才搜得到它）。");
        } catch (MediaSourceMissingException exception) {
            // 文件不会自己出现：直接告诉模型读不了，不要被工具框架当成瞬时故障再重试一轮
            return ToolBusinessResult.failure(exception.getMessage() + "。这个文件的原始数据已经无法找回，"
                    + "如实告诉用户，不要再反复尝试读取。");
        }
    }

    @Tool(value = "看完一个已保存文件（尤其是图片）之后，用一句话记下里面到底是什么，供以后按内容检索。hint 要包含以后可能被搜到的关键词：是什么、属于哪门课或哪个主题、涉及的时间或人名。同一份文件重复调用会覆盖上一次的记录。只记内容，不要复述文件路径或 ID。")
    public ToolBusinessResult noteStoredMediaContent(Long mediaId, String hint) {
        return ToolBusinessResult.success(storageService.noteContentHint(requireCurrentUser(), mediaId, hint));
    }

    @Tool(value = "审阅当前用户的一个已保存文件，明确其文件名、摘要、来源内容和保存原因。任何删除之前必须先调用本工具，并读取返回的 inspectionToken。")
    public ToolBusinessResult inspectStoredMedia(Long mediaId) {
        try {
            return ToolBusinessResult.success(storageService.inspect(requireCurrentUser(), mediaId));
        } catch (MediaSourceMissingException exception) {
            return ToolBusinessResult.failure(exception.getMessage() + "。这个文件的原始数据已经无法找回，"
                    + "如实告诉用户，也删不了它。");
        }
    }

    @Tool(value = "将当前用户的已保存文件移入该用户回收目录。只能在刚刚调用 inspectStoredMedia、确认清楚文件内容且确有删除必要后调用；inspectionToken 必须使用审阅结果返回的令牌，reason 必须具体。不会永久删除。")
    @com.liche.wechatagent.tool.ToolExecutionPolicy(value = com.liche.wechatagent.tool.ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true,
            destructive = true, requiresConfirmation = true, confirmationParameter = "inspectionToken",
            riskLevel = com.liche.wechatagent.tool.ToolRiskLevel.HIGH,
            allowParallel = false, retryable = false)
    @com.liche.wechatagent.tool.NonIdempotentTool
    public String deleteStoredMedia(Long mediaId, String inspectionToken, String reason) {
        return storageService.trash(requireCurrentUser(), mediaId, inspectionToken, reason);
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }

}
