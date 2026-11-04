package com.samskivert.depot.util;

/**
 * Stand-in for samskivert Depot's interface of the same name. The game's stream writer checks enums against it with
 * {@code instanceof} before it writes them. The game ships only the reading side, so a server that sends enums (like
 * a mail's type) needs this class to exist; none of the game's enums implement it, so it is never used otherwise.
 */
public interface ByteEnum {
    byte toByte();
}
