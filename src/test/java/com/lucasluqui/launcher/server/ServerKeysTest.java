package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerKeysTest {

    @Test
    public void createsTheKeyOnceAndLoadsTheSameKeyAfterwards(@TempDir Path game) throws IOException {
        File file = ServerKeys.keyFile(game.toFile());
        ServerKeys first = ServerKeys.loadOrCreate(file);
        ServerKeys second = ServerKeys.loadOrCreate(file);
        assertEquals(first.publicKeyProperty(), second.publicKeyProperty());
        assertEquals(first.publicKey(), second.publicKey());
        try (var files = Files.list(file.getParentFile().toPath())) {
            assertEquals(1, files.count(), "no temporary files may be left behind");
        }
    }

    @Test
    public void publicKeyPropertyHasTheFormatTheGameParses(@TempDir Path game) throws Exception {
        ServerKeys keys = ServerKeys.loadOrCreate(ServerKeys.keyFile(game.toFile()));
        String property = keys.publicKeyProperty();
        assertTrue(property.matches("[0-9a-f]+#10001"), "modulus#exponent in hex, like the game's own key: " + property);
        assertEquals(keys.publicKey(), ServerKeys.parsePublicKeyProperty(property));
    }

    @Test
    public void thePublicKeyEncryptsWhatThePrivateKeyDecrypts(@TempDir Path game) throws Exception {
        ServerKeys keys = ServerKeys.loadOrCreate(ServerKeys.keyFile(game.toFile()));
        byte[] secret = "0123456789abcdef".getBytes();
        Cipher encrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        encrypt.init(Cipher.ENCRYPT_MODE, keys.publicKey());
        Cipher decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        decrypt.init(Cipher.DECRYPT_MODE, keys.privateKey());
        assertArrayEquals(secret, decrypt.doFinal(encrypt.doFinal(secret)));
    }

    @Test
    public void thePrivateKeyFileIsOnlyReadableByItsOwner(@TempDir Path game) throws IOException {
        File file = ServerKeys.keyFile(game.toFile());
        ServerKeys.loadOrCreate(file);
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.toPath());
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        } catch (UnsupportedOperationException windows) {
            // no POSIX permissions here
        }
    }

    @Test
    public void aDamagedKeyFileIsReportedNotReplaced(@TempDir Path game) throws IOException {
        File file = ServerKeys.keyFile(game.toFile());
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), "this is not a key");
        IOException error = assertThrows(IOException.class, () -> ServerKeys.loadOrCreate(file));
        assertTrue(error.getMessage().contains("Cannot read the server key"));
        assertEquals("this is not a key", Files.readString(file.toPath()), "must not overwrite a key it cannot read");
    }
}
