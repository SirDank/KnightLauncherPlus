package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.threerings.presents.data.InvocationMarshaller;

/** Hands each service request to the handler registered for its service code. */
final class InvocationRouter {
    private final Map<Integer, ServiceHandler> handlers = new HashMap<>();

    void register(int serviceCode, ServiceHandler handler) {
        if (this.handlers.putIfAbsent(serviceCode, handler) != null) {
            throw new IllegalStateException("Two handlers for service code " + serviceCode);
        }
    }

    /** @return Whether a handler answered the request. */
    boolean route(int serviceCode, int methodId, Object[] args, short requestId,
                  InvocationMarshaller.ListenerMarshaller listener) throws IOException {
        ServiceHandler handler = this.handlers.get(serviceCode);
        return handler != null && handler.handle(methodId, args, requestId, listener);
    }
}
