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
    public void allLocalesHaveEveryKeyTheWindowUses() throws IOException {
        String[] locales = {
            "ar", "de", "en", "es", "et-ee", "fr", "it", "jp", "pl", "pt-br", "ru", "zh-hans", "zh-hant"
        };
        for (String lang : locales) {
            Properties props = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/rsrc/lang/lang_" + lang + ".properties")) {
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            List<String> missing = new ArrayList<>();
            for (String key : ServerSettingsDialog.requiredLocaleKeys()) {
                String value = props.getProperty(key);
                if (value == null || value.isEmpty()) missing.add(key);
            }
            if (props.getProperty("b.server") == null) missing.add("b.server");
            assertTrue(missing.isEmpty(), "missing in lang_" + lang + ".properties: " + missing);
        }
    }
}
