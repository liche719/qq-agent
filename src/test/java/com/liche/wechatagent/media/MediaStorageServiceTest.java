package com.liche.wechatagent.media;

import com.liche.wechatagent.network.PublicUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaStorageServiceTest {

    @TempDir
    Path storageRoot;

    private final List<StoredMedia> database = new ArrayList<>();
    private MediaStorageService service;

    @BeforeEach
    void setUp() {
        StoredMediaRepository repository = mock(StoredMediaRepository.class);
        AtomicLong ids = new AtomicLong();
        when(repository.save(any(StoredMedia.class))).thenAnswer(invocation -> {
            StoredMedia media = invocation.getArgument(0);
            if (media.getId() == null) {
                media.setId(ids.incrementAndGet());
                database.add(media);
            }
            return media;
        });
        when(repository.findFirstByUserIdAndSha256AndStatus(any(), any(), any())).thenAnswer(invocation ->
                database.stream().filter(media -> media.getUserId().equals(invocation.getArgument(0))
                                && media.getSha256().equals(invocation.getArgument(1))
                                && media.getStatus().equals(invocation.getArgument(2)))
                        .findFirst());
        when(repository.findByIdAndUserIdAndStatus(any(), any(), any())).thenAnswer(invocation ->
                database.stream().filter(media -> media.getId().equals(invocation.getArgument(0))
                                && media.getUserId().equals(invocation.getArgument(1))
                                && media.getStatus().equals(invocation.getArgument(2)))
                        .findFirst());
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc(any(), any())).thenAnswer(invocation ->
                database.stream().filter(media -> media.getUserId().equals(invocation.getArgument(0))
                                && media.getStatus().equals(invocation.getArgument(1)))
                        .toList());
        service = new MediaStorageService(repository, mock(PublicUrlValidator.class),
                storageRoot.toString(), 1024 * 1024, 5);
    }

    @Test
    void storesUsersInSeparateDirectoriesAndPreventsPathTraversal() throws Exception {
        service.save("qq:user/A", "m1", image(1, "data:image/png;base64,aW1hZ2UtYQ=="),
                "../../南京理工课表.exe", "南京理工备考课程安排", "未来一年复习规划需要长期使用");
        service.save("qq:user:B", "m2", image(1, "data:image/png;base64,aW1hZ2UtYg=="),
                "南京理工课表", "另一个用户的课程安排", "未来一年复习规划需要长期使用");

        StoredMedia first = database.get(0);
        StoredMedia second = database.get(1);
        assertTrue(first.getFileName().endsWith(".png"));
        assertFalse(first.getFileName().contains("/"));
        assertFalse(first.getFileName().contains("\\"));
        assertNotEquals(Path.of(first.getRelativePath()).getParent().getParent(),
                Path.of(second.getRelativePath()).getParent().getParent());
        assertTrue(Files.isRegularFile(storageRoot.resolve(first.getRelativePath())));
        assertTrue(storageRoot.resolve(first.getRelativePath()).normalize().startsWith(storageRoot));
    }

    @Test
    void deduplicatesOnlyWithinTheSameUser() {
        MediaCandidate candidate = image(1, "data:image/png;base64,c2FtZQ==");
        service.save("u1", "m1", candidate, "课表", "本学期完整课程安排", "每周都需要查询课程安排");
        String duplicate = service.save("u1", "m2", candidate, "重复课表", "本学期完整课程安排", "每周都需要查询课程安排");
        service.save("u2", "m3", candidate, "课表", "另一个用户的课程安排", "每周都需要查询课程安排");

        assertTrue(duplicate.contains("已经保存过"));
        assertEquals(2, database.size());
    }

    @Test
    void refusesCrossUserInspectionAndRequiresInspectionBeforeTrash() throws Exception {
        service.save("u1", "m1", image(1, "data:image/png;base64,c2NoZWR1bGU="),
                "考研课表", "南京理工考研长期课表", "明年考试前会持续使用");
        StoredMedia media = database.get(0);

        assertThrows(IllegalArgumentException.class, () -> service.inspect("u2", media.getId()));
        assertThrows(IllegalStateException.class,
                () -> service.trash("u1", media.getId(), "not-inspected", "用户资料已经明确失效"));
        assertEquals(StoredMedia.ACTIVE, media.getStatus());

        String inspection = service.inspect("u1", media.getId());
        String token = inspection.substring(inspection.indexOf("inspectionToken=") + "inspectionToken=".length())
                .substring(0, 36);
        String result = service.trash("u1", media.getId(), token, "课程安排已经明确失效并被新版本替代");

        assertTrue(result.contains("未永久删除"));
        assertEquals(StoredMedia.TRASHED, media.getStatus());
        assertEquals("课程安排已经明确失效并被新版本替代", media.getTrashReason());
        assertTrue(media.getTrashedAt() != null);
        assertTrue(media.getRelativePath().contains(".trash"));
        assertTrue(Files.isRegularFile(storageRoot.resolve(media.getRelativePath())));
    }

    @Test
    void readsOwnedStoredImageBackAsMultimodalData() {
        service.save("u1", "m1", image(1, "data:image/png;base64,c2NoZWR1bGU="),
                "第13周课表", "第13周课程安排", "之后查询第13周课程需要原图");
        StoredMedia media = database.get(0);

        MediaStorageService.ReadOutcome result = service.readForAssistant("u1", media.getId());

        assertTrue(result.description().contains("第13周课表"));
        assertTrue(result.imageDataUrl().startsWith("data:image/png;base64,"));
        assertThrows(IllegalArgumentException.class, () -> service.readForAssistant("u2", media.getId()));
    }

    private MediaCandidate image(int index, String source) {
        return new MediaCandidate(index, "schedule.png", "image/png", source, "", true);
    }
}
