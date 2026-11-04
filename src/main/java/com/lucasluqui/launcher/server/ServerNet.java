package com.lucasluqui.launcher.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Server constants and network helpers that need no game classes, so the launcher can use them
 * without loading {@link SpiralServer} (which only runs in its own JVM next to the game jars).
 */
public final class ServerNet {
    public static final int DEFAULT_PORT = 47624;
    public static final String LOOPBACK = "127.0.0.1";
    /**
     * The server process is started in the game directory, so this is where its files are.
     * {@code -Dsk.gameDir=...} overrides it, e.g. to run the tests against an installed game.
     */
    public static final String GAME_DIR = System.getProperty("sk.gameDir", System.getProperty("user.dir"));

    /** Prefixes of the lines the server process prints for the launcher to parse. */
    public static final String READY = "[READY] ";
    public static final String FATAL = "[FATAL] ";
    public static final String PLAYERS = "[PLAYERS] ";

    private ServerNet() {}

    public static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /** Non-loopback IPv4 addresses of this machine, comma separated; loopback if there are none. */
    public static String findClientAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> members = network.getInetAddresses();
                while (members.hasMoreElements()) {
                    InetAddress address = members.nextElement();
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException error) {
            return LOOPBACK;
        }
        return addresses.isEmpty() ? LOOPBACK : String.join(", ", addresses);
    }
}
