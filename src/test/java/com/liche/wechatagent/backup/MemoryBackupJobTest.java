package com.liche.wechatagent.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.wechatagent.media.StoredMediaRepository;
import com.liche.wechatagent.memory.MemoryArchiveRepository;
import com.liche.wechatagent.memory.MemoryChangeLogRepository;
import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import com.liche.wechatagent.reminder.ReminderTaskRepository;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryBackupJobTest {

    @TempDir
    Path tempDirectory;

    @Test
    void usesHashedUserDirectoriesAndWritesARecoverableStateFile() throws Exception {
        String userId = "qq:../../private-user";
        UserProfile profile = new UserProfile(userId, "陪伴助手");
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        UserCoreMemoryRepository cores = mock(UserCoreMemoryRepository.class);
        UserWorkMemoryRepository work = mock(UserWorkMemoryRepository.class);
        MemoryArchiveRepository archives = mock(MemoryArchiveRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        ReminderTaskRepository reminders = mock(ReminderTaskRepository.class);
        StoredMediaRepository media = mock(StoredMediaRepository.class);
        when(profiles.findAll()).thenReturn(List.of(profile));
        when(profiles.findById(userId)).thenReturn(Optional.of(profile));
        when(cores.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());
        when(work.findByUserIdAndArchivedFalse(userId)).thenReturn(List.of());
        when(archives.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(changeLogs.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(reminders.findByUserIdAndStatus(userId, "PENDING")).thenReturn(List.of());
        when(media.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());

        Path backupRoot = tempDirectory.resolve("backup");
        MemoryBackupJob job = new MemoryBackupJob(profiles, cores, work, archives, changeLogs, reminders, media,
                new ObjectMapper().findAndRegisterModules(), backupRoot.toString(), 30,
                tempDirectory.resolve("stored-media").toString());

        job.backupAll();

        Path dayDirectory = backupRoot.resolve(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        Path userDirectory;
        try (var paths = Files.list(dayDirectory)) {
            userDirectory = paths.findFirst().orElseThrow();
        }
        assertTrue(userDirectory.getFileName().toString().matches("user-[0-9a-f]{12}"));
        assertFalse(userDirectory.getFileName().toString().contains("private-user"));
        String state = Files.readString(userDirectory.resolve("state.json"));
        assertTrue(state.contains(userId));
        assertTrue(state.contains("mediaArtifacts"));
    }
}
