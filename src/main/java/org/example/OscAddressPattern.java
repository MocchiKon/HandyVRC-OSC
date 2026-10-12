package org.example;

import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCMessageEvent;
import com.illposed.osc.argument.OSCTimeTag64;
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * An OSC address pattern (for example {@code /avatar/parameters/OGB/Orf/&#42;/PenOthersNewRoot}) that can be
 * matched against concrete OSC addresses with the same wildcard rules that are used when listening for
 * OSC messages (the star, question mark, bracket and curly brace wildcards of the OSC specification are
 * supported).
 * <p>
 * Curly brace alternatives ({@code {PenSelf,PenOthers}}) are expanded here into one pattern per alternative, because
 * the JavaOSC matcher does not handle them (it never matches them, and can even throw).
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
    /** One selector per alternative of the curly brace groups, the pattern matches when any of them does. */
    private final List<OSCPatternAddressMessageSelector> selectors;

    public OscAddressPattern(String pattern)
    {
        this.pattern = pattern;
        // VRChat parameter names may contain characters OSC forbids (for example spaces), so pattern and
        // address are both sanitized the same way before they are matched
        this.selectors = expandAlternatives(pattern).stream()
                .map(alternative -> new OSCPatternAddressMessageSelector(OscAddressSanitizer.sanitizePattern(alternative)))
                .toList();
    }

    /**
     * @return the pattern with every '{a,b}' group replaced by each of its alternatives, for example
     * '/x/{PenSelf,PenOthers}NewRoot' becomes '/x/PenSelfNewRoot' and '/x/PenOthersNewRoot'. A '{' without a
     * closing '}' is kept as it is.
     */
    static List<String> expandAlternatives(String pattern)
    {
        int open = pattern == null ? -1 : pattern.indexOf('{');
        int close = open < 0 ? -1 : pattern.indexOf('}', open);
        if (close < 0)
        {
            return List.of(pattern);
        }
        String before = pattern.substring(0, open);
        List<String> rest = expandAlternatives(pattern.substring(close + 1));
        List<String> expanded = new ArrayList<>();
        for (String alternative : pattern.substring(open + 1, close).split(",", -1))
        {
            for (String end : rest)
            {
                expanded.add(before + alternative + end);
            }
        }
        return expanded;
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
        for (OSCPatternAddressMessageSelector selector : selectors)
        {
            if (selector.matches(messageEvent))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString()
    {
        return pattern;
    }
}
