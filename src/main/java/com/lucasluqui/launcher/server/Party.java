package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.threerings.config.ConfigReference;
import com.threerings.presents.dobj.DEvent;
import com.threerings.presents.dobj.DSet;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.presents.dobj.EntryRemovedEvent;
import com.threerings.presents.dobj.EntryUpdatedEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.dungeon.data.Health;
import com.threerings.projectx.dungeon.data.PartyMember;
import com.threerings.projectx.dungeon.data.PartyObject;
import com.threerings.projectx.dungeon.data.Status;

/**
 * A group of players who share a party object (the game shows its members, health and statuses from it). Every
 * player is always in exactly one party, alone at first. Changes are applied to the object and sent to the members
 * that are subscribed to it; a player who joins is sent the object by subscribing after their {@code partyOid}
 * changes.
 */
final class Party {
    /** What one member has in the party object's sets. */
    private static final class Entries {
        PartyMember member;
        Health health;
        final Status status;

        Entries(PartyMember member, Health health, Status status) {
            this.member = member;
            this.health = health;
            this.status = status;
        }
    }

    final PartyObject object;
    private final SceneManager scenes;
    private final Map<ClientSession, Entries> members = new LinkedHashMap<>(); // join order
    private int nextJoinOrder;
    private volatile int companions;
    private volatile long travelUntil;
    private volatile int defaultDepth = 1;
    private final int maxSize;

    Party(PartyObject object, SceneManager scenes, int maxSize) {
        this.maxSize = maxSize;
        this.object = object;
        this.scenes = scenes;
        this.object.members = new DSet<>();
        this.object.health = new DSet<>();
        this.object.status = new DSet<>();
    }

    /** How many server-played knights go into dungeons with this party. */
    int companions() {
        return this.companions;
    }

    void setCompanions(int count) {
        this.companions = Math.max(0, count);
    }

    /** The depth a dungeon started by this party starts at. */
    int defaultDepth() {
        return this.defaultDepth;
    }

    void setDefaultDepth(int depth) {
        this.defaultDepth = depth;
    }

    /** Until when the party is on its way into a dungeon (milliseconds since 1970); a second start waits. */
    long travelUntil() {
        return this.travelUntil;
    }

    void setTravelUntil(long millis) {
        this.travelUntil = millis;
    }

    int maxSize() {
        return this.maxSize;
    }

    int oid() {
        return this.object.xL();
    }

    synchronized int size() {
        return this.members.size();
    }

    /** The members in the order they joined; the first is the leader. */
    synchronized List<ClientSession> members() {
        return new ArrayList<>(this.members.keySet());
    }

    synchronized ClientSession leader() {
        return this.members.isEmpty() ? null : this.members.keySet().iterator().next();
    }

    synchronized boolean contains(ClientSession session) {
        return this.members.containsKey(session);
    }

    private Entries entriesFor(ClientSession session, int joinOrder) {
        PlayerObject player = session.player();
        PartyMember member = new PartyMember(this.scenes.getConfigManager(), player, joinOrder, false);
        Health health = new Health(session.clientOid(), 100, 0);
        Status status = new Status(session.clientOid(), false);
        status.conditions = new ConfigReference[0];
        status.expires = new int[0];
        return new Entries(member, health, status);
    }

    /** Adds the player; the members already here are told. */
    synchronized void add(ClientSession session) {
        Entries entries = entriesFor(session, this.nextJoinOrder++);
        publish(new EntryAddedEvent<>(oid(), "members", entries.member));
        publish(new EntryAddedEvent<>(oid(), "health", entries.health));
        publish(new EntryAddedEvent<>(oid(), "status", entries.status));
        this.members.put(session, entries);
    }

    /** Removes the player; the members that stay are told. */
    synchronized void remove(ClientSession session) {
        Entries entries = this.members.remove(session);
        if (entries == null) return;
        publish(new EntryRemovedEvent<>(oid(), "members", entries.member.jz()));
        publish(new EntryRemovedEvent<>(oid(), "health", entries.health.jz()));
        publish(new EntryRemovedEvent<>(oid(), "status", entries.status.jz()));
    }

    /** The player's look changed (gear): tells the members. */
    synchronized void refresh(ClientSession session) {
        Entries entries = this.members.get(session);
        if (entries == null) return;
        PartyMember member = new PartyMember(this.scenes.getConfigManager(), session.player(), entries.member.joinOrder, false);
        entries.member = member;
        publish(new EntryUpdatedEvent<>(oid(), "members", member));
    }

    /** Shows the member's health to the party. */
    synchronized void setHealth(ClientSession session, int current, int maximum) {
        Entries entries = this.members.get(session);
        if (entries == null) return;
        Health health = new Health(session.clientOid(), maximum, 0);
        health.current = current;
        entries.health = health;
        publish(new EntryUpdatedEvent<>(oid(), "health", health));
    }

    /** Applies the event to the party object and sends it to every member. */
    private void publish(DEvent event) {
        try {
            event.b(this.object);
        } catch (RuntimeException e) {
            System.out.println("[PARTY][WARNING] could not update party " + oid() + ": " + e);
        }
        for (ClientSession member : new ArrayList<>(this.members.keySet())) {
            try {
                member.send(new EventNotification(event));
            } catch (java.io.IOException ignored) {
                // That member is going away and leaves on its own.
            }
        }
    }

    /** Sends a message to every member (chat lines). */
    void send(Object message) {
        for (ClientSession member : members()) {
            try {
                member.send(message);
            } catch (java.io.IOException ignored) {
                // Going away.
            }
        }
    }
}
