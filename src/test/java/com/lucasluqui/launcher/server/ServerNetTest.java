package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerNetTest {

    @Test
    public void validPortRange() {
        assertFalse(ServerNet.validPort(0));
        assertFalse(ServerNet.validPort(-1));
        assertTrue(ServerNet.validPort(1));
        assertTrue(ServerNet.validPort(ServerNet.DEFAULT_PORT));
        assertTrue(ServerNet.validPort(65535));
        assertFalse(ServerNet.validPort(65536));
    }

    @Test
    public void clientAddressesAreNeverEmpty() {
        assertFalse(ServerNet.findClientAddresses().isEmpty());
    }
}
