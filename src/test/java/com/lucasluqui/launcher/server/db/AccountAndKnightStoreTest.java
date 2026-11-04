package com.lucasluqui.launcher.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccountAndKnightStoreTest {
    private static final PasswordHasher FAST = new PasswordHasher(1_000); // real work factor is slow on purpose

    private Database database;
    private AtomicLong now;
    private AccountStore accounts;
    private KnightStore knights;

    @BeforeEach
    public void open() throws SQLException {
        database = Database.inMemory();
        now = new AtomicLong(1_000_000);
        accounts = new AccountStore(database, FAST, now::get);
        knights = new KnightStore(database);
    }

    @AfterEach
    public void close() throws SQLException {
        database.close();
    }

    @Test
    public void firstSignInCreatesTheAccountAndTheSamePasswordSignsBackIn() throws SQLException {
        AccountStore.Result created = accounts.signIn("Admin", "1234");
        assertEquals(AccountStore.Status.CREATED, created.status);
        AccountStore.Result again = accounts.signIn("Admin", "1234");
        assertEquals(AccountStore.Status.OK, again.status);
        assertEquals(created.account.id, again.account.id);
    }

    @Test
    public void aWrongPasswordNeverSignsInAndNeverCreatesASecondAccount() throws SQLException {
        accounts.signIn("Admin", "1234");
        assertEquals(AccountStore.Status.WRONG_PASSWORD, accounts.signIn("Admin", "nope").status);
        assertEquals(AccountStore.Status.WRONG_PASSWORD, accounts.signIn("admin", "nope").status); // same name, other case
        assertEquals(AccountStore.Status.OK, accounts.signIn("ADMIN", "1234").status);
    }

    @Test
    public void usernamesAreUniqueIgnoringCaseButKeepTheirFirstSpelling() throws SQLException {
        AccountStore.Result created = accounts.signIn("SirDank", "pw");
        assertEquals("SirDank", created.account.username);
        assertEquals("SirDank", accounts.signIn("sirdank", "pw").account.username);
    }

    @Test
    public void rejectsEmptyOrAbsurdNamesAndPasswords() throws SQLException {
        assertEquals(AccountStore.Status.INVALID, accounts.signIn("", "pw").status);
        assertEquals(AccountStore.Status.INVALID, accounts.signIn("   ", "pw").status);
        assertEquals(AccountStore.Status.INVALID, accounts.signIn("name", "").status);
        assertEquals(AccountStore.Status.INVALID, accounts.signIn(null, "pw").status);
        assertEquals(AccountStore.Status.INVALID, accounts.signIn("x".repeat(AccountStore.MAX_NAME + 1), "pw").status);
        assertEquals(AccountStore.Status.INVALID, accounts.signIn("bad\nname", "pw").status);
        assertEquals(AccountStore.Status.INVALID, accounts.signIn("name", "p".repeat(AccountStore.MAX_PASSWORD + 1)).status);
    }

    @Test
    public void passwordsAreStoredSaltedAndHashedNotInClear() throws SQLException {
        accounts.signIn("a", "samepassword");
        accounts.signIn("b", "samepassword");
        List<byte[]> hashes = new ArrayList<>();
        List<byte[]> salts = new ArrayList<>();
        database.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT pw_hash, pw_salt FROM accounts ORDER BY id")) {
                while (rs.next()) { hashes.add(rs.getBytes(1)); salts.add(rs.getBytes(2)); }
            }
            return null;
        });
        assertEquals(2, hashes.size());
        assertFalse(java.util.Arrays.equals(hashes.get(0), hashes.get(1)), "the same password must not give the same hash");
        assertFalse(java.util.Arrays.equals(salts.get(0), salts.get(1)));
        assertNotEquals("samepassword", new String(hashes.get(0), java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals(32, hashes.get(0).length);
    }

    @Test
    public void tooManyWrongPasswordsLockTheNameForAWhile() throws SQLException {
        accounts.signIn("Admin", "right");
        for (int i = 0; i < AccountStore.MAX_FAILURES; i++) {
            assertEquals(AccountStore.Status.WRONG_PASSWORD, accounts.signIn("Admin", "wrong" + i).status);
        }
        assertEquals(AccountStore.Status.TOO_MANY_ATTEMPTS, accounts.signIn("Admin", "right").status);
        now.addAndGet(AccountStore.LOCKOUT_MS + 1);
        assertEquals(AccountStore.Status.OK, accounts.signIn("Admin", "right").status);
    }

    @Test
    public void manyThreadsCreatingTheSameUsernameCreateItOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<AccountStore.Status>> jobs = new ArrayList<>();
            for (int i = 0; i < 16; i++) jobs.add(() -> accounts.signIn("Racer", "pw").status);
            int created = 0;
            for (Future<AccountStore.Status> f : pool.invokeAll(jobs)) {
                AccountStore.Status status = f.get();
                if (status == AccountStore.Status.CREATED) created++;
                else assertEquals(AccountStore.Status.OK, status);
            }
            assertEquals(1, created);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void passwordResetChangesWhatSignsIn() throws SQLException {
        accounts.signIn("Admin", "old");
        assertTrue(accounts.resetPassword("Admin", "new"));
        assertEquals(AccountStore.Status.WRONG_PASSWORD, accounts.signIn("Admin", "old").status);
        assertEquals(AccountStore.Status.OK, accounts.signIn("Admin", "new").status);
        assertFalse(accounts.resetPassword("nobody", "x"));
    }

    @Test
    public void knightNamesAreUniqueAcrossTheWholeServerIgnoringCase() throws SQLException {
        long first = accounts.signIn("one", "pw").account.id;
        long second = accounts.signIn("two", "pw").account.id;
        assertEquals(KnightStore.Status.CREATED, knights.create(first, "Sir Dank", 3).status);
        assertEquals(KnightStore.Status.NAME_TAKEN, knights.create(second, "sir dank", 0).status);
        assertEquals(KnightStore.Status.NAME_TAKEN, knights.create(first, "SIR DANK", 0).status);
        assertEquals(KnightStore.Status.CREATED, knights.create(second, "Other Knight", 0).status);
    }

    @Test
    public void knightNameIsIndependentOfTheAccountName() throws SQLException {
        long account = accounts.signIn("Admin", "pw").account.id;
        assertEquals(KnightStore.Status.CREATED, knights.create(account, "HeroKnight", 0).status);
        assertEquals(KnightStore.Status.CREATED, knights.create(account, "Admin", 0).status);
        assertEquals(2, knights.list(account).size());
    }

    @Test
    public void validatesKnightNamesAndTheKnightLimit() throws SQLException {
        long account = accounts.signIn("Admin", "pw").account.id;
        assertEquals("e.name_too_short", knights.create(account, "ab", 0).error);
        assertEquals("e.name_too_long", knights.create(account, "Abcdefghijklmnopq", 0).error);
        assertEquals("e.name_invalid_character", knights.create(account, "bad<name>", 0).error);
        assertEquals("e.name_too_short", knights.create(account, null, 0).error);
        for (int i = 0; i < KnightStore.MAX_KNIGHTS_PER_ACCOUNT; i++) {
            assertEquals(KnightStore.Status.CREATED, knights.create(account, "Knight" + (char)('a' + i), 0).status);
        }
        assertEquals(KnightStore.Status.TOO_MANY_KNIGHTS, knights.create(account, "One More", 0).status);
    }

    @Test
    public void renameAndDeleteOnlyTouchTheOwnersKnights() throws SQLException {
        long owner = accounts.signIn("owner", "pw").account.id;
        long stranger = accounts.signIn("stranger", "pw").account.id;
        long knight = knights.create(owner, "Original", 0).knight.id;
        knights.create(stranger, "Taken Name", 0);

        assertEquals(KnightStore.Status.NOT_FOUND, knights.rename(stranger, knight, "Hijacked").status);
        assertEquals(KnightStore.Status.NAME_TAKEN, knights.rename(owner, knight, "taken name").status);
        assertEquals(KnightStore.Status.RENAMED, knights.rename(owner, knight, "Renamed").status);
        assertEquals("Renamed", knights.findByName("renamed").get().name);

        assertEquals(KnightStore.Status.NOT_FOUND, knights.delete(stranger, knight));
        assertEquals(KnightStore.Status.DELETED, knights.delete(owner, knight));
        assertTrue(knights.findByName("Renamed").isEmpty());
        assertEquals(KnightStore.Status.CREATED, knights.create(owner, "Renamed", 0).status); // the name is free again
    }

    @Test
    public void deletingAnAccountDeletesItsKnights() throws SQLException {
        long account = accounts.signIn("gone", "pw").account.id;
        knights.create(account, "Doomed Knight", 0);
        assertTrue(accounts.delete("gone"));
        assertTrue(knights.findByName("Doomed Knight").isEmpty());
    }

    @Test
    public void theFileSurvivesReopeningAndKeepsItsSchemaVersion(@TempDir Path dir) throws SQLException {
        File file = new File(dir.toFile(), "server/world.db");
        try (Database first = Database.open(file)) {
            new AccountStore(first, FAST).signIn("Admin", "pw");
            assertEquals(Database.MIGRATIONS.size(), first.schemaVersion());
        }
        try (Database second = Database.open(file)) {
            assertEquals(Database.MIGRATIONS.size(), second.schemaVersion());
            assertEquals(AccountStore.Status.OK, new AccountStore(second, FAST).signIn("Admin", "pw").status);
            assertEquals(AccountStore.Status.WRONG_PASSWORD, new AccountStore(second, FAST).signIn("Admin", "other").status);
        }
    }

    @Test
    public void refusesAFileFromANewerLauncher(@TempDir Path dir) throws SQLException {
        File file = new File(dir.toFile(), "world.db");
        try (Database db = Database.open(file)) {
            db.write(c -> { try (var st = c.createStatement()) { st.execute("PRAGMA user_version = 999"); } return null; });
        }
        try {
            Database.open(file).close();
            org.junit.jupiter.api.Assertions.fail("a database from a newer launcher must not be opened");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("newer launcher"));
        }
    }

    @Test
    public void aFailedTransactionIsRolledBack() throws SQLException {
        try {
            database.write(c -> {
                try (var st = c.createStatement()) {
                    st.execute("INSERT INTO accounts (username, pw_hash, pw_salt, pw_iterations, created_at) VALUES ('x', x'00', x'00', 1, 0)");
                }
                throw new SQLException("boom");
            });
        } catch (SQLException expected) {
            // expected
        }
        assertTrue(accounts.find("x").isEmpty());
    }

    @Test
    public void nameRulesFollowTheGamesOwnMessages() {
        assertNull(KnightStore.nameError("Sir Dank"));
        assertNull(KnightStore.nameError("Anne-Marie"));
        assertEquals("e.name_too_short", KnightStore.nameError("abc"));
        assertEquals("e.name_too_short", KnightStore.nameError("a b c"));          // three letters
        assertEquals("e.name_invalid_character", KnightStore.nameError("Knight2"));
        assertEquals("e.name_invalid_character", KnightStore.nameError("O'Neil"));
        assertEquals("e.name_invalid_space", KnightStore.nameError(" Knight"));
        assertEquals("e.name_invalid_space", KnightStore.nameError("Two  Spaces"));
        assertEquals("e.name_invalid_space", KnightStore.nameError("A B C D E"));    // more than two spaces
        assertEquals("e.name_invalid_dash", KnightStore.nameError("A-bcdef"));       // needs two letters before a dash
        assertEquals("e.name_invalid_dash", KnightStore.nameError("Ab-cd-ef-gh"));   // more than two dashes
        assertEquals("e.name_too_consec", KnightStore.nameError("Aaabcd"));
        assertNull(KnightStore.nameError("Aabbcc"));                                 // twice in a row is fine
    }
}
