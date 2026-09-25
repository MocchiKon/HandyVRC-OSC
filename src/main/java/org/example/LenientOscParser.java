package org.example;

import com.illposed.osc.OSCPacket;
import com.illposed.osc.OSCParseException;
import com.illposed.osc.OSCParser;
import com.illposed.osc.argument.ArgumentHandler;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * An {@link OSCParser} that accepts the addresses VRChat sends for avatar parameters whose names contain
 * characters the OSC specification forbids (most notably spaces, for example
 * {@code /avatar/parameters/OGB/Pen/Big Pen/PenSelf}).
 * <p>
 * The forbidden characters are replaced in the raw packet before it is parsed (see
 * {@link OscAddressSanitizer}), so such a message is received instead of being reported as a bad packet.
 * All addresses of the packet are replaced up front, including those inside bundles, so one invalid
 * address no longer makes VRChat's whole bundle (and with it every other parameter in it) disappear.
 */
class LenientOscParser extends OSCParser
{
    LenientOscParser(Map<Character, ArgumentHandler> identifierToType, Map<String, Object> properties)
    {
        super(identifierToType, properties);
    }

    @Override
    public OSCPacket convert(ByteBuffer rawInput) throws OSCParseException
    {
        OscAddressSanitizer.sanitizePacketInPlace(rawInput);
        return super.convert(rawInput);
    }
}
