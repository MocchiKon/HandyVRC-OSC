package org.example.handy.v3;

import handy.invoker.ApiClient;

import java.net.http.HttpClient;

/**
 * ApiClient that lets all generated API classes (HSP, info, slider, utils) share one {@link HttpClient}.
 * <p>
 * The generated {@link ApiClient#getHttpClient()} builds a new {@link HttpClient} for every API class instance it
 * is asked for, and every {@link HttpClient} has its own connection pool. Sharing a single client means all
 * requests run over the same (HTTP/2) connection: the TLS handshake is paid once and the connection is reused
 * instead of being opened again for each API class.
 */
class SharedHttpClientApiClient extends ApiClient
{
    private final HttpClient httpClient;

    SharedHttpClientApiClient(HttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    @Override
    public HttpClient getHttpClient()
    {
        return httpClient;
    }
}
