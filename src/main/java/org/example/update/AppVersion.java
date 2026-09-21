package org.example.update;

import java.util.Optional;

/**
 * A version of the application (major.minor.patch). Parts are compared as numbers, so "0.10" is newer than "0.9",
 * and a missing part counts as zero, so "0.2" and "0.2.0" are the same version.
 */
public record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion>
{
    private static final int PARTS = 3;

    /**
     * Parses a version text like "0.2", "v0.2.1" or "0.3.1-beta". A leading 'v' and any pre-release/build suffix
     * ("-rc1", "+build5") are ignored, and a part may have a trailing non-numeric suffix ("0.3b" is read as "0.3").
     *
     * @return the parsed version or an empty optional when the text does not contain a usable version
     */
    public static Optional<AppVersion> parse(String text)
    {
        if (text == null)
        {
            return Optional.empty();
        }
        String version = cutOffSuffix(text.trim());
        String[] parts = version.split("\\.", -1);
        if (parts.length == 0 || parts.length > PARTS)
        {
            return Optional.empty();
        }
        int[] numbers = new int[PARTS];
        for (int i = 0; i < parts.length; i++)
        {
            Optional<Integer> number = parseLeadingNumber(parts[i]);
            if (number.isEmpty())
            {
                return Optional.empty();
            }
            numbers[i] = number.get();
        }
        return Optional.of(new AppVersion(numbers[0], numbers[1], numbers[2]));
    }

    public boolean isNewerThan(AppVersion other)
    {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(AppVersion other)
    {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0)
        {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        if (minorComparison != 0)
        {
            return minorComparison;
        }
        return Integer.compare(patch, other.patch);
    }

    /** The version without the parts that are zero, so "0.2.0" is printed as "0.2". */
    @Override
    public String toString()
    {
        return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
    }

    private static String cutOffSuffix(String text)
    {
        String withoutLeadingV = text.startsWith("v") || text.startsWith("V") ? text.substring(1) : text;
        int end = withoutLeadingV.length();
        for (char suffixStart : new char[]{'-', '+'})
        {
            int index = withoutLeadingV.indexOf(suffixStart);
            if (index >= 0)
            {
                end = Math.min(end, index);
            }
        }
        return withoutLeadingV.substring(0, end).trim();
    }

    /** Reads the digits a version part starts with, so "01" and "1b" both give 1. */
    private static Optional<Integer> parseLeadingNumber(String part)
    {
        int digitsEnd = 0;
        while (digitsEnd < part.length() && Character.isDigit(part.charAt(digitsEnd)))
        {
            digitsEnd++;
        }
        if (digitsEnd == 0)
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(Integer.parseInt(part.substring(0, digitsEnd)));
        }
        catch (NumberFormatException e) // more digits than an int can hold
        {
            return Optional.empty();
        }
    }
}
