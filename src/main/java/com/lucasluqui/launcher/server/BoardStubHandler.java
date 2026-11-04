package com.lucasluqui.launcher.server;

import java.io.IOException;

import com.threerings.presents.data.InvocationMarshaller;

/**
 * The game's Board and Mission services (the dungeon gate and mission screens): their "join a party" calls expect a
 * yes/no result that the game casts to a Boolean. Dungeons are started with chat commands here, so the answer is
 * always no; everything else on these services gets the generic reply.
 */
final class BoardStubHandler implements ServiceHandler {
    private final SessionContext session;

    BoardStubHandler(SessionContext session) {
        this.session = session;
    }

    @Override
    public boolean handle(int methodId, Object[] args, short requestId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (!(listener instanceof InvocationMarshaller.ResultMarshaller)) return false;
        session.respond(requestId, Boolean.FALSE);
        return true;
    }
}
