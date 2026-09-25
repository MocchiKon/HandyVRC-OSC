package org.example;

import com.illposed.osc.OSCParser;
import com.illposed.osc.OSCSerializerAndParserBuilder;

/**
 * An {@link OSCSerializerAndParserBuilder} that builds {@link LenientOscParser parsers} which accept the
 * invalid OSC addresses VRChat sends for avatar parameters with spaces in their names. Serialization is
 * unchanged, so the parser only differs in what it accepts.
 */
class LenientOscSerializerAndParserBuilder extends OSCSerializerAndParserBuilder
{
    @Override
    public OSCParser buildParser()
    {
        // The standard parser already knows all argument handlers, its configuration is reused as is
        OSCParser standardParser = super.buildParser();
        return new LenientOscParser(standardParser.getIdentifierToTypeMapping(), standardParser.getProperties());
    }
}
