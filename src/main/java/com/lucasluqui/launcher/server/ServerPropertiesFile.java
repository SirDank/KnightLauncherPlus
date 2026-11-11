package com.lucasluqui.launcher.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads and writes Tortodrone configuration files (server.yml and combat.yml). Files are written as UTF-8 through a
 * temporary file, so a crash never leaves a half-written configuration behind.
 */
public final class ServerPropertiesFile {

    private ServerPropertiesFile() {}

    /** The server runs in {@code KnightLauncher/server}, so this is the file it reads. */
    public static File resolve(File gameDir) {
        return new File(gameDir, "KnightLauncher/server/config/server.yml");
    }

    /**
     * The towns the server can start in: the folders under {@code content/maps} whose map.yml says {@code type: town}.
     * (Tortodrone refuses any other kind of map as the entry map.) Empty if the content pack is not installed yet.
     */
    public static List<String> townMaps(File serverDir) {
        List<String> towns = new ArrayList<>();
        File[] maps = new File(serverDir, "content/maps").listFiles(File::isDirectory);
        if (maps == null) return towns;
        for (File map : maps) {
            if (!ServerSettings.MAP_NAME.matcher(map.getName()).matches()) continue;
            File yml = new File(map, "map.yml");
            if (!yml.isFile()) continue;
            try (BufferedReader in = Files.newBufferedReader(yml.toPath(), StandardCharsets.UTF_8)) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("type:")) {
                        if (line.substring(5).trim().equals("town")) towns.add(map.getName());
                        break;
                    }
                }
            } catch (IOException ignored) {
                // An unreadable map is not offered.
            }
        }
        Collections.sort(towns);
        return towns;
    }

    /** The settings the launcher shows, from server.yml and combat.yml (missing files and keys are skipped). */
    public static Map<String, String> read(File serverYml) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, Object> server = load(serverYml);
        copy(server, "max-clients", result, "server.maxClients");
        copy(server, "welcome-message", result, "server.welcomeMessage");
        copy(server, "startup-map", result, "server.startupMap");
        copy(server, "log-mode", result, "server.logMode");
        copy(server, "log-max-mib", result, "server.logMaxMib");
        copy(server, "log-retention", result, "server.logRetention");
        if (server.containsKey("server-ip")) {
            result.put("server.allowLan", String.valueOf("0.0.0.0".equals(String.valueOf(server.get("server-ip")))));
        }
        if (serverYml != null) {
            copy(load(new File(serverYml.getParentFile(), "combat.yml")), "dodgeCooldownMs", result, "combat.dodgeCooldownMs");
        }
        return result;
    }

    public static void write(File serverYml, Map<String, String> values) throws IOException {
        Map<String, Object> server = load(serverYml);
        putInt(server, "max-clients", values.get("server.maxClients"));
        putText(server, "welcome-message", values.get("server.welcomeMessage"));
        putText(server, "startup-map", values.get("server.startupMap"));
        putText(server, "log-mode", values.get("server.logMode"));
        putInt(server, "log-max-mib", values.get("server.logMaxMib"));
        putInt(server, "log-retention", values.get("server.logRetention"));
        if (values.containsKey("server.allowLan")) {
            server.put("server-ip", Boolean.parseBoolean(values.get("server.allowLan")) ? "0.0.0.0" : "127.0.0.1");
        }
        keepLauncherManaged(server);
        dump(serverYml, server);

        if (values.containsKey("combat.dodgeCooldownMs")) {
            File combatYml = new File(serverYml.getParentFile(), "combat.yml");
            Map<String, Object> combat = load(combatYml);
            putInt(combat, "dodgeCooldownMs", values.get("combat.dodgeCooldownMs"));
            dump(combatYml, combat);
        }
    }

    /** What the launcher decides on every start: where the server listens (the port and LAN choice live in the launcher). */
    public static void applyRuntime(File serverYml, int port, boolean allowLan) throws IOException {
        Map<String, Object> server = load(serverYml);
        server.put("server-port", port);
        server.put("server-ip", allowLan ? "0.0.0.0" : "127.0.0.1");
        keepLauncherManaged(server);
        dump(serverYml, server);
    }

    /** No web UI, and the news listener never on 8080, which Steam's web helper owns (0 lets the OS pick a free port). */
    private static void keepLauncherManaged(Map<String, Object> server) {
        server.put("webui-enabled", false);
        Object httpPort = server.get("webui/content_url-port");
        if (httpPort == null || "8080".equals(String.valueOf(httpPort))) {
            server.put("webui/content_url-port", 0);
        }
    }

    /** Rewrites the file without its comments (and in the launcher's own layout), keeping every setting. */
    public static void stripComments(File yml) throws IOException {
        dump(yml, load(yml));
    }

    private static Map<String, Object> load(File file) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        if (file != null && file.isFile()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
                        map.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } catch (RuntimeException e) { // malformed YAML
                throw new IOException(file.getName() + " is not valid YAML: " + e.getMessage(), e);
            }
        }
        return map;
    }

    private static void dump(File file, Map<String, Object> map) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        Files.createDirectories(dir.toPath());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        File temp = new File(dir, file.getName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) {
            new Yaml(options).dump(map, out);
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copy(Map<String, Object> from, String yamlKey, Map<String, String> to, String key) {
        if (from.containsKey(yamlKey)) to.put(key, String.valueOf(from.get(yamlKey)));
    }

    private static void putText(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private static void putInt(Map<String, Object> map, String key, String value) {
        if (value == null) return;
        try {
            map.put(key, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            // Validation happens before saving; a bad number leaves the old value.
        }
    }
}
