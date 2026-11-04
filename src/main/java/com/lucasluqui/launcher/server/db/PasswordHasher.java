package com.lucasluqui.launcher.server.db;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2-HMAC-SHA256 (from the JDK) for account passwords. Passwords are never stored or logged. */
public final class PasswordHasher {
    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. Takes a few hundred milliseconds on a modern CPU. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** For tests, which do not need to wait for a real work factor. */
    public PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public int iterations() {
        return this.iterations;
    }

    public byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return salt;
    }

    public byte[] hash(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Constant-time comparison. */
    public boolean matches(String password, byte[] salt, int iterations, byte[] expected) {
        return MessageDigest.isEqual(hash(password, salt, iterations), expected);
    }
}
