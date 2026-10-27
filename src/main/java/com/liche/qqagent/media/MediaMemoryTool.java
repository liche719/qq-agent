package com.liche.qqagent.media;

import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaMemoryTool implements com.liche.qqagent.tool.AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MediaMemoryTool.class);

    private final MediaStorageService storageService;
    private final MediaToolContextService mediaContext;
    private final ToolStatusService statusService;
    /** 一次任务最多把几个已保存文件读给模型（**图片也算文件**；防止模型把候选一个个看完把上下文撑爆） */
    private final int maxFilesPerTask;

    public MediaMemoryTool(MediaStorageService storageService,
                           MediaToolContextService mediaContext,
                           ToolStatusService statusService,
                           @Value("${media.context.max-files-per-task:10}") int maxFilesPerTask) {
        this.storageService = storageService;
        this.mediaContext = mediaContext;
        this.statusService = statusService;
        this.maxFilesPerTask = Math.max(1, Math.min(50, maxFilesPerTask));
    }

    @Tool(value = "保存他刚发的图片/文件（**未来还会用到**才存）。先 inspectRecentUnstoredMedia 取得引用。")
    @com.liche.qqagent.tool.NonIdempotentTool
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

    @Tool(value = "他说「刚才那张图」但还没保存时：取回那条未保存的图片/文件。userReference 原样抄他的话。")
    public String inspectRecentUnstoredMedia(String userReference) {
        String userId = requireCurrentUser();
        if (!userId.equals(mediaContext.currentUserId())) {
            throw new IllegalStateException("媒体与当前用户上下文不一致");
        }
        return mediaContext.inspectRecentUnstoredMedia(userReference);
    }

    @Tool(value = "按文件名/摘要/内容找他保存过的资料；query 留空列最近。**先拿到 mediaId 再 readStoredMedia**。")
    public String listStoredMedia(String query) {
        return storageService.list(requireCurrentUser(), query);
    }

    @Tool(value = "读某个已保存资料的内容（先 listStoredMedia 拿 mediaId）；图片会重新交给视觉模型看。")
    public ToolBusinessResult readStoredMedia(Long mediaId) {
        String userId = requireCurrentUser();
        try {
            MediaStorageService.ReadOutcome outcome = storageService.readForAssistant(userId, mediaId);
            boolean image = outcome.imageDataUrl() != null && !outcome.imageDataUrl().isBlank();
            if (mediaContext.reserveFileRead(mediaId) > maxFilesPerTask) {
                log.warn("本轮读取文件已达上限 {}，拒绝继续读取 mediaId={} user={}", maxFilesPerTask, mediaId, userId);
                return ToolBusinessResult.failure("这条消息里我已经读过 " + maxFilesPerTask + " 个文件了，再读下去会把上下文撑爆。"
                        + "先用已经看到的内容回答用户；确实还要看别的，就告诉用户下一句点名要哪几个（或者按名字/时间筛一下）。");
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

    @Tool(value = "看完文件后一句话记下里面是什么，带上以后可能被搜到的关键词。")
    /** 写一条笔记：重试会插两行（2026-10-28 补） */
    @com.liche.qqagent.tool.NonIdempotentTool
    public ToolBusinessResult noteStoredMediaContent(Long mediaId, String hint) {
        return ToolBusinessResult.success(storageService.noteContentHint(requireCurrentUser(), mediaId, hint));
    }

    @Tool(value = "删除前必须先调：确认这个文件是什么、为什么存。会返回 inspectionToken。")
    public ToolBusinessResult inspectStoredMedia(Long mediaId) {
        try {
            return ToolBusinessResult.success(storageService.inspect(requireCurrentUser(), mediaId));
        } catch (MediaSourceMissingException exception) {
            return ToolBusinessResult.failure(exception.getMessage() + "。这个文件的原始数据已经无法找回，"
                    + "如实告诉用户，也删不了它。");
        }
    }

    @Tool(value = "把已保存文件移入回收目录。**必须先 inspectStoredMedia** 并带上它返回的 token。")
    @com.liche.qqagent.tool.ToolExecutionPolicy(value = com.liche.qqagent.tool.ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true,
            destructive = true, requiresConfirmation = true, confirmationParameter = "inspectionToken",
            riskLevel = com.liche.qqagent.tool.ToolRiskLevel.HIGH,
            allowParallel = false, retryable = false)
    @com.liche.qqagent.tool.NonIdempotentTool
    public String deleteStoredMedia(Long mediaId, String inspectionToken, String reason) {
        return storageService.trash(requireCurrentUser(), mediaId, inspectionToken, reason);
    }

    private String requireCurrentUser() {
        return statusService.requireCurrentUserId();
    }

}
