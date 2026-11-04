package com.lucasluqui.launcher.server;

import java.io.IOException;

import com.threerings.presents.data.InvocationMarshaller;

/** The server side of one of the game's services (knights, mail, chat, ...). */
interface ServiceHandler {
    /**
     * @param methodId The game's method id on the service.
     * @param args What the game sent; the last one is its reply listener.
     * @return Whether this handler answered; if not, the session's generic reply applies.
     */
    boolean handle(int methodId, Object[] args, short requestId, InvocationMarshaller.ListenerMarshaller listener)
        throws IOException;
}
