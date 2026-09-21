package org.example.oscquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reads the OSC address space that another OSCQuery service (VRChat) serves over HTTP.
 * <p>
 * Only used for validating the configured avatar parameters, so every problem is handled gracefully and the
 * returned list is simply empty when the tree cannot be read.
 */
@Slf4j
public class OscQueryTreeClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AVATAR_PARAMETERS_PATH = "/avatar/parameters";
    private static final int HTTP_OK = 200;

    private final HttpClient httpClient;
    private final Duration timeout;

    public OscQueryTreeClient(Duration timeout)
    {
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Fetches all avatar parameters of the given service, trying all of its addresses and finally loopback
     * (VRChat on Windows only serves its OSCQuery tree to applications on the same machine).
     *
     * @return all parameters of the avatar that is currently loaded, or an empty list when they cannot be read
     */
    public List<OscQueryNode> fetchAvatarParameters(OscQueryServiceProfile profile)
    {
        for (InetAddress address : addressesToTry(profile))
        {
            try
            {
                List<OscQueryNode> parameters = fetchAvatarParameters(address, profile.port());
                if (!parameters.isEmpty())
                {
                    return parameters;
                }
            }
            catch (IOException e)
            {
                log.debug("Could not read OSCQuery tree of {} at {}: {}", profile.name(),
                        address.getHostAddress(), e.getMessage());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
        return List.of();
    }

    List<OscQueryNode> fetchAvatarParameters(InetAddress address, int port) throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(rootUri(address, port))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_OK)
        {
            throw new IOException("OSCQuery service answered with status " + response.statusCode());
        }
        return parseAvatarParameters(OBJECT_MAPPER.readTree(response.body()));
    }

    /**
     * Walks an OSCQuery tree and collects every leaf below {@code /avatar/parameters}, which are the avatar
     * parameters VRChat currently exposes.
     */
    static List<OscQueryNode> parseAvatarParameters(JsonNode root)
    {
        List<OscQueryNode> parameters = new ArrayList<>();
        collectParameters(root, "/", parameters);
        return parameters;
    }

    private static void collectParameters(JsonNode node, String nodePath, List<OscQueryNode> parameters)
    {
        if (node == null || !node.isObject())
        {
            return;
        }
        String path = node.hasNonNull("FULL_PATH") ? node.get("FULL_PATH").asText() : nodePath;
        JsonNode contents = node.get("CONTENTS");
        if (contents == null || !contents.isObject() || contents.isEmpty())
        {
            // A node without children is an OSC method (a concrete parameter)
            if (path.startsWith(AVATAR_PARAMETERS_PATH + "/"))
            {
                parameters.add(new OscQueryNode(path,
                        node.hasNonNull("TYPE") ? node.get("TYPE").asText() : null,
                        node.hasNonNull("ACCESS") ? node.get("ACCESS").asInt() : null));
            }
            return;
        }
        contents.fields().forEachRemaining(entry ->
                collectParameters(entry.getValue(), childPath(path, entry.getKey()), parameters));
    }

    private static String childPath(String parentPath, String childName)
    {
        return parentPath.endsWith("/") ? parentPath + childName : parentPath + "/" + childName;
    }

    private static List<InetAddress> addressesToTry(OscQueryServiceProfile profile)
    {
        // A service may advertise an address it does not actually listen on, loopback is the fallback
        LinkedHashSet<InetAddress> addresses = new LinkedHashSet<>(profile.addresses());
        try
        {
            addresses.add(InetAddress.getByName("127.0.0.1"));
        }
        catch (UnknownHostException e)
        {
            log.debug("127.0.0.1 does not resolve: {}", e.getMessage());
        }
        return List.copyOf(addresses);
    }

    private static URI rootUri(InetAddress address, int port) throws IOException
    {
        try
        {
            return new URI("http", null, address.getHostAddress(), port, "/", null, null);
        }
        catch (URISyntaxException e)
        {
            throw new IOException("Invalid OSCQuery service address " + address.getHostAddress(), e);
        }
    }
}
