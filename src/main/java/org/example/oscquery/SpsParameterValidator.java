package org.example.oscquery;

import lombok.extern.slf4j.Slf4j;
import org.example.processor.SpsType;

import java.util.Collection;
import java.util.List;

/**
 * Logs the SPS parameters that VRChat currently exposes through OSCQuery and checks the configured avatar
 * parameters against them. Purely informational: receiving and processing of OSC messages never depends on it.
 */
@Slf4j
public final class SpsParameterValidator
{
    /** Upper limit for the extra list of SPS-like parameters, just to keep the log readable. */
    private static final int MAX_OTHER_PARAMETERS_LOGGED = 50;

    private SpsParameterValidator()
    {
    }

    /**
     * Logs all penetrator/orifice candidates found in the OSC namespace of VRChat and validates the configured
     * parameters against them.
     *
     * @param parameters      all nodes VRChat exposes below {@code /avatar/parameters}
     * @param rootParameter   configured {@code avatarParameter} (may contain OSC wildcards)
     * @param tipParameter    configured {@code penetratorTipParameter} (only used for ORIFICE, may be null)
     * @param spsType         configured SPS type
     */
    public static void logReport(Collection<OscQueryNode> parameters, String rootParameter, String tipParameter, SpsType spsType)
    {
        if (parameters.isEmpty())
        {
            log.info("OSCQuery did not report any avatar parameter yet - is an avatar loaded in VRChat?");
            return;
        }

        SpsParameterDetector.Detection detection = SpsParameterDetector.detect(parameters);
        log.info(buildCandidatesReport(parameters.size(), detection));
        logValidation(parameters, rootParameter, spsType == SpsType.ORIFICE ? tipParameter : null);
    }

    private static String buildCandidatesReport(int parameterCount, SpsParameterDetector.Detection detection)
    {
        StringBuilder report = new StringBuilder();
        report.append("SPS parameter candidates reported by VRChat OSCQuery (%d avatar parameter(s) in total):"
                .formatted(parameterCount));
        appendOrificeCandidates(report, detection);
        appendPenetratorCandidates(report, detection);
        appendOtherCandidates(report, detection);
        return report.toString();
    }

    private static void appendOrificeCandidates(StringBuilder report, SpsParameterDetector.Detection detection)
    {
        List<SpsParameterDetector.PenetratorPair> pairs = detection.orificePairs();
        List<String> incomplete = detection.incompleteOrificeParameters();
        if (pairs.isEmpty() && incomplete.isEmpty())
        {
            report.append("\n  ORIFICE candidates: none (no *NewRoot/*NewTip proximity parameters)");
            return;
        }
        report.append("\n  ORIFICE candidates (root proximity -> tip proximity):");
        for (SpsParameterDetector.PenetratorPair pair : pairs)
        {
            report.append("\n    ").append(pair.rootParameter());
            report.append("\n      -> ").append(pair.tipParameter());
        }
        for (String parameter : incomplete)
        {
            String missingCounterpart = parameter.endsWith(SpsParameterDetector.NEW_ROOT_SUFFIX)
                    ? SpsParameterDetector.NEW_TIP_SUFFIX
                    : SpsParameterDetector.NEW_ROOT_SUFFIX;
            report.append("\n    ").append(parameter)
                    .append(" (INCOMPLETE: its ").append(missingCounterpart).append(" counterpart is missing)");
        }
    }

    private static void appendPenetratorCandidates(StringBuilder report, SpsParameterDetector.Detection detection)
    {
        List<String> penetrators = detection.penetratorParameters();
        if (penetrators.isEmpty())
        {
            report.append("\n  PENETRATOR candidates: none (no PenOthers/PenSelf parameters)");
            return;
        }
        report.append("\n  PENETRATOR candidates (penetration amount):");
        for (String parameter : penetrators)
        {
            report.append("\n    ").append(parameter);
        }
    }

    private static void appendOtherCandidates(StringBuilder report, SpsParameterDetector.Detection detection)
    {
        List<String> others = detection.otherSpsParameters();
        if (others.isEmpty())
        {
            return;
        }
        int logged = Math.min(others.size(), MAX_OTHER_PARAMETERS_LOGGED);
        report.append("\n  Other SPS-like parameters (%d, showing %d):".formatted(others.size(), logged));
        for (String parameter : others.subList(0, logged))
        {
            report.append("\n    ").append(parameter);
        }
    }

    private static void logValidation(Collection<OscQueryNode> parameters, String rootParameter, String tipParameter)
    {
        log.info("Validating configured avatar parameters against the OSCQuery namespace of VRChat:");
        logMatchedParameter("avatarParameter", rootParameter, parameters);
        if (tipParameter != null)
        {
            logMatchedParameter("penetratorTipParameter", tipParameter, parameters);
        }
    }

    private static void logMatchedParameter(String propertyName, String pattern, Collection<OscQueryNode> parameters)
    {
        List<OscQueryNode> matches = SpsParameterDetector.matchingNodes(pattern, parameters);
        if (matches.isEmpty())
        {
            log.warn("  {} '{}' does NOT match any parameter exposed by VRChat right now! Check the value in"
                    + " app.properties and the candidates logged above.", propertyName, pattern);
            return;
        }
        log.info("  {} '{}' matches {} parameter(s):", propertyName, pattern, matches.size());
        for (OscQueryNode match : matches)
        {
            log.info("    {}", match.describe());
            if (!match.isNumeric())
            {
                log.warn("      Parameter '{}' is not a numeric parameter, so it cannot be used to move the device!",
                        match.path());
            }
        }
    }
}
