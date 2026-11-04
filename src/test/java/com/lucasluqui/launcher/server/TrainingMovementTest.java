package com.lucasluqui.launcher.server;

import com.threerings.math.Vector2f;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.dungeon.data.DungeonInputFrame;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: in the Training Hall the game's own movement code moves the knight. It silently clamped every step to
 * zero (the client never sets {@code _maxStep}), so players could attack but not walk.
 */
public class TrainingMovementTest {

    private static final int MOVING = 1;

    /** A player object set up the way a login does it, without a network connection. */
    private static PlayerObject newPlayer(SceneManager scenes, MovementConfig movement) throws Exception {
        ClientSession session = new ClientSession(SocketChannel.open(), new DObjectManager(), scenes, null, movement,
            1, "test", 1, s -> {});
        for (String name : new String[]{"initServerObject", "initAdminObject", "initPlayer"}) {
            Method init = ClientSession.class.getDeclaredMethod(name);
            init.setAccessible(true);
            init.invoke(session);
        }
        Field player = ClientSession.class.getDeclaredField("playerObject");
        player.setAccessible(true);
        return (PlayerObject) player.get(session);
    }

    @Test
    public void knightWalksInTheTrainingHall() throws Exception {
        SpiralServerTest.assumeGameData();
        MovementConfig movement = MovementConfig.load();
        SceneManager scenes = new SceneManager(new DObjectManager(), movement);
        PlayerObject player = newPlayer(scenes, movement);
        Dungeoneer pawn = (Dungeoneer) scenes.createPlayerKnight(player, 1, SceneManager.TRAINING_SCENE_ID,
            SceneManager.TRAINING_SPAWN_X, SceneManager.TRAINING_SPAWN_Y);
        VanillaCombatController controller = new VanillaCombatController(scenes, player, pawn, 0);

        Vector2f start = new Vector2f(pawn.tz());
        int timestamp = 0;
        for (int frame = 0; frame < 50; frame++) { // one second of walking east, one input frame every 20 ms
            timestamp += 20;
            if (pawn.adX() == 1) pawn.aU(0, timestamp); // what ClientSession does once the creation animation is over
            controller.input(new DungeonInputFrame(timestamp, 0f, 0f, MOVING, timestamp));
            controller.tick(timestamp);
        }
        float walked = pawn.tz().x - start.x;
        // Base speed is 5 units per second; allow a few frames lost at the start and for collisions.
        assertTrue(walked > 3.0f, "the knight should have walked east, but moved " + walked);
        assertTrue(walked < 6.0f, "the knight moved faster than its speed: " + walked);
        assertEquals(start.y, pawn.tz().y, 0.01f);
    }
}
