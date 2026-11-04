package com.lucasluqui.launcher.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Accounts. The first sign-in with an unknown username creates the account with the password given; after that the
 * same password is required, and nobody else can take the username (names are unique without regard to case).
 */
public final class AccountStore {

    public enum Status {
        /** The username was new: the account was created and signed in. */
        CREATED,
        /** Known username, right password. */
        OK,
        WRONG_PASSWORD,
        /** The name or password is empty, too long or has control characters. */
        INVALID,
        /** Too many wrong passwords for this username in a short time. */
        TOO_MANY_ATTEMPTS
    }

    public static final class Account {
        public final long id;
        public final String username;

        Account(long id, String username) {
            this.id = id;
            this.username = username;
        }
    }

    public static final class Result {
        public final Status status;
        /** The account, for {@link Status#CREATED} and {@link Status#OK}; null otherwise. */
        public final Account account;

        Result(Status status, Account account) {
            this.status = status;
            this.account = account;
        }

        public boolean signedIn() {
            return this.account != null;
        }
    }

    public static final int MAX_NAME = 32;
    public static final int MAX_PASSWORD = 128;
    static final int MAX_FAILURES = 5;
    static final long FAILURE_WINDOW_MS = 60_000;
    static final long LOCKOUT_MS = 30_000;

    private final Database database;
    private final PasswordHasher hasher;
    private final LongSupplier clock;
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    private static final class Attempts {
        int count;
        long firstAt;
        long lockedUntil;
    }

    public AccountStore(Database database, PasswordHasher hasher) {
        this(database, hasher, System::currentTimeMillis);
    }

    /** The clock is a parameter so tests can move time. */
    AccountStore(Database database, PasswordHasher hasher, LongSupplier clock) {
        this.database = database;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** Signs in, creating the account if the username is new. */
    public Result signIn(String username, String password) throws SQLException {
        String name = username == null ? "" : username.trim();
        if (!validName(name) || password == null || password.isEmpty() || password.length() > MAX_PASSWORD) {
            return new Result(Status.INVALID, null);
        }
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (lockedOut(key)) {
            return new Result(Status.TOO_MANY_ATTEMPTS, null);
        }

        Result result = database.write(c -> {
            try (PreparedStatement find = c.prepareStatement(
                    "SELECT id, username, pw_hash, pw_salt, pw_iterations FROM accounts WHERE username = ?")) {
                find.setString(1, name);
                try (ResultSet row = find.executeQuery()) {
                    if (row.next()) {
                        boolean ok = hasher.matches(password, row.getBytes("pw_salt"), row.getInt("pw_iterations"), row.getBytes("pw_hash"));
                        if (!ok) return new Result(Status.WRONG_PASSWORD, null);
                        long id = row.getLong("id");
                        touch(c, id);
                        return new Result(Status.OK, new Account(id, row.getString("username")));
                    }
                }
            }
            byte[] salt = hasher.newSalt();
            long now = clock.getAsLong();
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO accounts (username, pw_hash, pw_salt, pw_iterations, created_at, last_login) VALUES (?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, name);
                insert.setBytes(2, hasher.hash(password, salt, hasher.iterations()));
                insert.setBytes(3, salt);
                insert.setInt(4, hasher.iterations());
                insert.setLong(5, now);
                insert.setLong(6, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return new Result(Status.CREATED, new Account(keys.getLong(1), name));
                }
            }
        });

        if (result.status == Status.WRONG_PASSWORD) recordFailure(key);
        else if (result.signedIn()) failures.remove(key);
        return result;
    }

    public Optional<Account> find(String username) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement find = c.prepareStatement("SELECT id, username FROM accounts WHERE username = ?")) {
                find.setString(1, username == null ? "" : username.trim());
                try (ResultSet row = find.executeQuery()) {
                    return row.next() ? Optional.of(new Account(row.getLong(1), row.getString(2))) : Optional.<Account>empty();
                }
            }
        });
    }

    /** All accounts, oldest first. */
    public java.util.List<Account> list() throws SQLException {
        return database.read(c -> {
            java.util.List<Account> accounts = new java.util.ArrayList<>();
            try (java.sql.Statement all = c.createStatement();
                 ResultSet rows = all.executeQuery("SELECT id, username FROM accounts ORDER BY id")) {
                while (rows.next()) accounts.add(new Account(rows.getLong(1), rows.getString(2)));
            }
            return accounts;
        });
    }

    /** Sets a new password (the console's account reset). */
    public boolean resetPassword(String username, String newPassword) throws SQLException {
        if (newPassword == null || newPassword.isEmpty() || newPassword.length() > MAX_PASSWORD) return false;
        byte[] salt = hasher.newSalt();
        return database.write(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE accounts SET pw_hash = ?, pw_salt = ?, pw_iterations = ? WHERE username = ?")) {
                update.setBytes(1, hasher.hash(newPassword, salt, hasher.iterations()));
                update.setBytes(2, salt);
                update.setInt(3, hasher.iterations());
                update.setString(4, username.trim());
                return update.executeUpdate() == 1;
            }
        });
    }

    /** Deletes the account and, with it, its knights. */
    public boolean delete(String username) throws SQLException {
        return database.write(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM accounts WHERE username = ?")) {
                delete.setString(1, username.trim());
                return delete.executeUpdate() == 1;
            }
        });
    }

    private void touch(java.sql.Connection c, long id) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE accounts SET last_login = ? WHERE id = ?")) {
            update.setLong(1, clock.getAsLong());
            update.setLong(2, id);
            update.executeUpdate();
        }
    }

    static boolean validName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        return true;
    }

    private boolean lockedOut(String key) {
        Attempts attempts = failures.get(key);
        return attempts != null && attempts.lockedUntil > clock.getAsLong();
    }

    private void recordFailure(String key) {
        long now = clock.getAsLong();
        Attempts attempts = failures.computeIfAbsent(key, k -> new Attempts());
        synchronized (attempts) {
            if (now - attempts.firstAt > FAILURE_WINDOW_MS) {
                attempts.count = 0;
                attempts.firstAt = now;
            }
            if (++attempts.count >= MAX_FAILURES) {
                attempts.lockedUntil = now + LOCKOUT_MS;
                attempts.count = 0;
            }
        }
    }
}
