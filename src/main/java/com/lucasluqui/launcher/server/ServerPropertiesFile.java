package com.lucasluqui.launcher.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads and writes Tortodrone configuration files (server.yml and combat.yml).
 */
public final class ServerPropertiesFile {

    private ServerPropertiesFile() {}

    public static File resolve(File gameDir) {
        File preferred = new File(gameDir, "KnightLauncher/server/config/server.yml");
        File fallback = new File(gameDir, "config/server.yml");
        return !preferred.isFile() && fallback.isFile() ? fallback : preferred;
    }

    public static Map<String, String> read(File serverYml) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (serverYml != null && serverYml.isFile()) {
            Yaml yaml = new Yaml();
            try (InputStream in = new FileInputStream(serverYml)) {
                Object loaded = yaml.load(in);
                if (loaded instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) loaded;
                    if (map.containsKey("max-clients")) {
                        result.put("server.maxClients", String.valueOf(map.get("max-clients")));
                    }
                    if (map.containsKey("welcome-message")) {
                        result.put("server.welcomeMessage", String.valueOf(map.get("welcome-message")));
                    }
                    if (map.containsKey("startup-map")) {
                        result.put("server.startupMap", String.valueOf(map.get("startup-map")));
                    }
                    if (map.containsKey("log-mode")) {
                        result.put("server.logMode", String.valueOf(map.get("log-mode")));
                    }
                    if (map.containsKey("server-ip")) {
                        String ip = String.valueOf(map.get("server-ip"));
                        result.put("server.allowLan", String.valueOf("0.0.0.0".equals(ip)));
                    }
                }
            }

            File combatYml = new File(serverYml.getParentFile(), "combat.yml");
            if (combatYml.isFile()) {
                try (InputStream in = new FileInputStream(combatYml)) {
                    Object loaded = yaml.load(in);
                    if (loaded instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) loaded;
                        if (map.containsKey("dodgeCooldownMs")) {
                            result.put("combat.dodgeCooldownMs", String.valueOf(map.get("dodgeCooldownMs")));
                        }
                    }
                }
            }
        }
        return result;
    }

    public static void write(File serverYml, Map<String, String> values) throws IOException {
        if (serverYml.getParentFile() != null) {
            serverYml.getParentFile().mkdirs();
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        Map<String, Object> serverMap = new LinkedHashMap<>();
        if (serverYml.isFile()) {
            try (InputStream in = new FileInputStream(serverYml)) {
                Object loaded = yaml.load(in);
                if (loaded instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) loaded;
                    serverMap.putAll(map);
                }
            }
        }

        if (values.containsKey("server.maxClients")) {
            try {
                serverMap.put("max-clients", Integer.parseInt(values.get("server.maxClients")));
            } catch (NumberFormatException ignored) {}
        }
        if (values.containsKey("server.welcomeMessage")) {
            serverMap.put("welcome-message", values.get("server.welcomeMessage"));
        }
        if (values.containsKey("server.startupMap")) {
            serverMap.put("startup-map", values.get("server.startupMap"));
        }
        if (values.containsKey("server.logMode")) {
            serverMap.put("log-mode", values.get("server.logMode"));
        }
        if (values.containsKey("server.allowLan")) {
            boolean lan = Boolean.parseBoolean(values.get("server.allowLan"));
            serverMap.put("server-ip", lan ? "0.0.0.0" : "127.0.0.1");
        }
        serverMap.put("webui-enabled", false);
        Object httpPort = serverMap.get("webui/content_url-port");
        if (httpPort == null || Integer.valueOf(8080).equals(httpPort) || "8080".equals(String.valueOf(httpPort))) {
            serverMap.put("webui/content_url-port", 0);
        }

        try (FileWriter writer = new FileWriter(serverYml)) {
            yaml.dump(serverMap, writer);
        }

        if (values.containsKey("combat.dodgeCooldownMs")) {
            File combatYml = new File(serverYml.getParentFile(), "combat.yml");
            Map<String, Object> combatMap = new LinkedHashMap<>();
            if (combatYml.isFile()) {
                try (InputStream in = new FileInputStream(combatYml)) {
                    Object loaded = yaml.load(in);
                    if (loaded instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) loaded;
                        combatMap.putAll(map);
                    }
                }
            }
            try {
                combatMap.put("dodgeCooldownMs", Integer.parseInt(values.get("combat.dodgeCooldownMs")));
            } catch (NumberFormatException ignored) {}
            try (FileWriter writer = new FileWriter(combatYml)) {
                yaml.dump(combatMap, writer);
            }
        }
    }
}
