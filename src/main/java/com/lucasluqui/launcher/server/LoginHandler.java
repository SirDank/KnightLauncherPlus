package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import com.lucasluqui.launcher.server.db.AccountStore;
import com.lucasluqui.launcher.server.db.KnightStore;
import com.threerings.presents.net.AESAuthRequest;
import com.threerings.presents.net.AuthRequest;
import com.threerings.presents.net.AuthResponse;
import com.threerings.presents.net.Credentials;
import com.threerings.presents.net.PublicKeyCredentials;
import com.threerings.presents.net.SecureRequest;
import com.threerings.presents.net.SecureResponse;
import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.projectx.data.ProjectXCredentials;

/**
 * The login: the key exchange that lets the game encrypt the username and password, then signing in (creating the
 * account on the first login of a new username).
 */
final class LoginHandler {
    private final SessionContext session;
    /** Set after the key exchange, used to read the player's encrypted login. */
    private byte[] serverSecret;

    LoginHandler(SessionContext session) {
        this.session = session;
    }

    private void log(String text) {
        System.out.println("[ClientSession-" + session.connectionId() + "][AUTH] " + text);
    }

    /** Step one: answer the key exchange so the game can encrypt the username and password. */
    void handleSecure(SecureRequest request) throws IOException {
        SecureResponse response = new SecureResponse();
        ServerServices services = session.services();
        if (services == null) {
            response = new SecureResponse("m.failed_to_secure"); // no key here: the game then sends its login unencrypted
        } else {
            PublicKeyCredentials credentials = (PublicKeyCredentials)request.wV();
            this.serverSecret = response.a(credentials, services.keys.privateKey(), 16);
            if (this.serverSecret == null) {
                log("[WARNING] key exchange failed; the game was probably not set up by this launcher (wrong key.public)");
            }
        }
        session.send(response);
    }

    /**
     * Reads the player's login and signs in.
     *
     * @return Whether the player is signed in; if not, the refusal has been sent.
     */
    boolean signIn(AuthRequest request) throws IOException {
        ServerServices services = session.services();
        ProjectXCredentials credentials = readCredentials(request);
        if (credentials == null) {
            refuseLogin("m.failed_to_secure");
            return false;
        }
        String username = credentials.yq() == null ? "" : credentials.yq().toString();
        String password = credentials.yr() == null ? "" : credentials.yr(); // the game sends the MD5 of what was typed

        // steamClient only says the game has Steam running (it is true for typed logins too); a Steam ticket login
        // is the one with a "steam:<id>" username.
        if (username.startsWith("steam:")) {
            log("Steam logins are not supported here; sign in with a username and password");
            refuseLogin("m.steam_error");
            return false;
        }
        try {
            if (password.isEmpty() && credentials.sessionId != null && !credentials.sessionId.isEmpty()) {
                // A reconnect: the game replaced its password with the session id it was given at sign-in.
                long id = services.sessions.resolve(credentials.sessionId, username);
                AccountStore.Account known = id < 0 ? null : services.accounts.find(username).orElse(null);
                if (known == null || known.id != id) {
                    refuseLogin("m.session_expired");
                    return false;
                }
                session.setAccount(known);
            } else {
                AccountStore.Result result = services.accounts.signIn(username, password);
                switch (result.status) {
                    case CREATED:
                        log("new account " + result.account.username);
                        // fall through
                    case OK:
                        session.setAccount(result.account);
                        break;
                    case WRONG_PASSWORD:
                        refuseLogin("m.invalid_password");
                        return false;
                    case TOO_MANY_ATTEMPTS:
                        refuseLogin("m.too_many_attempts");
                        return false;
                    default:
                        refuseLogin("m.no_such_user");
                        return false;
                }
            }
            session.setKnights(ensureKnights(session.account()));
        } catch (SQLException e) {
            log("[ERROR] database: " + e);
            refuseLogin("m.server_error");
            return false;
        }
        return true;
    }

    private ProjectXCredentials readCredentials(AuthRequest request) {
        try {
            // A game whose key exchange failed (it holds a different key.public) falls back to sending the login
            // unencrypted. Never accept that: the passwords of a server with a key must not travel in the clear.
            if (!(request instanceof AESAuthRequest) || this.serverSecret == null) return null;
            ((AESAuthRequest)request).g(this.serverSecret);
            Credentials credentials = request.wV();
            return credentials instanceof ProjectXCredentials ? (ProjectXCredentials)credentials : null;
        } catch (Exception e) { // g() throws on a wrong key, whether or not it declares it
            log("[WARNING] could not read the login: " + e);
            return null;
        }
    }

    /** The account's knights; a first login gets one, named after the account if that is a valid knight name. */
    private List<KnightStore.Knight> ensureKnights(AccountStore.Account signedIn) throws SQLException {
        KnightStore store = session.services().knights;
        List<KnightStore.Knight> knights = store.list(signedIn.id);
        if (!knights.isEmpty()) return knights;
        String base = signedIn.username.replaceAll("[^A-Za-z -]", "").trim().replaceAll(" +", " ");
        if (base.length() > KnightStore.MAX_NAME - 2) base = base.substring(0, KnightStore.MAX_NAME - 2).trim();
        if (KnightStore.nameError(base) != null) base = "Knight";
        String name = base;
        Random random = new Random();
        for (int attempt = 0; attempt < 50; attempt++) {
            KnightStore.Result created = store.create(signedIn.id, name, SceneManager.DEFAULT_PLAYER_COLOR);
            if (created.status == KnightStore.Status.CREATED) return store.list(signedIn.id);
            // Taken, or not valid after all: add two letters (digits are not allowed in names).
            name = base + (char)('a' + random.nextInt(26)) + (char)('a' + random.nextInt(26));
            if (KnightStore.nameError(name) != null) name = "Knight" + (char)('a' + random.nextInt(26)) + (char)('a' + random.nextInt(26));
        }
        throw new SQLException("Could not find a free knight name for " + signedIn.username);
    }

    /** Sends the failure the game shows on its login screen, then ends the session. */
    private void refuseLogin(String code) throws IOException {
        log("refused: " + code);
        ProjectXAuthResponseData data = new ProjectXAuthResponseData();
        data.code = code;
        data.ident = "spiral";
        session.send(new AuthResponse(data));
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(500); // let the reply leave before the connection goes
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            session.close();
        }, "ClientSession-" + session.connectionId() + "-Refuse");
        closer.setDaemon(true);
        closer.start();
    }
}
