package org.example.oscquery;

/**
 * A single node of the OSC namespace that VRChat exposes through OSCQuery
 * (for example one avatar parameter).
 *
 * @param path  full OSC address of the node (for example {@code /avatar/parameters/OGB/Orf/1/PenOthersNewRoot})
 * @param type  OSC type tag reported by VRChat ({@code f} for float, {@code i} for int, ...); may be null
 * @param access OSCQuery access flags (1 = readable, 2 = writable); may be null
 */
public record OscQueryNode(String path, String type, Integer access)
{
    /** @return whether the node can be read (its value can be queried) */
    public boolean isReadable()
    {
        return access != null && (access & 1) != 0;
    }

    /** @return whether the value of the node is a number that OSC messages can carry (float or int) */
    public boolean isNumeric()
    {
        return type != null && (type.contains("f") || type.contains("i"));
    }

    /** @return short human readable description used in logs */
    public String describe()
    {
        return "%s (type=%s, access=%s)".formatted(path, type == null ? "?" : type, access == null ? "?" : access);
    }
}
