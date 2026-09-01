package com.liche.wechatagent.media;

import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class MediaMemoryTool {

    private final MediaStorageService storageService;
    private final MediaToolContextService mediaContext;
    private final ToolStatusService statusService;

    public MediaMemoryTool(MediaStorageService storageService,
                           MediaToolContextService mediaContext,
                           ToolStatusService statusService) {
        this.storageService = storageService;
        this.mediaContext = mediaContext;
        this.statusService = statusService;
    }

    @Tool(value = "仅当本条消息上传，或已先通过 inspectRecentUnstoredMedia 明确取回的图片或文件，对用户未来仍有明显复用价值时保存。请根据内容、用户语境和是否已有等价副本判断；临时、低价值、重复或用途不明的媒体不要保存。mediaIndex 必须来自工具已提供的媒体候选；fileName 由你按内容清晰命名；summary 和 importanceReason 必须具体。")
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

    @Tool(value = "读取当前用户已保存资料的内容。先用 listStoredMedia 找到 mediaId；文本文件会提供提取内容，图片会重新交给视觉模型查看。用户询问此前保存的图片或文件内容时调用。不能用于删除审阅。")
    public String readStoredMedia(Long mediaId) {
        MediaStorageService.ReadOutcome outcome = storageService.readForAssistant(requireCurrentUser(), mediaId);
        mediaContext.addReadableMedia(outcome.description(), outcome.imageDataUrl());
        return outcome.description();
    }

    @Tool(value = "审阅当前用户的一个已保存文件，明确其文件名、摘要、来源内容和保存原因。任何删除之前必须先调用本工具，并读取返回的 inspectionToken。")
    public String inspectStoredMedia(Long mediaId) {
        return storageService.inspect(requireCurrentUser(), mediaId);
    }

    @Tool(value = "将当前用户的已保存文件移入该用户回收目录。只能在刚刚调用 inspectStoredMedia、确认清楚文件内容且确有删除必要后调用；inspectionToken 必须使用审阅结果返回的令牌，reason 必须具体。不会永久删除。")
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
