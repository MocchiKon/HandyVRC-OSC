package org.example.oscquery;

import org.example.OscAddressPattern;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Finds the SPS parameters that VRChat currently exposes through OSCQuery so that the configured
 * {@code avatarParameter} can be validated against the avatar that is actually loaded.
 * <p>
 * Two kinds of candidates are detected, based on the SPS (OGB) parameter naming convention:
 * <ul>
 *     <li><b>orifice</b> candidates: receiver proximity parameters ({@code *NewRoot} / {@code *NewTip} pairs,
 *     for example {@code /avatar/parameters/OGB/Orf/1/PenOthersNewRoot}). The root of a pair is what this app
 *     uses in ORIFICE mode, the tip is needed to auto-detect the penetrator length.</li>
 *     <li><b>penetrator</b> candidates: penetration amount parameters ({@code PenOthers} / {@code PenSelf},
 *     for example {@code /avatar/parameters/OGB/Pen/1/PenOthers}) used in PENETRATOR mode.</li>
 * </ul>
 */
public final class SpsParameterDetector
{
    public static final String NEW_ROOT_SUFFIX = "NewRoot";
    public static final String NEW_TIP_SUFFIX = "NewTip";

    /** Penetration amount parameters of the SPS penetrator (PenOthers = other people, PenSelf = self test). */
    private static final Pattern PENETRATOR_PARAMETER = Pattern.compile(".*/(PenOthers|PenSelf)$");
    /** Parameters that look SPS related but are neither a proximity pair nor a penetration amount. */
    private static final Pattern OTHER_SPS_PARAMETER = Pattern.compile("(?i).*(pen|orf|sps).*");

    private SpsParameterDetector()
    {
    }

    /**
     * @param nodes all nodes that VRChat exposes below {@code /avatar/parameters}
     * @return the detected penetrator/orifice candidates, sorted by path
     */
    public static Detection detect(Collection<OscQueryNode> nodes)
    {
        Set<String> paths = new TreeSet<>();
        for (OscQueryNode node : nodes)
        {
            paths.add(node.path());
        }

        List<PenetratorPair> orificePairs = new ArrayList<>();
        List<String> incompleteOrificeParameters = new ArrayList<>();
        for (String path : paths)
        {
            if (!path.endsWith(NEW_ROOT_SUFFIX) && !path.endsWith(NEW_TIP_SUFFIX))
            {
                continue;
            }
            String counterpart = isRoot(path)
                    ? replaceSuffix(path, NEW_ROOT_SUFFIX, NEW_TIP_SUFFIX)
                    : replaceSuffix(path, NEW_TIP_SUFFIX, NEW_ROOT_SUFFIX);
            if (!paths.contains(counterpart))
            {
                incompleteOrificeParameters.add(path);
            }
            else if (isRoot(path))
            {
                orificePairs.add(new PenetratorPair(path, counterpart));
            }
        }

        List<String> penetratorParameters = new ArrayList<>();
        List<String> otherSpsParameters = new ArrayList<>();
        for (String path : paths)
        {
            if (isOrificeParameter(path))
            {
                continue;
            }
            if (PENETRATOR_PARAMETER.matcher(path).matches())
            {
                penetratorParameters.add(path);
            }
            else if (OTHER_SPS_PARAMETER.matcher(path).matches())
            {
                otherSpsParameters.add(path);
            }
        }
        return new Detection(orificePairs, incompleteOrificeParameters, penetratorParameters, otherSpsParameters);
    }

    /**
     * @return all nodes whose path is matched by the given OSC address pattern (wildcards like {@code *} are
     * supported, the same way they are when listening for OSC messages), sorted by path
     */
    public static List<OscQueryNode> matchingNodes(String addressPattern, Collection<OscQueryNode> nodes)
    {
        List<OscQueryNode> matches = new ArrayList<>();
        if (addressPattern == null || addressPattern.isBlank())
        {
            return matches;
        }
        OscAddressPattern pattern = new OscAddressPattern(addressPattern);
        for (OscQueryNode node : nodes)
        {
            if (pattern.matches(node.path()))
            {
                matches.add(node);
            }
        }
        matches.sort(Comparator.comparing(OscQueryNode::path));
        return matches;
    }

    private static boolean isOrificeParameter(String path)
    {
        return path.endsWith(NEW_ROOT_SUFFIX) || path.endsWith(NEW_TIP_SUFFIX);
    }

    private static boolean isRoot(String path)
    {
        return path.endsWith(NEW_ROOT_SUFFIX);
    }

    private static String replaceSuffix(String path, String suffix, String replacement)
    {
        return path.substring(0, path.length() - suffix.length()) + replacement;
    }

    /** A root/tip proximity pair of one orifice receiver. */
    public record PenetratorPair(String rootParameter, String tipParameter)
    {
    }

    /**
     * @param orificePairs                 complete root/tip proximity pairs, which are the ORIFICE candidates
     * @param incompleteOrificeParameters  {@code *NewRoot} or {@code *NewTip} parameters without their counterpart
     * @param penetratorParameters         penetration amount parameters, which are the PENETRATOR candidates
     * @param otherSpsParameters           parameters that look SPS related but did not match either category
     */
    public record Detection(
            List<PenetratorPair> orificePairs,
            List<String> incompleteOrificeParameters,
            List<String> penetratorParameters,
            List<String> otherSpsParameters)
    {
        public boolean isEmpty()
        {
            return orificePairs.isEmpty() && incompleteOrificeParameters.isEmpty()
                    && penetratorParameters.isEmpty() && otherSpsParameters.isEmpty();
        }
    }
}
