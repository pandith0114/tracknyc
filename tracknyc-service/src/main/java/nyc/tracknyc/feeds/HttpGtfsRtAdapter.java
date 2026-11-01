package nyc.tracknyc.feeds;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import nyc.live.core.feed.FeedAdapter;
import nyc.live.core.feed.FeedFetchException;
import nyc.live.core.feed.FeedSnapshot;

/**
 * Fetches a live GTFS-realtime protobuf over HTTPS. Works for the MTA subway and LIRR feeds
 * (api-endpoint.mta.info) and the community PATH feed. An API key is sent only if configured —
 * the MTA portal stopped requiring one for these feeds, but older accounts/keys still work.
 */
public final class HttpGtfsRtAdapter implements FeedAdapter {

    private final String id;
    private final String system;
    private final URI url;
    private final Duration pollInterval;
    private final Duration timeout;
    private final String apiKeyHeader;
    private final String apiKey;
    private final boolean enabled;
    private final HttpClient http;
    private final Clock clock;

    public HttpGtfsRtAdapter(String id, String system, String url, Duration pollInterval, Duration timeout,
                             String apiKeyHeader, String apiKey, boolean enabled, Clock clock) {
        this.id = id;
        this.system = system;
        this.url = url == null || url.isBlank() ? null : URI.create(url);
        this.pollInterval = pollInterval;
        this.timeout = timeout;
        this.apiKeyHeader = apiKeyHeader;
        this.apiKey = apiKey;
        this.enabled = enabled && this.url != null;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String system() {
        return system;
    }

    @Override
    public Duration pollInterval() {
        return pollInterval;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    HttpRequest request() {
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Accept", "application/x-protobuf, application/octet-stream")
                .header("User-Agent", "TrackNYC/0.4 (portfolio project; non-commercial)")
                .GET();
        if (apiKey != null && !apiKey.isBlank() && apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            b.header(apiKeyHeader, apiKey);
        }
        return b.build();
    }

    @Override
    public FeedSnapshot fetch() throws FeedFetchException {
        if (!enabled) {
            throw FeedFetchException.permanent("feed " + id + " is not configured", null);
        }
        try {
            HttpResponse<byte[]> resp = http.send(request(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw FeedFetchException.http(resp.statusCode(), "feed " + id);
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                throw FeedFetchException.retryable("feed " + id + " returned an empty body", null);
            }
            return FeedSnapshot.live(id, body, clock.instant());
        } catch (HttpTimeoutException e) {
            throw FeedFetchException.retryable("feed " + id + " timed out", e);
        } catch (IOException e) {
            throw FeedFetchException.retryable("feed " + id + " I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw FeedFetchException.permanent("interrupted", e);
        }
    }
}
