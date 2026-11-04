package com.lucasluqui.launcher.server;

import com.lucasluqui.launcher.server.ui.ServerSettingsDialog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every label, description and button of the server settings window needs an English string. */
public class ServerSettingsLocaleTest {

    @Test
    public void englishHasEveryKeyTheWindowUses() throws IOException {
        Properties english = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/rsrc/lang/lang_en.properties")) {
            english.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        for (String key : ServerSettingsDialog.requiredLocaleKeys()) {
            String value = english.getProperty(key);
            if (value == null || value.length() < 3) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "missing in lang_en.properties: " + missing);
    }
}
