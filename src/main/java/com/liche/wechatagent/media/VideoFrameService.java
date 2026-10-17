package com.liche.wechatagent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频抽帧：把一段视频变成「模型能看的几张图」（2026-10-17 加）。
 *
 * <p><b>为什么需要</b>：视频对模型等于不存在——QQ 里发来的视频落到附件通道，
 * 而 {@code DocumentExtractionService} 只认 PDF/DOCX/PPT，模型只看到 {@code [文件]} 两个字。
 * 用户 2026-10-10 明确要求"能看懂我发的视频"，B站那半边当天做完了（读字幕），
 * 这条补的是**相册视频**；顺带让 B站**没字幕时也能抽帧兜底**。
 *
 * <p><b>参数怎么定的（用户让我自己拍，2026-10-17）</b>：
 * <ul>
 *   <li><b>帧数随时长自适应</b>：≤20 秒 4 帧、≤1 分钟 5 帧、≤3 分钟 6 帧、≤10 分钟 8 帧、更长 10 帧。
 *       太短的视频抽 10 帧全是重复画面（白烧 token），太长的只抽 4 帧又看不清。</li>
 *   <li><b>先按镜头变化抽</b>（{@code select='gt(scene,0.3)'}）：同一场景里抽 6 张几乎一样的图没有意义，
 *       让人头讲话类的视频 6 帧可能全是同一张脸。镜头变化的帧信息量最大。</li>
 *   <li><b>镜头不够再均匀补齐</b>：镜头少的视频（录屏、讲话）会只抽到 1~2 张，
 *       这时按"整段均匀时间点"补到目标帧数，避免只看到开头。</li>
 *   <li><b>只看前 10 分钟</b>：超长视频全片抽帧既慢又没重点，明确告诉调用方"只看了前 10 分钟"。</li>
 *   <li><b>60MB / 120 秒超时</b>：抽帧是 CPU 活，这台机器只有 1.8G 内存，必须有个天花板。</li>
 * </ul>
 *
 * <p><b>输出</b>：JPEG data URL（长边 720，质量 4 ≈ 每张 20~80KB），可直接喂
 * {@code ImageContent.from(...)}——{@code ImageContentLoader} 认 {@code data:} 开头。
 */
@Component
public class VideoFrameService {

    private static final Logger log = LoggerFactory.getLogger(VideoFrameService.class);

    /** 抽出来的每张图长边上限：720 够模型认出画面，又不至于每帧吃掉几千 token */
    private static final int TARGET_LONG_EDGE = 720;
    /** 拼联系表时每格的长边（比单帧小，因为一张图里要塞 4~10 格） */
    private static final int TILE_LONG_EDGE = 480;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");

    private final String ffmpeg;
    private final String ffprobe;
    private final com.liche.wechatagent.network.PublicUrlValidator urlValidator;
    private final long maxFileBytes;
    private final double maxWindowSeconds;
    private final int maxFrames;
    private final int timeoutSeconds;
    private final double sceneThreshold;

    @org.springframework.beans.factory.annotation.Autowired
    public VideoFrameService(@Value("${video.ffmpeg:ffmpeg}") String ffmpeg,
                             @Value("${video.ffprobe:ffprobe}") String ffprobe,
                             com.liche.wechatagent.network.PublicUrlValidator urlValidator,
                             @Value("${video.max-file-bytes:62914560}") long maxFileBytes,
                             @Value("${video.max-window-seconds:600}") double maxWindowSeconds,
                             @Value("${video.max-frames:10}") int maxFrames,
                             @Value("${video.timeout-seconds:120}") int timeoutSeconds,
                             @Value("${video.scene-threshold:0.3}") double sceneThreshold) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.urlValidator = urlValidator;
        // 上限兜一圈：配置写错（比如 0）不能把功能整个弄废
        this.maxFileBytes = Math.max(5L * 1024 * 1024, maxFileBytes);
        this.maxWindowSeconds = Math.max(18d, Math.min(3600d, maxWindowSeconds));
        this.maxFrames = Math.max(2, Math.min(16, maxFrames));
        this.timeoutSeconds = Math.max(20, Math.min(600, timeoutSeconds));
        this.sceneThreshold = Math.max(0.05d, Math.min(0.9d, sceneThreshold));
    }

    /** 不带 URL 校验的构造器（单元测试/直接 new 时用；生产走上面那个 @Autowired 的） */
    public VideoFrameService(String ffmpeg, String ffprobe, long maxFileBytes, double maxWindowSeconds,
                             int maxFrames, int timeoutSeconds, double sceneThreshold) {
        this(ffmpeg, ffprobe, null, maxFileBytes, maxWindowSeconds, maxFrames, timeoutSeconds, sceneThreshold);
    }

    /** 一张抽出来的画面：第几秒 + data URL。 */
    public record Frame(double second, String dataUrl) {
    }

    /**
     * 一次抽帧的结果。
     *
     * @param frames       抽到的画面（可能少于目标帧数，也可能为空）
     * @param duration     视频总时长（秒；取不到时为 -1）
     * @param window       实际分析的时段（秒）——小于总时长就说明只看了前段
     * @param targetFrames 按策略本该抽几帧
     * @param note         给调用方/模型的一句人话（失败原因或"只看了前 10 分钟"这类）
     */
    public record Result(List<Frame> frames, double duration, double window, int targetFrames, String note) {

        public boolean usable() {
            return frames != null && !frames.isEmpty();
        }
    }

    /**
     * 抽帧主入口：给一个**已经在本地**的视频文件。
     *
     * <p>永远不抛异常：抽不出来就返回空 frames + note，由调用方决定怎么跟用户交代。
     * 视频格式坏、ffmpeg 没装、超时，都走这条路。
     */
    public Result extract(Path videoFile) {
        if (videoFile == null || !Files.isRegularFile(videoFile)) {
            return new Result(List.of(), -1, 0, 0, "视频文件读不到。");
        }
        long size = -1;
        try {
            size = Files.size(videoFile);
        } catch (IOException ignored) {
            // 拿不到大小就按能处理算，ffmpeg 自己会失败
        }
        if (size > maxFileBytes) {
            return new Result(List.of(), -1, 0, 0,
                    "视频超过 " + (maxFileBytes / 1024 / 1024) + "MB，太大了，这次没抽帧。");
        }

        double duration = probeDuration(videoFile);
        if (duration <= 0) {
            return new Result(List.of(), -1, 0, 0, "这个视频的时长读不出来（可能不是常见的 mp4/mov 格式）。");
        }
        double window = Math.min(duration, maxWindowSeconds);
        int target = targetFrames(duration);

        Path work = null;
        try {
            work = Files.createTempDirectory("va-frames-");
            List<double[]> times = new ArrayList<>();
            List<Path> shots = sceneFrames(videoFile, window, target, work, times);
            // 镜头不够就用"均匀时间点"补齐（避开已经取过的时刻，差 1.5 秒内算重复）
            if (shots.size() < target) {
                List<Double> wanted = uniformTimes(window, target);
                int index = 0;
                for (double at : wanted) {
                    if (shots.size() >= target) {
                        break;
                    }
                    if (near(times, at, Math.max(1.5d, window / (target * 3d)))) {
                        continue;
                    }
                    Path one = work.resolve(String.format(Locale.ROOT, "fill_%02d.jpg", index++));
                    if (stillAt(videoFile, at, one)) {
                        shots.add(one);
                        times.add(new double[]{at});
                    }
                }
            }
            if (shots.isEmpty()) {
                return new Result(List.of(), duration, window, target, "视频没能抽出可用画面。");
            }
            // 按时间排序后再编码，模型的"第 N 秒"才是递增的
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < shots.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingDouble(i -> times.get(i)[0]));
            List<Frame> frames = new ArrayList<>();
            for (int i : order) {
                String dataUrl = toDataUrl(shots.get(i));
                if (dataUrl != null) {
                    frames.add(new Frame(times.get(i)[0], dataUrl));
                }
            }
            String note = window < duration
                    ? "视频共 " + Math.round(duration) + " 秒，只分析了前 " + Math.round(window) + " 秒。"
                    : "";
            return new Result(List.copyOf(frames), duration, window, target, note);
        } catch (Exception e) {
            log.warn("抽帧失败 file={} error={}", videoFile.getFileName(), e.toString());
            return new Result(List.of(), duration, window, target, "抽帧失败：" + e.getMessage());
        } finally {
            deleteQuietly(work);
        }
    }

    /**
     * 把几张帧拼成**一张联系表**（网格图）。
     *
     * <p>为什么要它：工具返回只能带文字，能塞图片的通道（{@code addReadableMedia}）一次只有一张图。
     * 6~10 张单独发过去会变成一堆消息、上下文也乱；拼成一张 3×2 的图，模型一眼能看到整段视频的走向，
     * token 还比 6 张单图省一半以上。代价是每格变小——所以抽帧兜底只看"大概拍了什么"，不指望看清小字。
     */
    public String contactSheet(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        List<BufferedImage> images = new ArrayList<>();
        for (Frame frame : frames) {
            BufferedImage image = decode(frame.dataUrl());
            if (image != null) {
                images.add(image);
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        int count = images.size();
        int cols = count <= 4 ? 2 : 3;
        int rows = (count + cols - 1) / cols;
        int cellW = TILE_LONG_EDGE;
        int cellH = Math.round(cellW * 9f / 16f);
        int gap = 6;
        int width = cols * cellW + (cols + 1) * gap;
        int height = rows * cellH + (rows + 1) * gap;
        // 深色底：画面比例不一致时留边也好看，且不会和白色字幕混在一起
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new java.awt.Color(24, 24, 28));
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < count; i++) {
                BufferedImage image = images.get(i);
                int x = gap + (i % cols) * (cellW + gap);
                int y = gap + (i / cols) * (cellH + gap);
                double scale = Math.min((double) cellW / image.getWidth(), (double) cellH / image.getHeight());
                int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
                g.drawImage(image, x + (cellW - w) / 2, y + (cellH - h) / 2, w, h, null);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(sheet, "jpg", out);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            log.warn("拼联系表失败：{}", e.toString());
            return null;
        }
    }

    /** 下载一段视频到本地临文件。调用方负责删。 */
    public Path download(String url, Map<String, String> headers, long maxBytes, String suffix) throws IOException {
        // 先过公网地址校验（和文档下载同一套）：附件地址来自 QQ 事件，但下载是我们发起的，
        // 不能让它变成打内网的跳板（SSRF）。校验不过直接拒。
        if (urlValidator != null) {
            try {
                urlValidator.validate(url);
            } catch (RuntimeException rejected) {
                throw new IOException("视频地址不可用（" + rejected.getMessage() + "）");
            }
        }
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; WechatAgent/1.0)");
        if (headers != null) {
            headers.forEach(connection::setRequestProperty);
        }
        long limit = Math.min(maxBytes, maxFileBytes);
        Path target = Files.createTempFile("va-video-", suffix == null ? ".mp4" : suffix);
        try {
            connection.connect();
            if (connection.getResponseCode() >= 400) {
                throw new IOException("下载视频失败 HTTP " + connection.getResponseCode());
            }
            long declared = connection.getContentLengthLong();
            if (declared > limit) {
                throw new IOException("视频超过大小限制");
            }
            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new IOException("视频超过大小限制");
                    }
                    out.write(buffer, 0, read);
                }
            }
            return target;
        } catch (IOException e) {
            deleteQuietly(target);
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    /** 按策略该抽几帧（时长越短越少，避免一堆重复画面） */
    int targetFrames(double durationSeconds) {
        int target;
        if (durationSeconds <= 20) {
            target = 4;
        } else if (durationSeconds <= 60) {
            target = 5;
        } else if (durationSeconds <= 180) {
            target = 6;
        } else if (durationSeconds <= 600) {
            target = 8;
        } else {
            target = 10;
        }
        return Math.min(target, maxFrames);
    }

    // ------------------------------------------------------------------ 内部

    private double probeDuration(Path file) {
        List<String> out = run(List.of(ffprobe, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1", file.toAbsolutePath().toString()));
        for (String line : out) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                // 继续看下一行（ffprobe 偶尔把警告也打到 stdout）
            }
        }
        return -1;
    }

    /** 按镜头变化抽帧；同时把每一帧的时间点写进 {@code times} */
    private List<Path> sceneFrames(Path file, double window, int target, Path work, List<double[]> times) {
        Path dir = work.resolve("scene");
        List<Path> result = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            String filter = String.format(Locale.ROOT,
                    "select='gt(scene,%.2f)',showinfo,scale='min(%d,iw)':-2", sceneThreshold, TARGET_LONG_EDGE);
            List<String> out = run(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "info",
                    "-t", String.format(Locale.ROOT, "%.3f", window),
                    "-i", file.toAbsolutePath().toString(),
                    "-vf", filter, "-vsync", "vfr",
                    "-frames:v", String.valueOf(target), "-q:v", "4",
                    dir.resolve("f_%03d.jpg").toString()));
            List<Double> found = new ArrayList<>();
            for (String line : out) {
                Matcher m = PTS_TIME.matcher(line);
                if (m.find()) {
                    try {
                        found.add(Double.parseDouble(m.group(1)));
                    } catch (NumberFormatException ignored) {
                        // 忽略解析不了的行
                    }
                }
            }
            List<Path> files = listJpgs(dir);
            for (int i = 0; i < files.size(); i++) {
                double at = i < found.size() ? found.get(i) : (window * (i + 1) / (files.size() + 1));
                result.add(files.get(i));
                times.add(new double[]{at});
            }
            return result;
        } catch (Exception e) {
            log.info("按镜头抽帧失败（会退回均匀抽帧）：{}", e.toString());
            return result;
        }
    }

    /** 在指定时刻取一张画面（快进到 at 再抓一帧） */
    private boolean stillAt(Path file, double at, Path target) {
        try {
            List<String> out = run(List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
                    "-ss", String.format(Locale.ROOT, "%.3f", at),
                    "-i", file.toAbsolutePath().toString(),
                    "-frames:v", "1", "-vf", String.format(Locale.ROOT, "scale='min(%d,iw)':-2", TARGET_LONG_EDGE),
                    "-q:v", "4", target.toAbsolutePath().toString()));
            return out != null && Files.isRegularFile(target) && Files.size(target) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Double> uniformTimes(double window, int target) {
        List<Double> times = new ArrayList<>();
        for (int i = 1; i <= target; i++) {
            times.add(window * i / (target + 1d));
        }
        return times;
    }

    private boolean near(List<double[]> times, double at, double tolerance) {
        for (double[] item : times) {
            if (Math.abs(item[0] - at) < tolerance) {
                return true;
            }
        }
        return false;
    }

    private List<Path> listJpgs(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .toList();
        }
    }

    private String toDataUrl(Path image) {
        try {
            byte[] bytes = Files.readAllBytes(image);
            if (bytes.length == 0) {
                return null;
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    private BufferedImage decode(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /** 跑一个子进程，返回它打到 stdout+stderr 的每一行；超时就强杀 */
    private List<String> run(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            process = builder.start();
            List<String> lines = new ArrayList<>();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lines.size() < 4000) {
                        lines.add(line);
                    }
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg 超时（>" + timeoutSeconds + " 秒）");
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException("调用 " + command.get(0) + " 失败（容器里装了吗）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("抽帧被中断", e);
        }
    }

    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录删不掉不影响功能
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 给调用方拼一段"这些画面分别在第几秒"的说明 */
    public static String describeTimes(List<Frame> frames, int maxItems) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Map<Integer, String> parts = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(frames.size(), Math.max(1, maxItems)); i++) {
            parts.put(i + 1, Math.round(frames.get(i).second()) + "秒");
        }
        int index = 0;
        for (String value : parts.values()) {
            if (index++ > 0) {
                sb.append("、");
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
