package com.lucasluqui.launcher.server;

import com.threerings.io.r;
import com.threerings.io.s;
import com.threerings.io.w;
import com.threerings.presents.net.AESAuthRequest;
import com.threerings.presents.net.AuthRequest;
import com.threerings.presents.net.AuthResponse;
import com.threerings.presents.net.PublicKeyCredentials;
import com.threerings.presents.net.SecureRequest;
import com.threerings.presents.net.SecureResponse;
import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.projectx.data.ProjectXCredentials;
import com.threerings.util.Name;

import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.InvocationRequestEvent;
import com.threerings.presents.dobj.InvocationResponseEvent;
import com.threerings.presents.net.BootstrapNotification;
import com.threerings.presents.net.EventNotification;
import com.threerings.presents.net.ForwardEventRequest;
import com.threerings.projectx.data.ProjectXBootstrapData;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.PublicKey;
import java.util.concurrent.TimeUnit;

/**
 * A game client for tests: the same stream classes, key exchange and encryption as the real one, over a real socket.
 * It speaks the protocol the way the game does, so a test that passes here is a test of what a player would hit.
 */
final class TestClient implements AutoCloseable {
    static final String VERSION = "20261028041525";

    private final SocketChannel channel;
    private final s sink = new s();
    private final w out = new w(sink);
    private final r source = new r();
    // The streams decode service marshallers, which attach themselves to a Client; it never connects anywhere.
    private final com.threerings.presents.client.Client client = new com.threerings.presents.client.Client(null,
        (com.samskivert.util.at) java.lang.reflect.Proxy.newProxyInstance(TestClient.class.getClassLoader(),
            new Class<?>[]{com.samskivert.util.at.class},
            (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.TRUE : null));
    private final com.threerings.presents.client.s in = new com.threerings.presents.client.s(client, source);

    private TestClient(SocketChannel channel) {
        this.channel = channel;
    }

    static TestClient connect(int port) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress(ServerNet.LOOPBACK, port));
        channel.configureBlocking(true);
        return new TestClient(channel);
    }

    void send(Object message) throws IOException {
        try {
            out.bf(message);
            out.flush();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        ByteBuffer frame = sink.sH();
        while (frame.hasRemaining()) channel.write(frame);
        sink.sI();
    }

    /** The next message from the server; fails (and closes the connection) if none arrives within {@code seconds}. */
    Object receive(int seconds) throws Exception {
        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(seconds);
                channel.close();
            } catch (Exception ignored) {
                // finished in time, or already closed
            }
        }, "TestClient-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            while (!source.a(channel)) {
                // keep reading until a whole message is there
            }
            return in.qe();
        } finally {
            watchdog.interrupt();
        }
    }

    /**
     * Signs in the way the game does: key exchange, then the encrypted login.
     *
     * @param publicKeyProperty The server's {@code key.public}.
     * @return The server's answer; its {@code code} is "success" or what the login screen shows.
     */
    ProjectXAuthResponseData login(String publicKeyProperty, String username, String typedPassword) throws Exception {
        return login(publicKeyProperty, new ProjectXCredentials(new Name(username), typedPassword));
    }

    ProjectXAuthResponseData login(String publicKeyProperty, ProjectXCredentials credentials) throws Exception {
        PublicKey key = com.threerings.presents.b.d.dD(publicKeyProperty);
        PublicKeyCredentials exchange = new PublicKeyCredentials(key);
        send(new SecureRequest(exchange, VERSION));
        SecureResponse secure = (SecureResponse) receive(10);
        AuthRequest request = AESAuthRequest.a(credentials, VERSION, new String[0], false, exchange, secure);
        send(request);
        AuthResponse response = (AuthResponse) receive(15);
        return (ProjectXAuthResponseData) response.yg();
    }

    private int clientOid = -1;
    private short nextRequest = 1;

    /** Reads messages until the server's bootstrap data arrives (it follows a successful login). */
    int awaitBootstrap() throws Exception {
        while (clientOid < 0) {
            Object message = receive(10);
            if (message instanceof BootstrapNotification) {
                clientOid = ((ProjectXBootstrapData) ((BootstrapNotification) message).yh()).clientOid;
            }
        }
        return clientOid;
    }

    /** What a service answered: {@code failed} with the message key, or a confirmation. */
    static final class Reply {
        final boolean failed;
        final String message;
        /** What a result listener received (the inbox, ...); empty for a plain confirmation. */
        final Object[] values;

        Reply(boolean failed, String message, Object[] values) {
            this.failed = failed;
            this.message = message;
            this.values = values == null ? new Object[0] : values;
        }
    }

    /**
     * Calls a service the way the game's marshallers do: an invocation request whose last argument is a confirm
     * listener, answered by an invocation response event. Other events that arrive meanwhile are skipped.
     */
    Reply invoke(int serviceCode, int method, Object... arguments) throws Exception {
        if (clientOid < 0) awaitBootstrap();
        InvocationMarshaller.ConfirmMarshaller listener = new InvocationMarshaller.ConfirmMarshaller();
        short request = nextRequest++;
        listener.requestId = request;
        Object[] args = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        args[arguments.length] = listener;
        send(new ForwardEventRequest(new InvocationRequestEvent(clientOid, serviceCode, method, args)));
        while (true) {
            Object message = receive(10);
            if (!(message instanceof EventNotification)) continue;
            Object event = ((EventNotification) message).yj();
            if (event instanceof InvocationResponseEvent && ((InvocationResponseEvent) event).yf() == request) {
                InvocationResponseEvent response = (InvocationResponseEvent) event;
                boolean failed = response.yc() == 0;
                Object[] values = response.yd();
                return new Reply(failed, failed && values != null && values.length > 0 ? String.valueOf(values[0]) : null, values);
            }
        }
    }

    /** Waits for the "new mail" entry the server adds to the player object when mail arrives. */
    com.threerings.projectx.uplink.data.NewMailInfo awaitNewMail(int seconds) throws Exception {
        while (true) {
            Object message = receive(seconds);
            if (!(message instanceof EventNotification)) continue;
            Object event = ((EventNotification) message).yj();
            if (event instanceof com.threerings.presents.dobj.EntryAddedEvent
                    && ((com.threerings.presents.dobj.EntryAddedEvent<?>) event).xZ() instanceof com.threerings.projectx.uplink.data.NewMailInfo) {
                return (com.threerings.projectx.uplink.data.NewMailInfo) ((com.threerings.presents.dobj.EntryAddedEvent<?>) event).xZ();
            }
        }
    }

    /** A call the game makes without waiting for an answer (speaking, going away). */
    void call(int serviceCode, int method, Object... arguments) throws Exception {
        if (clientOid < 0) awaitBootstrap();
        send(new ForwardEventRequest(new InvocationRequestEvent(clientOid, serviceCode, method, arguments)));
    }

    /** Waits for a chat line (a "crowd.chat" message event) and returns it. */
    com.threerings.crowd.chat.data.UserMessage awaitChat(int seconds) throws Exception {
        while (true) {
            Object message = receive(seconds);
            if (!(message instanceof EventNotification)) continue;
            Object event = ((EventNotification) message).yj();
            if (event instanceof com.threerings.presents.dobj.MessageEvent
                    && "crowd.chat".equals(((com.threerings.presents.dobj.MessageEvent) event).getName())) {
                return (com.threerings.crowd.chat.data.UserMessage) ((com.threerings.presents.dobj.MessageEvent) event).yd()[0];
            }
        }
    }

    /** Waits for an event on the player object's {@code attribute} set and returns it (added, updated or removed). */
    com.threerings.presents.dobj.DEvent awaitSetEvent(String attribute, int seconds) throws Exception {
        while (true) {
            Object message = receive(seconds);
            if (!(message instanceof EventNotification)) continue;
            Object event = ((EventNotification) message).yj();
            if (event instanceof com.threerings.presents.dobj.NamedEvent
                    && attribute.equals(((com.threerings.presents.dobj.NamedEvent) event).getName())
                    && (event instanceof com.threerings.presents.dobj.EntryEvent)) {
                return (com.threerings.presents.dobj.DEvent) event;
            }
        }
    }

    /** The object id of this client's party, read from the server (tests only). */
    int partyOidOf(SpiralServer server) {
        return server.partyOidOfClient(clientOid);
    }

    /** Waits for the server to tell the game to move to another scene; returns that scene's id. */
    int awaitZoneMove(int seconds) throws Exception {
        while (true) {
            Object message = receive(seconds);
            if (!(message instanceof EventNotification)) continue;
            Object event = ((EventNotification) message).yj();
            if (event instanceof com.threerings.presents.dobj.InvocationNotificationEvent) {
                Object[] args = ((com.threerings.presents.dobj.InvocationNotificationEvent) event).yd();
                if (args != null && args.length == 2 && args[1] instanceof Integer) return (Integer) args[1];
            }
        }
    }

    int clientOid() {
        return clientOid;
    }

    boolean isOpen() {
        return channel.isOpen();
    }

    /** True if the server closes the connection within {@code seconds}. */
    boolean closedByServer(int seconds) throws Exception {
        try {
            receive(seconds);
            return false; // something else arrived, so it is still open
        } catch (Exception endOfStream) {
            return true;
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
