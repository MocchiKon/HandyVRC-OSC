package org.example.handy.wifi;

import lombok.NoArgsConstructor;
import org.example.config.ConfigProperties;
import org.example.handy.common.HandyClient;

import java.io.IOException;
import java.net.URI;

/**
 * Connects to the Handy through the Handy servers (Handy API v3) with the connection key and application ID from the
 * settings. The Handy itself has to be connected to Wi-Fi; the app only talks to the servers.
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class HandyWifiConnector
{
    public static HandyClient connect(String connectionKey, String applicationId) throws IOException
    {
        return connect(HandyWifiApi.DEFAULT_BASE_URI, connectionKey, applicationId);
    }

    /**
     * @param baseUri API base URI, normally the production Handy servers; a local server can be used in tests
     */
    public static HandyClient connect(URI baseUri, String connectionKey, String applicationId) throws IOException
    {
        String key = connectionKey == null ? "" : connectionKey.trim();
        String appId = applicationId == null || applicationId.isBlank()
                ? ConfigProperties.DEFAULT_HANDY_APPLICATION_ID
                : applicationId.trim();
        String keyError = ConfigProperties.validateConnectionKey(key);
        if (keyError != null)
        {
            throw new IOException(keyError);
        }

        HandyWifiApi api;
        try
        {
            api = new HandyWifiApi(baseUri, appId, key);
        }
        catch (IOException e)
        {
            throw new IOException("Could not reach the Handy servers: " + e.getMessage(), e);
        }
        HandyWifiClient client = new HandyWifiClient(api, key);
        try
        {
            client.verifyConnected();
            return client;
        }
        catch (IOException | RuntimeException e)
        {
            client.close();
            throw e;
        }
    }
}
