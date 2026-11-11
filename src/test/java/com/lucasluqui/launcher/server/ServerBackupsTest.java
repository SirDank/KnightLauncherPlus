package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerBackupsTest {
    @TempDir Path server;

    private void populate(String db) throws IOException {
        Files.createDirectories(server.resolve("data/keys"));
        Files.createDirectories(server.resolve("config"));
        Files.writeString(server.resolve("data/tortodrone.db"), db);
        Files.writeString(server.resolve("data/tortodrone.db-wal"), "wal-" + db);
        Files.writeString(server.resolve("data/server.lock"), "");
        Files.writeString(server.resolve("data/keys/public.txt"), "KEY");
        Files.writeString(server.resolve("config/server.yml"), "max-clients: 16\n");
    }

    @Test
    public void aBackupHoldsDataAndConfigButNotTheLockFile() throws IOException {
        populate("one");
        ServerBackups.Backup backup = ServerBackups.create(server, null);
        Path dir = ServerBackups.backupsDir(server).resolve(backup.name());
        assertEquals("one", Files.readString(dir.resolve("data/tortodrone.db")));
        assertEquals("wal-one", Files.readString(dir.resolve("data/tortodrone.db-wal")));
        assertEquals("KEY", Files.readString(dir.resolve("data/keys/public.txt")));
        assertTrue(Files.exists(dir.resolve("config/server.yml")));
        assertFalse(Files.exists(dir.resolve("data/server.lock")));
        assertTrue(backup.bytes() > 0);
        assertEquals(1, ServerBackups.list(server).size());
    }

    @Test
    public void restoreBringsBackTheOldStateAndKeepsAnUndoPoint() throws IOException {
        populate("one");
        ServerBackups.Backup backup = ServerBackups.create(server, null);
        Files.writeString(server.resolve("data/tortodrone.db"), "two");
        Files.writeString(server.resolve("config/server.yml"), "max-clients: 99\n");

        ServerBackups.restore(server, backup.name());

        assertEquals("one", Files.readString(server.resolve("data/tortodrone.db")));
        assertEquals("max-clients: 16\n", Files.readString(server.resolve("config/server.yml")));
        List<ServerBackups.Backup> all = ServerBackups.list(server);
        assertTrue(all.stream().anyMatch(b -> b.name().endsWith("before-restore")), "no undo point: " + all);
        String undo = all.stream().filter(b -> b.name().endsWith("before-restore")).findFirst().orElseThrow().name();
        assertEquals("two", Files.readString(ServerBackups.backupsDir(server).resolve(undo).resolve("data/tortodrone.db")));
        assertFalse(Files.exists(server.resolve("data.restoring")));
        assertFalse(Files.exists(server.resolve("data.replaced")));
    }

    @Test
    public void resetMovesDataAsideAndKeepsTheKeysWhenAsked() throws IOException {
        populate("one");
        ServerBackups.reset(server, true);
        assertFalse(Files.exists(server.resolve("data/tortodrone.db")));
        assertEquals("KEY", Files.readString(server.resolve("data/keys/public.txt")));
        assertEquals(1, ServerBackups.list(server).size());
        assertTrue(ServerBackups.list(server).get(0).name().endsWith("reset"));
    }

    @Test
    public void resetWithoutKeepingKeysLeavesNoKeys() throws IOException {
        populate("one");
        ServerBackups.reset(server, false);
        assertFalse(Files.exists(server.resolve("data/keys")));
    }

    @Test
    public void onlyTheNewestBackupsAreKept() throws IOException {
        populate("one");
        for (int i = 0; i < ServerBackups.KEEP + 3; i++) ServerBackups.create(server, "n" + i);
        assertEquals(ServerBackups.KEEP, ServerBackups.list(server).size());
    }

    @Test
    public void namesThatClimbOutOfTheBackupsFolderAreRefused() throws IOException {
        populate("one");
        assertThrows(IOException.class, () -> ServerBackups.restore(server, "../data"));
        assertThrows(IOException.class, () -> ServerBackups.delete(server, ".."));
        assertThrows(IOException.class, () -> ServerBackups.delete(server, "."));
        assertTrue(Files.exists(server.resolve("data/tortodrone.db")));
    }

    @Test
    public void deleteRemovesOneBackup() throws IOException {
        populate("one");
        ServerBackups.Backup backup = ServerBackups.create(server, null);
        ServerBackups.delete(server, backup.name());
        assertTrue(ServerBackups.list(server).isEmpty());
    }
}
