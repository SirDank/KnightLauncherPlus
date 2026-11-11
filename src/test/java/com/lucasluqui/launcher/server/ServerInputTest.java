package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerInputTest {
    @Test
    public void usernamesFollowTheServersRule() {
        assertTrue(ServerInput.validUsername("Bob"));
        assertTrue(ServerInput.validUsername("a_b-9"));
        assertTrue(ServerInput.validUsername("x".repeat(32)));
        assertFalse(ServerInput.validUsername("x".repeat(33)));
        assertFalse(ServerInput.validUsername("ab"));
        assertFalse(ServerInput.validUsername("9lives"));
        assertFalse(ServerInput.validUsername("two words"));
        assertFalse(ServerInput.validUsername("bob\ngm true"));
        assertFalse(ServerInput.validUsername(null));
    }

    @Test
    public void knightNamesAreLettersWithSingleHyphens() {
        assertTrue(ServerInput.validKnight("Sir-Lance"));
        assertTrue(ServerInput.validKnight("Dusk"));
        assertFalse(ServerInput.validKnight("Du"));
        assertFalse(ServerInput.validKnight("x".repeat(17)));
        assertFalse(ServerInput.validKnight("Sir--Lance"));
        assertFalse(ServerInput.validKnight("-Lance"));
        assertFalse(ServerInput.validKnight("Sir Lance"));
        assertFalse(ServerInput.validKnight("Knight1"));
    }

    @Test
    public void passwordsAreEightToOneTwentyEightWithoutControlCharacters() {
        assertTrue(ServerInput.validPassword("pass1234"));
        assertTrue(ServerInput.validPassword("with spaces and é"));
        assertFalse(ServerInput.validPassword("short"));
        assertFalse(ServerInput.validPassword("x".repeat(129)));
        assertFalse(ServerInput.validPassword("tab\tinside!"));
        assertFalse(ServerInput.validPassword("line\nbreak123"));
    }

    @Test
    public void anItemIsOneWordOnOneLine() {
        assertTrue(ServerInput.validItem("12345"));
        assertTrue(ServerInput.validItem("item/weapon/sword_of_x"));
        assertFalse(ServerInput.validItem(""));
        assertFalse(ServerInput.validItem("two words"));
        assertFalse(ServerInput.validItem("a\nshutdown"));
    }
}
