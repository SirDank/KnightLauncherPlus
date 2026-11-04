package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The server copies the scenes the game client cached, so players only have to visit them once. */
public class SceneImportTest {

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path dir, int sceneId) throws IOException {
        return Files.readString(dir.resolve(SceneManager.SCENES_DIR + "/" + sceneId), StandardCharsets.UTF_8);
    }

    @Test
    public void copiesCachedScenesIntoTheServerFolder(@TempDir Path game) throws IOException {
        write(game.resolve("scenes/1"), "haven");
        write(game.resolve("scenes/294"), "training");

        assertEquals(2, SceneManager.importClientScenes(game.toFile()));
        assertEquals("haven", read(game, 1));
        assertEquals("training", read(game, 294));
    }

    @Test
    public void keepsExistingCopiesAndIgnoresMissingScenes(@TempDir Path game) throws IOException {
        write(game.resolve(SceneManager.SCENES_DIR + "/1"), "mine");
        write(game.resolve("scenes/1"), "newer from the client");

        assertEquals(0, SceneManager.importClientScenes(game.toFile()), "scene 294 is nowhere, scene 1 is already there");
        assertEquals("mine", read(game, 1));
        assertEquals(false, new File(game.toFile(), SceneManager.SCENES_DIR + "/294").exists());
    }
}
