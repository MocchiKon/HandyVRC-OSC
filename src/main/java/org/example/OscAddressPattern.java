package org.example;

import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCMessageEvent;
import com.illposed.osc.argument.OSCTimeTag64;
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector;

import java.util.List;

/**
 * An OSC address pattern (for example {@code /avatar/parameters/OGB/Orf/&#42;/PenOthersNewRoot}) that can be
 * matched against concrete OSC addresses with the same wildcard rules that are used when listening for
 * OSC messages (the star, question mark, bracket and curly brace wildcards of the OSC specification are
 * supported).
 * <p>
 * Used both for matching incoming OSC messages and for checking whether a configured parameter exists in the
 * OSC namespace that VRChat exposes through OSCQuery, so that configuration validation behaves exactly like
 * the actual message handling.
 */
public class OscAddressPattern
{
    /** OSCMessageEvent requires a non-null event source that is not used for matching. */
    private static final Object EVENT_SOURCE = new Object();

    private final String pattern;
    private final OSCPatternAddressMessageSelector selector;

    public OscAddressPattern(String pattern)
    {
        this.pattern = pattern;
        // VRChat parameter names may contain characters OSC forbids (for example spaces), so pattern and
        // address are both sanitized the same way before they are matched
        this.selector = new OSCPatternAddressMessageSelector(OscAddressSanitizer.sanitizePattern(pattern));
    }

    public String pattern()
    {
        return pattern;
    }

    public boolean matches(String address)
    {
        return matches(new OSCMessageEvent(EVENT_SOURCE, OSCTimeTag64.IMMEDIATE,
                new OSCMessage(OscAddressSanitizer.sanitizeAddress(address), List.of())));
    }

    public boolean matches(OSCMessageEvent messageEvent)
    {
        // Messages received from VRChat already had their forbidden characters replaced by the lenient
        // parser of OscListener, which is exactly what the selector was built with above
        return selector.matches(messageEvent);
    }

    @Override
    public String toString()
    {
        return pattern;
    }
}
