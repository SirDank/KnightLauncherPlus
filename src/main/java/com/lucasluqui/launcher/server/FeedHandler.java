package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.ArrayList;

import com.threerings.presents.data.InvocationMarshaller;

/** The activity feed: always empty (the game sorts the reply as a list, so it must be one). */
final class FeedHandler implements ServiceHandler {
    private final SessionContext session;

    FeedHandler(SessionContext session) {
        this.session = session;
    }

    @Override
    public boolean handle(int methodId, Object[] args, short requestId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (listener == null || methodId != 1) return false;
        session.respond(requestId, new ArrayList<>());
        return true;
    }
}
