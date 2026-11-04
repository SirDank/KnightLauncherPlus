package com.lucasluqui.launcher.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Sets;
import com.threerings.projectx.dungeon.data.PartyMarshaller;
import com.threerings.projectx.dungeon.data.PartyObject;

/** Creates and finds the parties of the server. */
final class PartyManager {
    /** Well clear of the fixed scene object ids (500/501), the admin object ids and the players' own. */
    static final int OID_BASE = 1_000_000;

    private final DObjectManager dobjMgr;
    private final SceneManager scenes;
    private final int maxSize;
    private final AtomicInteger nextOid = new AtomicInteger(OID_BASE);
    private final Map<Integer, Party> parties = new ConcurrentHashMap<>();

    PartyManager(DObjectManager dobjMgr, SceneManager scenes, int maxSize) {
        this.dobjMgr = dobjMgr;
        this.scenes = scenes;
        this.maxSize = maxSize;
    }

    int maxSize() {
        return this.maxSize;
    }

    Party find(int oid) {
        return this.parties.get(oid);
    }

    /** A new party with nobody in it yet. */
    Party create() {
        PartyObject object = new PartyObject();
        object.cH(this.nextOid.getAndIncrement());
        PartyMarshaller partyService = new PartyMarshaller();
        partyService.a(object.xL(), ClientSession.PARTY_SERVICE_CODE, null);
        object.partyService = partyService;
        com.threerings.crowd.chat.data.SpeakMarshaller speakService = new com.threerings.crowd.chat.data.SpeakMarshaller();
        speakService.a(object.xL(), ClientSession.PARTY_SPEAK_SERVICE_CODE, null);
        object.speakService = speakService;
        object.visited = Sets.newHashSet(SceneManager.TRAINING_SCENE_ID);
        object.spawn = new com.threerings.math.Vector2f(SceneManager.TRAINING_SPAWN_X, SceneManager.TRAINING_SPAWN_Y);
        object.depth = 0;
        object.hasPickups = true;
        Party party = new Party(object, this.scenes, this.maxSize);
        this.dobjMgr.registerIfAbsent(object);
        this.parties.put(object.xL(), party);
        return party;
    }

    /** Forgets a party that has no members left. */
    void dissolve(Party party) {
        if (this.parties.remove(party.oid(), party)) {
            this.dobjMgr.unregister(party.oid());
        }
    }
}
