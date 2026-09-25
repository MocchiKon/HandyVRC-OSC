package org.example;

import com.illposed.osc.*;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LenientOscParserTest
{
    private final OSCParser parser = new LenientOscSerializerAndParserBuilder().buildParser();

    @Test
    void parsesAddressThatContainsASpace() throws Exception
    {
        OSCPacket packet = parser.convert(ByteBuffer.wrap(OscTestPackets.message(
                "/avatar/parameters/OGB/Pen/Actual Wsome Lollipop/PenSelf", 0.5f)));

        assertThat(packet).isInstanceOf(OSCMessage.class);
        OSCMessage message = (OSCMessage) packet;
        assertThat(message.getAddress())
                .isEqualTo("/avatar/parameters/OGB/Pen/Actual\u0001Wsome\u0001Lollipop/PenSelf");
        assertThat(message.getArguments()).containsExactly(0.5f);
    }

    @Test
    void parsesTheAddressFromTheReportedError() throws Exception
    {
        String address = "/avatar/parameters/OGB/Pen/Actual Wsome Lollipop$7E0d4031 F7b0 4509 9F5a"
                + " E25bb18b8454/PenSelf";

        OSCMessage message = (OSCMessage) parser.convert(ByteBuffer.wrap(OscTestPackets.message(address, 0.42f)));

        assertThat(message.getAddress()).doesNotContain(" ").contains("$7E0d4031");
        assertThat(message.getArguments()).containsExactly(0.42f);
    }

    @Test
    void parsesEveryMessageOfABundleEvenWhenOneAddressIsInvalid() throws Exception
    {
        OSCPacket packet = parser.convert(ByteBuffer.wrap(OscTestPackets.bundle(
                OscTestPackets.message("/avatar/parameters/OGB/Orf/Big Pen/PenSelfNewRoot", 0.5f),
                OscTestPackets.message("/avatar/parameters/OGB/Orf/Blowjob/PenSelfNewTip", 0.6f))));

        assertThat(packet).isInstanceOf(OSCBundle.class);
        assertThat(((OSCBundle) packet).getPackets())
                .hasSize(2)
                .allSatisfy(message -> assertThat(message).isInstanceOf(OSCMessage.class));
        assertThat(((OSCMessage) ((OSCBundle) packet).getPackets().get(1)).getArguments()).containsExactly(0.6f);
    }

    @Test
    void parsesNestedBundlesWithInvalidAddresses() throws Exception
    {
        byte[] inner = OscTestPackets.bundle(
                OscTestPackets.message("/avatar/parameters/OGB/Orf/Big Pen/PenSelfNewRoot", 0.5f));
        OSCPacket packet = parser.convert(ByteBuffer.wrap(OscTestPackets.bundle(inner)));

        assertThat(packet).isInstanceOf(OSCBundle.class);
        OSCPacket innerPacket = ((OSCBundle) packet).getPackets().getFirst();
        assertThat(innerPacket).isInstanceOf(OSCBundle.class);
        assertThat(((OSCMessage) ((OSCBundle) innerPacket).getPackets().getFirst()).getAddress())
                .isEqualTo("/avatar/parameters/OGB/Orf/Big\u0001Pen/PenSelfNewRoot");
    }

    @Test
    void stillRejectsGenuinelyInvalidData() 
    {
        // An address that does not start with '/' cannot be repaired by replacing characters
        assertThatThrownBy(() -> parser.convert(ByteBuffer.wrap(OscTestPackets.message("no-slash", 0.5f))))
                .isInstanceOf(OSCParseException.class);
    }
}
