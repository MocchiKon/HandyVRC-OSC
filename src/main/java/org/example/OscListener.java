package org.example;

import com.illposed.osc.*;
import com.illposed.osc.argument.OSCTimeTag64;
import com.illposed.osc.transport.OSCPortIn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class OscListener
{
    private final OSCPortIn oscListener;

    public OscListener(int portIn) throws IOException
    {
        // Listeners are added from another thread while the listening thread dispatches received packets,
        // so a thread-safe list is used instead of the plain one created by the OSCPortIn constructor.
        // The lenient builder accepts the addresses VRChat sends for avatar parameters whose names contain
        // characters the OSC specification forbids (for example spaces)
        this.oscListener = new OSCPortIn(
                new LenientOscSerializerAndParserBuilder(),
                new CopyOnWriteArrayList<>(OSCPortIn.defaultPacketListeners()),
                new InetSocketAddress(portIn));
        this.oscListener.setDaemonListener(false);
        this.oscListener.startListening();
        log.info("Listening for OSC messages on port {}...", portIn);
    }

    /**
     * Registers a listener that is called once for every received OSC packet (either a single message or a whole
     * bundle) with the values of all parameters in that packet whose address matches one of {@code addressPatterns}
     * (OSC wildcards like '*' are supported).
     * <p>
     * The values are keyed by the pattern that matched, so they are looked up with the same pattern they were
     * registered with. Parameters that are not part of the received packet are missing from the map, and packets
     * that contain none of the registered parameters do not call the listener at all.
     * Thanks to this, parameters that VRChat sends together - for example the root and the tip proximity of the
     * same penetrator - are always handled together, in one call and regardless of their order in the packet.
     */
    public void registerPacketListener(List<String> addressPatterns, Consumer<Map<String, Float>> valuesConsumer)
    {
        List<OscAddressPattern> patterns = addressPatterns.stream().map(OscAddressPattern::new).toList();
        oscListener.addPacketListener(new PacketListener(patterns, valuesConsumer));
    }

    /** Stops listening for OSC messages. */
    public void close()
    {
        oscListener.stopListening();
    }

    @RequiredArgsConstructor
    private static class PacketListener implements OSCPacketListener
    {
        private final List<OscAddressPattern> patterns;
        private final Consumer<Map<String, Float>> valuesConsumer;

        @Override
        public void handlePacket(OSCPacketEvent event)
        {
            try
            {
                Map<String, Float> values = new LinkedHashMap<>();
                collectValues(event.getPacket(), values);
                if (values.isEmpty())
                {
                    return; // Nothing we listen for in this packet
                }
                valuesConsumer.accept(values);
            }
            catch (Exception e)
            {
                // Never let an exception escape, otherwise the OSC listening thread would die
                log.error("Exception during OSC message handling!", e);
            }
        }

        @Override
        public void handleBadData(OSCBadDataEvent event)
        {
            log.error("Could not parse received OSC packet: {}", event.getException().getMessage());
        }

        private void collectValues(OSCPacket packet, Map<String, Float> values)
        {
            if (packet instanceof OSCBundle bundle)
            {
                for (OSCPacket packetInBundle : bundle.getPackets())
                {
                    collectValues(packetInBundle, values);
                }
                return;
            }
            if (!(packet instanceof OSCMessage message))
            {
                return;
            }
            OSCMessageEvent messageEvent = new OSCMessageEvent(this, OSCTimeTag64.IMMEDIATE, message);
            for (OscAddressPattern pattern : patterns)
            {
                if (!pattern.matches(messageEvent))
                {
                    continue;
                }
                Float value = getFirstArgumentAsFloat(message);
                if (value != null)
                {
                    values.put(pattern.pattern(), value);
                }
            }
        }

        private Float getFirstArgumentAsFloat(OSCMessage message)
        {
            List<Object> arguments = message.getArguments();
            if (arguments.isEmpty())
            {
                log.error("Empty arguments for {}", message.getAddress());
                return null;
            }
            Object firstArgument = arguments.getFirst();
            if (!(firstArgument instanceof Number number))
            {
                log.error("Expected a number but got '{}' for {}", firstArgument, message.getAddress());
                return null;
            }
            return number.floatValue();
        }
    }
}
