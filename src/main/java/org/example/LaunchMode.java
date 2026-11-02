package org.example;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How this copy of the application was started. Both release archives contain the same build output, so the mode
 * cannot be decided at build time:
 * <ul>
 *     <li>The Windows release starts {@code HandyVRC-OSC.exe}. Launch4j appends the jar to the exe and puts the exe
 *     on the classpath, so the classes are loaded from a path ending in {@code .exe}. The wrapper also sets the
 *     {@value #MODE_PROPERTY} system property, which is checked first.</li>
 *     <li>The all-platforms release starts {@code HandyVRC-OSC.jar} with {@code java -jar}, so the classes are
 *     loaded from a path ending in {@code .jar}.</li>
 *     <li>A source build (IDE, {@code mvn exec:java} or the tests) loads them from a classes directory.</li>
 * </ul>
 */
@Slf4j
public enum LaunchMode
{
    WINDOWS_EXE("Windows .exe (bundled runtime)"),
    ALL_PLATFORMS_JAR("all-platforms jar"),
    DEVELOPMENT("development build (classes directory)"),
    UNKNOWN("unknown");

    /** Set by the launch4j configuration in pom.xml, so only the Windows .exe runs with it. */
    static final String MODE_PROPERTY = "handyvrc.launchMode";

    private static final String EXE_SUFFIX = ".exe";
    private static final String JAR_SUFFIX = ".jar";
    private static final Pattern PATH_SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));

    private final String description;

    LaunchMode(String description)
    {
        this.description = description;
    }

    /** @return a short human readable name for the log */
    public String description()
    {
        return description;
    }

    @Override
    public String toString()
    {
        return description;
    }

    /** @return how this process was started, never null */
    public static LaunchMode detect()
    {
        LaunchMode fromProperty = parse(System.getProperty(MODE_PROPERTY));
        if (fromProperty != null)
        {
            return fromProperty;
        }
        return detect(codeSourceLocation(), System.getProperty("java.class.path"));
    }

    /** Package visible so that the detection can be tested without starting the app in another way. */
    static LaunchMode detect(String codeSourceLocation, String classPath)
    {
        LaunchMode fromLocation = modeFor(codeSourceLocation);
        if (fromLocation != null)
        {
            return fromLocation;
        }
        // Fallback when the code source is not available: launch4j puts the exe itself on the classpath.
        for (String entry : classPathEntries(classPath))
        {
            if (isSuffix(entry, EXE_SUFFIX))
            {
                return WINDOWS_EXE;
            }
        }
        return UNKNOWN;
    }

    private static LaunchMode parse(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT))
        {
            case "windows-exe", "windows", "exe" -> WINDOWS_EXE;
            case "all-platforms", "jar" -> ALL_PLATFORMS_JAR;
            case "development", "dev" -> DEVELOPMENT;
            default ->
            {
                log.warn("Ignoring unknown value '{}' of the {} system property", value, MODE_PROPERTY);
                yield null;
            }
        };
    }

    private static LaunchMode modeFor(String location)
    {
        if (location == null || location.isBlank())
        {
            return null;
        }
        String path = location.trim();
        if (isSuffix(path, EXE_SUFFIX))
        {
            return WINDOWS_EXE;
        }
        if (isSuffix(path, JAR_SUFFIX))
        {
            return ALL_PLATFORMS_JAR;
        }
        return isDirectory(path) ? DEVELOPMENT : null;
    }

    /** @return where the classes of this application were loaded from, or null when it cannot be determined */
    private static String codeSourceLocation()
    {
        try
        {
            var protectionDomain = LaunchMode.class.getProtectionDomain();
            var codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
            var location = codeSource == null ? null : codeSource.getLocation();
            return location == null ? null : Path.of(location.toURI()).toString();
        }
        catch (URISyntaxException | RuntimeException e)
        {
            log.debug("Could not determine where the application classes were loaded from: {}", e.toString());
            return null;
        }
    }

    private static List<String> classPathEntries(String classPath)
    {
        if (classPath == null || classPath.isBlank())
        {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : PATH_SEPARATOR.split(classPath))
        {
            if (!entry.isBlank())
            {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static boolean isSuffix(String path, String suffix)
    {
        return path.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static boolean isDirectory(String path)
    {
        try
        {
            return Files.isDirectory(Path.of(path));
        }
        catch (InvalidPathException e)
        {
            return false;
        }
    }
}
