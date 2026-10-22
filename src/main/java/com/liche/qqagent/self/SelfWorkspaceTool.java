package com.liche.qqagent.self;

import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import org.springframework.stereotype.Component;
import dev.langchain4j.agent.tool.Tool;

/**
 * 它自己的**工作区**工具（2026-10-20 加）。
 *
 * <p>用户要的是"给它一个文件夹，它可以在里面做任何想做的事情，但不能影响外面"。
 * 所以这里只暴露**文件层面**的四件事：列、读、写、删（+ 建目录），
 * **不提供执行命令的能力** —— 理由见 {@link SelfWorkspaceService} 的类注释。
 *
 * <p>这些工具只在**它自己的时间**（自主活动）里下发；机主对话里用不到它们（那边的需求是办事，
 * 走已有的文件/媒体工具）。{@link BilibiliActivity} 之类的活动把它们加进白名单即可。
 */
@Component
public class SelfWorkspaceTool implements AgentToolProvider {

    private final SelfWorkspaceService workspace;

    public SelfWorkspaceTool(SelfWorkspaceService workspace) {
        this.workspace = workspace;
    }

    @Tool(value = "看看你自己工作区的文件。path 是相对路径，留空就是看根目录。"
            + "工作区是你自己的地方，可以随便放东西；但它只有这么大，别乱堆。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfWorkList(String path) {
        if (!workspace.enabled()) {
            return ToolBusinessResult.failure("工作区还没启用（服务器没配 memory.self-workspace-dir）");
        }
        try {
            return ToolBusinessResult.success(workspace.list(path));
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "读你自己工作区里的一个文件。path 是相对路径（例如 notes/想法.md）。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfWorkRead(String path) {
        if (!workspace.enabled()) {
            return ToolBusinessResult.failure("工作区还没启用");
        }
        try {
            return ToolBusinessResult.success(workspace.read(path));
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "往你自己工作区里写一个文件。mode 传 append 就是追加（适合记流水），"
            + "不传就是覆盖（适合改草稿）。目录不存在会自动建。单个文件最多 16 MB。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfWorkWrite(String path, String content, String mode) {
        if (!workspace.enabled()) {
            return ToolBusinessResult.failure("工作区还没启用");
        }
        try {
            boolean append = mode != null && mode.trim().equalsIgnoreCase("append");
            return ToolBusinessResult.success(workspace.write(path, content, append));
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "删你自己工作区里的一个文件，或者一个**空**目录。"
            + "目录里有东西会拒绝——要清空得一个一个删，免得你一次删掉整个工作区。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfWorkDelete(String path) {
        if (!workspace.enabled()) {
            return ToolBusinessResult.failure("工作区还没启用");
        }
        try {
            return ToolBusinessResult.success(workspace.delete(path));
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "在你自己工作区里建一个目录。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfWorkMkdir(String path) {
        if (!workspace.enabled()) {
            return ToolBusinessResult.failure("工作区还没启用");
        }
        try {
            return ToolBusinessResult.success(workspace.mkdir(path));
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }
}
