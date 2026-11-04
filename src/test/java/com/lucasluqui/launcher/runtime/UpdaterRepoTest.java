package com.lucasluqui.launcher.runtime;

import com.lucasluqui.launcher.LauncherGlobals;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Java 8 self-updater hardcodes its URLs; they must stay on this fork, never on upstream. */
public class UpdaterRepoTest {

    private static String updaterConstant(String name) throws Exception {
        Field field = Updater.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    public void updaterUsesThisRepository() throws Exception {
        assertEquals(LauncherGlobals.GITHUB_URL_REPO, updaterConstant("GITHUB_URL_REPO"));
        assertEquals("https://api.github.com/repos/" + LauncherGlobals.GITHUB_AUTHOR + "/"
            + LauncherGlobals.GITHUB_REPO + "/releases/latest", updaterConstant("GITHUB_API_LATEST_RELEASE"));
    }
}
