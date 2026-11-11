package com.lucasluqui.launcher.server;

import com.lucasluqui.launcher.server.ui.ServerSettingsDialog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every label, description, button and message of the server settings window needs a string in every language. */
public class ServerSettingsLocaleTest {
    private static final String[] LOCALES = {
        "ar", "de", "en", "es", "et-ee", "fr", "it", "jp", "pl", "pt-br", "ru", "zh-hans", "zh-hant"
    };
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)}");

    private Properties load(String lang) throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/rsrc/lang/lang_" + lang + ".properties")) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    @Test
    public void allLocalesHaveEveryKeyTheWindowUses() throws IOException {
        for (String lang : LOCALES) {
            Properties props = load(lang);
            List<String> missing = new ArrayList<>();
            for (String key : ServerSettingsDialog.requiredLocaleKeys()) {
                String value = props.getProperty(key);
                if (value == null || value.isEmpty()) missing.add(key);
            }
            if (props.getProperty("b.server") == null) missing.add("b.server");
            assertTrue(missing.isEmpty(), "missing in lang_" + lang + ".properties: " + missing);
        }
    }

    /** LocaleManager drops the first and last character of a value, which are the quotes. */
    @Test
    public void everyValueIsQuotedOnOneLine() throws IOException {
        for (String lang : LOCALES) {
            Properties props = load(lang);
            List<String> broken = new ArrayList<>();
            for (String key : ServerSettingsDialog.requiredLocaleKeys()) {
                String value = props.getProperty(key);
                if (value != null && !(value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))) broken.add(key);
            }
            assertTrue(broken.isEmpty(), "not quoted in lang_" + lang + ".properties: " + broken);
            // A value that spills onto a second physical line leaves a stray key behind ("This also removes ...").
            assertFalse(props.containsKey("This"), "stray continuation line in lang_" + lang + ".properties");
        }
    }

    /** With arguments the value goes through MessageFormat: every placeholder must come out, and no apostrophe may eat text. */
    @Test
    public void everyMessageWithPlaceholdersFormatsInEveryLanguage() throws IOException {
        Properties english = load("en");
        for (String lang : LOCALES) {
            Properties props = load(lang);
            for (String key : ServerSettingsDialog.requiredLocaleKeys()) {
                String englishValue = english.getProperty(key);
                if (englishValue == null || !PLACEHOLDER.matcher(englishValue).find()) continue;
                String value = props.getProperty(key);
                Object[] args = {"A0", "A1", "A2"};
                String formatted = MessageFormat.format(value, args);
                Matcher placeholders = PLACEHOLDER.matcher(englishValue);
                while (placeholders.find()) {
                    String arg = "A" + placeholders.group(1);
                    assertTrue(formatted.contains(arg), "lang_" + lang + " " + key + " lost " + arg + ": " + formatted);
                }
                assertFalse(PLACEHOLDER.matcher(formatted).find(), "lang_" + lang + " " + key + " kept a raw placeholder: " + formatted);
                assertEquals(false, formatted.contains("''"), "lang_" + lang + " " + key + " has a doubled apostrophe after formatting");
            }
        }
    }
}
