package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 它自己的**工作区**（2026-10-20 加）：一个有边界的文件夹，它在里面可以随便折腾，出不去。
 *
 * <p>用户的原话：「给自主模块分配一个工作区文件夹，它可以在里面做任何想做的事情，但是不能影响工作区外面」。
 *
 * <p><b>安全是怎么保证的</b>（这是整个类的重点）：
 * <ol>
 *   <li>每次操作都把路径 `normalize()` + `toRealPath()`（文件还不存在时校验它的**父目录**），
 *       再判断是否 `startsWith(root)` —— 这样 `../` 和**符号链接**都逃不出去；</li>
 *   <li>只做**文本**读写，不执行任何命令 —— "能做的事"限定在文件层面，没有 shell 就没有破坏力；</li>
 *   <li>单个文件上限 {@code maxFileBytes}（默认 1 MB），防止它把磁盘写满；</li>
 *   <li>根目录**必须配了才工作**（空配置 = 关掉），默认不启用。</li>
 * </ol>
 *
 * <p>为什么不给它 shell：一旦能执行命令，"不能影响外面"就只剩容器边界这一层，
 * 而容器里它还连着数据库和网络。**文件沙箱是可证明的边界，shell 不是。**
 */
@Service
public class SelfWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(SelfWorkspaceService.class);

    private final Path root;
    private final int maxFileBytes;
    private final int maxListEntries;

    public SelfWorkspaceService(@Value("${memory.self-workspace-dir:}") String dir,
                                @Value("${memory.self-workspace-max-file-bytes:1048576}") int maxFileBytes,
                                @Value("${memory.self-workspace-max-list-entries:200}") int maxListEntries) {
        this.maxFileBytes = Math.max(1024, maxFileBytes);
        this.maxListEntries = Math.max(10, maxListEntries);
        Path resolved = null;
        if (dir != null && !dir.isBlank()) {
            try {
                Path path = Paths.get(dir.trim()).toAbsolutePath().normalize();
                Files.createDirectories(path);
                resolved = path.toRealPath();
                log.info("自主工作区已启用：{}", resolved);
            } catch (IOException exception) {
                log.warn("自主工作区目录建不出来，工作区功能关闭：{}", exception.getMessage());
            }
        }
        this.root = resolved;
    }

    public boolean enabled() {
        return root != null;
    }

    public String rootPath() {
        return root == null ? "" : root.toString();
    }

    /**
     * 把模型给的相对路径解析成**确实在根目录内**的绝对路径。
     *
     * @throws IllegalArgumentException 越界或非法
     */
    private Path resolveInside(String relative) {
        if (root == null) {
            throw new IllegalArgumentException("工作区还没启用（memory.self-workspace-dir 没配）");
        }
        String cleaned = relative == null ? "" : relative.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path candidate = root.resolve(cleaned).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("只能在自己的工作区里操作，不能访问 " + relative);
        }
        // 已经存在的部分要过一遍 toRealPath，防符号链接指到外面
        Path probe = candidate;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            try {
                Path real = probe.toRealPath();
                if (!real.startsWith(root)) {
                    throw new IllegalArgumentException("这个路径通过符号链接指到了工作区外面：" + relative);
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("路径检查失败：" + exception.getMessage());
            }
        }
        return candidate;
    }

    /** 列目录（相对 root）；文件也列，带大小 */
    public String list(String relative) {
        Path dir = resolveInside(relative);
        if (!Files.exists(dir)) {
            return "（这个目录还不存在：" + relative + "）";
        }
        if (Files.isRegularFile(dir)) {
            return "这是一个文件，用 read 看内容：" + relative;
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.sorted(Comparator.comparing(Path::getFileName)).toList();
            int shown = 0;
            for (Path entry : entries) {
                if (shown++ >= maxListEntries) {
                    lines.add("…（还有更多，没全列）");
                    break;
                }
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    lines.add("[目录] " + name + "/");
                } else {
                    lines.add("[文件] " + name + "（" + Files.size(entry) + " 字节）");
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("列目录失败：" + exception.getMessage());
        }
        if (lines.isEmpty()) {
            return "（空目录）";
        }
        return "工作区 " + (relative == null || relative.isBlank() ? "/" : relative) + " 下有 "
                + lines.size() + " 项：\n" + String.join("\n", lines);
    }

    public String read(String relative) {
        Path file = resolveInside(relative);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("文件不存在：" + relative);
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("这是一个目录，用 list 看里面有什么");
        }
        try {
            long size = Files.size(file);
            if (size > maxFileBytes) {
                throw new IllegalArgumentException("文件太大（" + size + " 字节），超过读取上限");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取失败：" + exception.getMessage());
        }
    }

    /**
     * 写文件。
     *
     * @param append true = 追加，false = 覆盖
     */
    public String write(String relative, String content, boolean append) {
        Path file = resolveInside(relative);
        String text = content == null ? "" : content;
        if (text.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            throw new IllegalArgumentException("内容太大，超过单文件上限（" + maxFileBytes + " 字节）");
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (append) {
                Files.writeString(file, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            long size = Files.size(file);
            return (append ? "追加完成：" : "写入完成：") + relative + "（现在 " + size + " 字节）";
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入失败：" + exception.getMessage());
        }
    }

    /** 删文件或空目录（**不递归**，避免它一条命令清空整个工作区） */
    public String delete(String relative) {
        Path target = resolveInside(relative);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("不存在：" + relative);
        }
        if (Files.isDirectory(target)) {
            try (Stream<Path> stream = Files.list(target)) {
                if (stream.findAny().isPresent()) {
                    throw new IllegalArgumentException("目录不是空的，先删里面的文件");
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("检查目录失败：" + exception.getMessage());
            }
            try {
                Files.delete(target);
                return "目录已删：" + relative;
            } catch (IOException exception) {
                throw new IllegalArgumentException("删除失败：" + exception.getMessage());
            }
        }
        try {
            Files.delete(target);
            return "文件已删：" + relative;
        } catch (IOException exception) {
            throw new IllegalArgumentException("删除失败：" + exception.getMessage());
        }
    }

    /** 建目录（含中间层级） */
    public String mkdir(String relative) {
        Path dir = resolveInside(relative);
        try {
            Files.createDirectories(dir);
            return "目录已就绪：" + relative;
        } catch (IOException exception) {
            throw new IllegalArgumentException("建目录失败：" + exception.getMessage());
        }
    }
}
