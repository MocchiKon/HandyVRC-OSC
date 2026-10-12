package org.example.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes app.properties with the default settings to the given path. Used by the packageForRelease Maven profile, so
 * that the release zips ship the same file the app would create on its first start.
 */
public class DefaultConfigWriter
{
    public static void main(String[] args) throws IOException
    {
        if (args.length != 1)
        {
            throw new IllegalArgumentException("Expected the path of the app.properties to write");
        }
        Path path = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(path.getParent());
        new ConfigLoader(path).save(ConfigProperties.defaults());
    }
}
