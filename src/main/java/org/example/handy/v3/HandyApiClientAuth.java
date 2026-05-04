package org.example.handy.v3;

import handy.invoker.ApiClient;
import lombok.NoArgsConstructor;

import java.net.http.HttpRequest;
import java.util.function.Consumer;

/**
 * Helpers for configuring authentication headers on the generated OpenAPI ApiClient.
 *
 * <p>The generated Java client does not expose OpenAPI security schemes as explicit
 * method parameters on API classes. Instead, auth headers must be attached
 * to each request through the ApiClient request interceptor.</p>
 **/
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class HandyApiClientAuth {
    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    public static ApiClient applyApiKey(ApiClient apiClient, String apiKey) {
        requireNonBlank(apiClient, "apiClient");
        requireNonBlank(apiKey, "apiKey");
        return appendInterceptor(apiClient, builder -> builder.setHeader(API_KEY_HEADER, apiKey));
    }

    public static ApiClient applyBearerToken(ApiClient apiClient, String bearerToken) {
        requireNonBlank(apiClient, "apiClient");
        requireNonBlank(bearerToken, "bearerToken");
        return appendInterceptor(apiClient, builder -> builder.setHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + bearerToken));
    }

    private static ApiClient appendInterceptor(ApiClient apiClient, Consumer<HttpRequest.Builder> additionalInterceptor) {
        Consumer<HttpRequest.Builder> existingInterceptor = apiClient.getRequestInterceptor();
        apiClient.setRequestInterceptor(builder -> {
            if (existingInterceptor != null) {
                existingInterceptor.accept(builder);
            }
            additionalInterceptor.accept(builder);
        });
        return apiClient;
    }

    private static void requireNonBlank(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

