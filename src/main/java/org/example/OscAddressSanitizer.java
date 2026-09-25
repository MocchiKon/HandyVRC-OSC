package org.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Makes OSC addresses that contain characters the OSC specification forbids parseable.
 * <p>
 * VRChat sends avatar parameter names verbatim, so a parameter called for example {@code Big Pen} arrives
 * as the address {@code /avatar/parameters/OGB/Pen/Big Pen/PenSelf}, which JavaOSC rejects with
 * {@code Not a valid OSC address}. The forbidden characters are the ones JavaOSC rejects:
 * space, {@code '#'}, {@code '*'}, {@code ','}, {@code '?'}, {@code '['}, {@code ']'}, <code>'{'</code> and
 * {@code '}'}.
 * <p>
 * Every forbidden character is replaced by {@link #REPLACEMENT_CHAR}, an ASCII control character that
 * cannot appear in a parameter name and that JavaOSC accepts. The replacement is a single byte, so it can
 * be done in place on the raw packet without changing any offsets, which keeps the arguments and - for a
 * bundle - the following messages intact. The same replacement is applied to the received address and to
 * the configured address patterns, so that wildcard matching keeps working.
 * <p>
 * The whole packet is walked before it is parsed, because JavaOSC parses the messages of a bundle from
 * read-only buffers which cannot be changed in place anymore.
 */
final class OscAddressSanitizer
{
    /** One byte long, so addresses can be replaced in place, and not usable in a VRChat parameter name. */
    static final byte REPLACEMENT_BYTE = 0x01;
    static final char REPLACEMENT_CHAR = (char) REPLACEMENT_BYTE;

    private static final byte[] BUNDLE_START = "#bundle".getBytes(StandardCharsets.US_ASCII);
    /** {@code "#bundle"} plus its terminating zero plus the 8 byte time tag. */
    private static final int BUNDLE_HEADER_LENGTH = 7 + 1 + 8;
    private static final int SIZE_FIELD_LENGTH = Integer.BYTES;

    private OscAddressSanitizer()
    {
    }

    /**
     * Replaces all forbidden characters of every address in the given raw OSC packet (also in the messages
     * of bundles and nested bundles). The buffer is not modified in any other way; in particular its
     * position and limit are left alone, so it can be parsed right afterwards.
     */
    static void sanitizePacketInPlace(ByteBuffer packet)
    {
        if (!packet.hasArray())
        {
            return; // A read-only buffer cannot be changed; the top level packet is always writable
        }
        sanitizePacket(packet.array(), packet.arrayOffset() + packet.position(), packet.arrayOffset() + packet.limit());
    }

    /** @return the given received address with all forbidden characters replaced (never {@code null}) */
    static String sanitizeAddress(String address)
    {
        if (address == null || address.isEmpty() || address.charAt(0) != '/')
        {
            return address;
        }
        return replaceForbidden(address, OscAddressSanitizer::isForbidden);
    }

    /**
     * @return the given address pattern with the forbidden characters that are literals in a pattern
     * replaced. The wildcard characters ({@code '*'}, {@code '?'}, {@code '['}, {@code ']'},
     * <code>'{'</code> and {@code '}'}) are kept, because the pattern matcher handles them specially.
     */
    static String sanitizePattern(String pattern)
    {
        if (pattern == null || pattern.isEmpty() || pattern.charAt(0) != '/')
        {
            return pattern;
        }
        return replaceForbidden(pattern, OscAddressSanitizer::isForbiddenLiteralInPattern);
    }

    private static void sanitizePacket(byte[] bytes, int start, int end)
    {
        if (isBundle(bytes, start, end))
        {
            sanitizeBundle(bytes, start, end);
            return;
        }
        sanitizeAddress(bytes, start, end);
    }

    private static void sanitizeBundle(byte[] bytes, int start, int end)
    {
        int position = start + BUNDLE_HEADER_LENGTH;
        while (position + SIZE_FIELD_LENGTH <= end)
        {
            int packetLength = readInt32(bytes, position);
            position += SIZE_FIELD_LENGTH;
            if (packetLength <= 0 || position + packetLength > end)
            {
                return; // Malformed; leave the rest alone and let the parser report the error
            }
            sanitizePacket(bytes, position, position + packetLength);
            position += packetLength;
        }
    }

    /**
     * Replaces all forbidden characters of the address at the beginning of the packet (the address ends at
     * the first zero byte). Bundles and addresses that JavaOSC accepts as they are ({@code #reply}) are
     * left untouched.
     */
    private static void sanitizeAddress(byte[] bytes, int start, int end)
    {
        if (start >= end || bytes[start] != '/')
        {
            return; // A bundle ("#bundle") or "#reply", both are valid as they are
        }
        for (int i = start; i < end && bytes[i] != 0; i++)
        {
            if (isForbidden((char) (bytes[i] & 0xFF)))
            {
                bytes[i] = REPLACEMENT_BYTE;
            }
        }
    }

    private static boolean isBundle(byte[] bytes, int start, int end)
    {
        if (start + BUNDLE_START.length > end)
        {
            return false;
        }
        for (int i = 0; i < BUNDLE_START.length; i++)
        {
            if (bytes[start + i] != BUNDLE_START[i])
            {
                return false;
            }
        }
        return true;
    }

    private static int readInt32(byte[] bytes, int position)
    {
        return ((bytes[position] & 0xFF) << 24)
                | ((bytes[position + 1] & 0xFF) << 16)
                | ((bytes[position + 2] & 0xFF) << 8)
                | (bytes[position + 3] & 0xFF);
    }

    private static String replaceForbidden(String address, CharPredicate isForbidden)
    {
        StringBuilder sanitized = null;
        for (int i = 0; i < address.length(); i++)
        {
            if (!isForbidden.test(address.charAt(i)))
            {
                continue;
            }
            if (sanitized == null)
            {
                sanitized = new StringBuilder(address);
            }
            sanitized.setCharAt(i, REPLACEMENT_CHAR);
        }
        return sanitized == null ? address : sanitized.toString();
    }

    private static boolean isForbidden(char character)
    {
        return switch (character)
        {
            case ' ', '#', '*', ',', '?', '[', ']', '{', '}' -> true;
            default -> false;
        };
    }

    /** Only these forbidden characters are literals in a pattern; the other ones are wildcards. */
    private static boolean isForbiddenLiteralInPattern(char character)
    {
        return character == ' ' || character == '#' || character == ',';
    }

    @FunctionalInterface
    private interface CharPredicate
    {
        boolean test(char character);
    }
}
