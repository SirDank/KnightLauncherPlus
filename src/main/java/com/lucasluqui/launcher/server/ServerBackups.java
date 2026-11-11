package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backups of the server's state: the whole {@code data/} folder (database with its WAL, keys, logs) and
 * {@code config/}, copied together while the server is stopped (a database copied under a running server can be torn).
 * They live in {@code backups/<timestamp>[-label]} beside {@code data/}. The server must not be running for any
 * operation here; the caller stops it first.
 */
public final class ServerBackups {
    /** How many backups are kept; older ones are deleted when a new one is made. */
    public static final int KEEP = 10;
    private static final String LOCK = "server.lock";

    public record Backup(String name, long time, long bytes) {}

    private ServerBackups() {}

    public static Path backupsDir(Path serverDir) {
        return serverDir.resolve("backups");
    }

    /** Copies data/ and config/ into a new backup and prunes the oldest beyond {@link #KEEP}. */
    public static Backup create(Path serverDir, String label) throws IOException {
        Path target = newBackupDir(serverDir, label);
        try {
            copyTree(serverDir.resolve("data"), target.resolve("data"));
            copyTree(serverDir.resolve("config"), target.resolve("config"));
        } catch (IOException | RuntimeException e) {
            deleteTree(target);
            throw e;
        }
        prune(serverDir);
        return describe(target);
    }

    /**
     * Retires the current data/ into a backup (a move, so it is instant) and leaves an empty data/ for a fresh start.
     * With {@code keepKeys} the server's RSA key pair is put back, so players who configured its public key can still
     * connect; without it the server makes a new pair and every player has to be given the new key.
     */
    public static Backup reset(Path serverDir, boolean keepKeys) throws IOException {
        Path data = serverDir.resolve("data");
        Path target = newBackupDir(serverDir, "reset");
        if (Files.isDirectory(data)) {
            Files.move(data, target.resolve("data"), StandardCopyOption.REPLACE_EXISTING);
            if (keepKeys && Files.isDirectory(target.resolve("data/keys"))) {
                copyTree(target.resolve("data/keys"), data.resolve("keys"));
            }
        }
        prune(serverDir);
        return describe(target);
    }

    /**
     * Puts a backup's data/ and config/ back. The current state is backed up first ("before-restore"), and put back if
     * the restore fails halfway.
     */
    public static void restore(Path serverDir, String name) throws IOException {
        Path source = backupsDir(serverDir).resolve(name).normalize();
        if (!source.startsWith(backupsDir(serverDir).normalize()) || !Files.isDirectory(source.resolve("data"))) {
            throw new IOException("no such backup: " + name);
        }
        Backup safety = create(serverDir, "before-restore");
        Path safetyDir = backupsDir(serverDir).resolve(safety.name());
        try {
            replace(source.resolve("data"), serverDir.resolve("data"));
            if (Files.isDirectory(source.resolve("config"))) replace(source.resolve("config"), serverDir.resolve("config"));
        } catch (IOException | RuntimeException e) {
            replace(safetyDir.resolve("data"), serverDir.resolve("data"));
            if (Files.isDirectory(safetyDir.resolve("config"))) replace(safetyDir.resolve("config"), serverDir.resolve("config"));
            throw e;
        }
    }

    /** Newest first. */
    public static List<Backup> list(Path serverDir) throws IOException {
        List<Backup> backups = new ArrayList<>();
        Path dir = backupsDir(serverDir);
        if (!Files.isDirectory(dir)) return backups;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (Files.isDirectory(child.resolve("data"))) backups.add(describe(child));
            }
        }
        backups.sort(Comparator.comparingLong(Backup::time).reversed().thenComparing(Backup::name, Comparator.reverseOrder()));
        return backups;
    }

    public static void delete(Path serverDir, String name) throws IOException {
        Path target = backupsDir(serverDir).resolve(name).normalize();
        if (!target.startsWith(backupsDir(serverDir).normalize()) || target.equals(backupsDir(serverDir).normalize())) {
            throw new IOException("no such backup: " + name);
        }
        deleteTree(target);
    }

    private static Path newBackupDir(Path serverDir, String label) throws IOException {
        String base = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + (label == null ? "" : "-" + label);
        Path dir = backupsDir(serverDir).resolve(base);
        for (int i = 2; Files.exists(dir); i++) dir = backupsDir(serverDir).resolve(base + "_" + i);
        Files.createDirectories(dir);
        return dir;
    }

    private static Backup describe(Path dir) throws IOException {
        long[] bytes = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                bytes[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return new Backup(dir.getFileName().toString(), Files.getLastModifiedTime(dir).toMillis(), bytes[0]);
    }

    private static void prune(Path serverDir) throws IOException {
        List<Backup> all = list(serverDir);
        for (int i = KEEP; i < all.size(); i++) deleteTree(backupsDir(serverDir).resolve(all.get(i).name()));
    }

    /** Replaces {@code target} with a copy of {@code source}, keeping the old target until the copy is complete. */
    private static void replace(Path source, Path target) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + ".restoring");
        Path old = target.resolveSibling(target.getFileName() + ".replaced");
        deleteTree(staging);
        deleteTree(old);
        copyTree(source, staging);
        if (Files.exists(target)) Files.move(target, old);
        try {
            Files.move(staging, target);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(old)) Files.move(old, target);
            throw e;
        }
        deleteTree(old);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.equals(LOCK) || name.endsWith(".tmp")) return FileVisitResult.CONTINUE;
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
