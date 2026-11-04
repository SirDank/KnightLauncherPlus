package com.lucasluqui.launcher.server;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import com.lucasluqui.launcher.server.db.AccountStore;
import com.lucasluqui.launcher.server.db.Database;
import com.lucasluqui.launcher.server.db.FriendStore;
import com.lucasluqui.launcher.server.db.KnightItemStore;
import com.lucasluqui.launcher.server.db.KnightStateStore;
import com.lucasluqui.launcher.server.db.KnightStore;
import com.lucasluqui.launcher.server.db.MailStore;
import com.lucasluqui.launcher.server.db.PasswordHasher;

/** What sessions share: the database with its stores, the server's key and the live session ids. */
public final class ServerServices implements AutoCloseable {
    public final Database database;
    public final AccountStore accounts;
    public final KnightStore knights;
    public final KnightStateStore knightState;
    public final KnightItemStore knightItems;
    public final FriendStore friends;
    public final MailStore mail;
    public final ServerKeys keys;
    public final SessionTokens sessions = new SessionTokens();
    /** The signed-in sessions by account, so something that happens to one player can reach another. */
    final java.util.concurrent.ConcurrentHashMap<Long, ClientSession> online = new java.util.concurrent.ConcurrentHashMap<>();
    final UplinkMailService uplinkMail;

    public ServerServices(Database database, ServerKeys keys, PasswordHasher hasher) {
        this.database = database;
        this.keys = keys;
        this.accounts = new AccountStore(database, hasher);
        this.knights = new KnightStore(database);
        this.knightState = new KnightStateStore(database);
        this.knightItems = new KnightItemStore(database);
        this.friends = new FriendStore(database);
        this.mail = new MailStore(database);
        this.uplinkMail = new UplinkMailService(this); // after the stores it reads
    }

    /** Opens {@code KnightLauncher/server/world.db} and the server key in the game folder, creating them if needed. */
    public static ServerServices open(File gameDir) throws SQLException, IOException {
        ServerKeys keys = ServerKeys.loadOrCreate(ServerKeys.keyFile(gameDir));
        Database database = Database.open(new File(gameDir, "KnightLauncher/server/world.db"));
        return new ServerServices(database, keys, new PasswordHasher());
    }

    @Override
    public void close() throws SQLException {
        this.database.close();
    }
}
