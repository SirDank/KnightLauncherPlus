package com.lucasluqui.launcher.server;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The server's own RSA key. The game encrypts the player's login with the public key in its
 * {@code deployment.properties} ({@code key.public}), so a server that holds the private half can read the username and
 * password. The launcher writes {@link #publicKeyProperty()} into the Local Server's settings; the server process loads
 * the private key from the same file. No game or launcher classes in here.
 */
public final class ServerKeys {
    private static final int BITS = 2048;
    private static final String FILE_NAME = "server-private.key";

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private ServerKeys(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static File keyFile(File gameDir) {
        return new File(gameDir, "KnightLauncher/server/keys/" + FILE_NAME);
    }

    /** Loads the key in {@code file}, creating it first if there is none. Safe to call from two processes at once. */
    public static ServerKeys loadOrCreate(File file) throws IOException {
        if (!file.isFile()) {
            create(file);
        }
        return load(file);
    }

    private static void create(File file) throws IOException {
        KeyPair pair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(BITS);
            pair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("RSA is not available", e);
        }
        Path directory = file.getAbsoluteFile().getParentFile().toPath();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "server-private.", ".tmp");
        try {
            Files.write(temporary, Base64.getEncoder().encode(pair.getPrivate().getEncoded()));
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Not a POSIX file system (Windows): the folder permissions of the game folder apply.
            }
            // No REPLACE: if the other process was first, keep its key.
            Files.move(temporary, file.toPath());
        } catch (FileAlreadyExistsException alreadyThere) {
            // loaded by the caller
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ServerKeys load(File file) throws IOException {
        try {
            byte[] der = Base64.getDecoder().decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).trim());
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey)) {
                throw new IOException(file + " is not an RSA key with CRT parameters");
            }
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new ServerKeys(privateKey, publicKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Cannot read the server key " + file + ": " + e.getMessage()
                + " (delete it to make a new one; players then need to launch through the launcher again)", e);
        }
    }

    public PrivateKey privateKey() {
        return this.privateKey;
    }

    public RSAPublicKey publicKey() {
        return this.publicKey;
    }

    /** The public key as the game's {@code key.public} setting wants it: modulus and exponent in hex, joined by '#'. */
    public String publicKeyProperty() {
        return this.publicKey.getModulus().toString(16) + "#" + this.publicKey.getPublicExponent().toString(16);
    }

    /** Parses a {@code key.public} value the way the game does. */
    public static RSAPublicKey parsePublicKeyProperty(String property) throws GeneralSecurityException {
        String[] parts = property.trim().split("#");
        if (parts.length != 2) throw new IllegalArgumentException("expected <modulus>#<exponent>");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
            new RSAPublicKeySpec(new BigInteger(parts[0], 16), new BigInteger(parts[1], 16)));
    }
}
