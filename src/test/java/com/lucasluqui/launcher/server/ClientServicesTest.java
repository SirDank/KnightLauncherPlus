package com.lucasluqui.launcher.server;

import com.threerings.presents.data.InvocationMarshaller;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client looks services up by class while it builds its windows; one that the server does not offer makes
 * it throw in every frame (it did for UplinkService). Every service we offer must load and initialise.
 */
public class ClientServicesTest {

    @Test
    public void everyOfferedServiceInitialises() throws Exception {
        Set<String> seen = new HashSet<>();
        int code = 40;
        for (String name : ClientSession.CLIENT_SERVICES) {
            assertTrue(seen.add(name), "listed twice: " + name);
            Object instance = Class.forName(name).getDeclaredConstructor().newInstance();
            assertTrue(instance instanceof InvocationMarshaller, name + " is not an invocation marshaller");
            ((InvocationMarshaller<?>) instance).a(10_001, code++, null);
        }
        assertEquals(ClientSession.CLIENT_SERVICES.length, seen.size());
    }

    @Test
    public void offersTheUplinkServiceTheClientCrashedWithout() {
        assertTrue(java.util.Arrays.asList(ClientSession.CLIENT_SERVICES)
            .contains("com.threerings.projectx.uplink.data.UplinkMarshaller"));
    }
}
