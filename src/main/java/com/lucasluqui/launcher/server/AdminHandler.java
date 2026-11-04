package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.projectx.dungeon.data.DungeonCodes;

/**
 * The server side of the game's own chat commands. The game keeps all slash commands in the client (a command it does
 * not know is refused before anything reaches a server), but several of its developer commands call an admin service
 * on the server. This answers three of them, which is how this server gets its commands:
 * <ul>
 * <li>{@code /mission <depth> [easy|medium|hard]}: starts a dungeon for your party (the game's "attempt a mission").</li>
 * <li>{@code /depth <n>}: the depth dungeons start at, and the depth of the next level you reach.</li>
 * <li>{@code /debug <command>}: the server's own commands, see {@link #help()}; {@code /debug help} lists them.</li>
 * </ul>
 * Every other admin call is refused ({@code m.access_denied}); there is no administrator here.
 */
final class AdminHandler implements ServiceHandler {
    private static final int MISSION = 3;
    private static final int DEBUG = 20;
    private static final int DEPTH = 39;

    private final SessionContext session;

    AdminHandler(SessionContext session) {
        this.session = session;
    }

    @Override
    public boolean handle(int methodId, Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (listener == null || session.services() == null || session.account() == null) return false;
        switch (methodId) {
            case MISSION:
                session.confirm(reqId, listener); // answered first: starting sends the game on its way
                say(startDungeon(text(args)));
                return true;
            case DEPTH:
                for (Object arg : args) {
                    if (arg instanceof Number) setDepth(((Number) arg).intValue());
                }
                session.confirm(reqId, listener);
                return true;
            case DEBUG:
                session.respond(reqId, debug(text(args)));
                return true;
            default:
                session.failRequest(reqId, "m.access_denied");
                return true;
        }
    }

    private static String text(Object[] args) {
        for (Object arg : args) if (arg instanceof String) return ((String) arg).trim();
        return "";
    }

    private void say(String text) {
        if (text != null && !text.isEmpty()) ChatHandler.notice(List.of(session.self()), "Server", text);
    }

    /** The commands of {@code /debug}; the answer is shown in the player's chat. */
    private String debug(String line) {
        String[] words = line.split("\\s+");
        switch (words[0].toLowerCase(Locale.ROOT)) {
            case "dungeon":
                String refusal = startDungeon(line.substring(words[0].length()).trim());
                return refusal == null ? "Starting a dungeon." : refusal;
            case "companions":
                return companions(words);
            case "leave":
                return leave();
            default:
                return help();
        }
    }

    static String help() {
        return "Server commands, typed as /debug <command>: dungeon [depth] [easy|medium|hard] (party leader, from town), "
            + "companions [n] (server-played knights for your dungeons), leave (out of a dungeon). "
            + "Also /mission <depth> [easy|medium|hard] to start a dungeon and /depth <n> to set the depth.";
    }

    /** Starts a dungeon from "[depth] [difficulty]"; returns what to tell the player if it could not be started. */
    private String startDungeon(String arguments) {
        int depth = 0; // the party's default
        DungeonCodes.Difficulty difficulty = DungeonCodes.Difficulty.MEDIUM;
        if (!arguments.isEmpty()) {
            for (String word : arguments.split("\\s+")) {
                try {
                    depth = Integer.parseInt(word);
                } catch (NumberFormatException notANumber) {
                    try {
                        difficulty = DungeonCodes.Difficulty.valueOf(word.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException notADifficulty) {
                        return "Usage: /mission [depth] [easy|medium|hard]";
                    }
                }
            }
        }
        return session.scenes().dungeons().start(session.self(), depth, difficulty);
    }

    private void setDepth(int depth) {
        if (depth < 1 || depth > DungeonManager.MAX_DEPTH) {
            say("Pick a depth from 1 to " + DungeonManager.MAX_DEPTH + ".");
            return;
        }
        session.party().setDefaultDepth(depth);
        Dungeon level = session.scenes().dungeons().find(session.self().currentSceneId());
        if (level != null) level.setNextDepth(depth);
        say("Dungeons start at depth " + depth + (level == null ? "." : ", and your next level is depth " + depth + "."));
    }

    private String companions(String[] words) {
        Party party = session.party();
        if (party.leader() != session.self()) return "Only the party leader can bring companions.";
        if (words.length < 2) return "You have " + party.companions() + " companions. Use /debug companions <number> (0 for none).";
        try {
            int room = Math.max(0, party.maxSize() - party.size());
            party.setCompanions(Math.min(Math.max(0, Integer.parseInt(words[1])), room));
            return "Companions: " + party.companions() + " (they join the next dungeon you start; the party and companions together fit "
                + party.maxSize() + ").";
        } catch (NumberFormatException notANumber) {
            return "Use /debug companions <number>.";
        }
    }

    private String leave() {
        try {
            if (!session.scenes().dungeons().isDungeon(session.self().currentSceneId())) return "You are not in a dungeon.";
            session.self().travelTo(SceneManager.DEFAULT_SCENE_ID);
            return "Leaving the dungeon.";
        } catch (IOException e) {
            return "Could not leave: " + e.getMessage();
        }
    }
}
