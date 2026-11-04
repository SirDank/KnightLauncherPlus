package com.lucasluqui.launcher.server;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Session ids handed out at sign-in. After a successful login the game replaces the password it keeps with this id,
 * and reconnects with it, so the server must recognise it. Ids live in memory only and expire.
 */
public final class SessionTokens {
    static final long LIFETIME_MS = 12L * 60 * 60 * 1000;
    static final int MAX_TOKENS = 1_000;

    private static final class Entry {
        final long accountId;
        final String username;
        final long expires;

        Entry(long accountId, String username, long expires) {
            this.accountId = accountId;
            this.username = username;
            this.expires = expires;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public SessionTokens() {
        this(System::currentTimeMillis);
    }

    SessionTokens(LongSupplier clock) {
        this.clock = clock;
    }

    /** A new unguessable id for the account. */
    public String issue(long accountId, String username) {
        long now = clock.getAsLong();
        if (tokens.size() >= MAX_TOKENS) {
            tokens.values().removeIf(entry -> entry.expires <= now);
            if (tokens.size() >= MAX_TOKENS) tokens.clear(); // a runaway client must not grow memory without bound
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder id = new StringBuilder(32);
        for (byte b : bytes) id.append(String.format("%02x", b));
        String token = id.toString();
        tokens.put(token, new Entry(accountId, username, now + LIFETIME_MS));
        return token;
    }

    /** The account id for a live token that was issued to this username (compared without regard to case), else -1. */
    public long resolve(String token, String username) {
        if (token == null) return -1;
        Entry entry = tokens.get(token);
        if (entry == null) return -1;
        if (entry.expires <= clock.getAsLong()) {
            tokens.remove(token);
            return -1;
        }
        return entry.username.equalsIgnoreCase(username) ? entry.accountId : -1;
    }
}
