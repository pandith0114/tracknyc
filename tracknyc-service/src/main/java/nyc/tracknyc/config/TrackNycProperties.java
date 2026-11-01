package nyc.tracknyc.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything configurable under {@code tracknyc.*}. Secrets (the optional MTA key) come from
 * environment variables only — see .env.example.
 */
@ConfigurationProperties(prefix = "tracknyc")
public class TrackNycProperties {

    /** {@code replay} (default: recorded fixtures, no keys needed) or {@code live}. */
    private String mode = "replay";
    /** Root of the fixtures directory (gtfs/ and realtime/). */
    private String fixturesDir = "fixtures";
    /** {@code next-stop} (milestone 1) or {@code interpolate}. */
    private String positionMode = "interpolate";
    /** {@code linear} or {@code trapezoidal}. */
    private String motionProfile = "linear";
    /** How often positions are recomputed and published. */
    private Duration tickInterval = Duration.ofSeconds(5);
    /** Data older than this marks a feed STALE and shows the warning banner. */
    private Duration staleAfter = Duration.ofSeconds(60);
    /** Optional MTA API key (header {@code x-api-key}); only sent if non-empty. */
    private String mtaApiKey = "";

    private final StaticData staticData = new StaticData();
    private final State state = new State();
    private final Push push = new Push();
    private final RateLimit rateLimit = new RateLimit();
    private final Stale stale = new Stale();
    private final Map<String, SystemConfig> systems = new LinkedHashMap<>();
    private final List<FeedConfig> feeds = new ArrayList<>();

    public boolean isReplay() {
        return !"live".equalsIgnoreCase(mode);
    }

    public static class StaticData {
        /** {@code postgis} (default, Flyway + PostGIS) or {@code file} (in-memory, no database). */
        private String source = "postgis";
        /** Cron for the nightly static GTFS refresh (live mode, systems with a static-url). */
        private String refreshCron = "0 30 3 * * *";
        /** Where downloaded GTFS zips are cached. */
        private String downloadDir = System.getProperty("java.io.tmpdir") + "/tracknyc-gtfs";

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getRefreshCron() { return refreshCron; }
        public void setRefreshCron(String refreshCron) { this.refreshCron = refreshCron; }
        public String getDownloadDir() { return downloadDir; }
        public void setDownloadDir(String downloadDir) { this.downloadDir = downloadDir; }
    }

    public static class State {
        /** {@code redis} (default) or {@code memory} (single process, no Redis). */
        private String store = "redis";
        private String keyPrefix = "tracknyc";
        /** TTL of each train's latest state in the store. */
        private Duration ttl = Duration.ofMinutes(5);
        /** Comma list: {@code ingest} (poll + compute + publish) and/or {@code push} (serve clients). */
        private String roles = "ingest,push";

        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public String getRoles() { return roles; }
        public void setRoles(String roles) { this.roles = roles; }
        public boolean hasRole(String role) {
            for (String r : roles.split(",")) {
                if (r.trim().equalsIgnoreCase(role)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class Push {
        private Duration flushInterval = Duration.ofSeconds(5);
        private Duration heartbeatInterval = Duration.ofSeconds(15);
        private Duration emitterTimeout = Duration.ofMinutes(30);
        private int maxClients = 5000;

        public Duration getFlushInterval() { return flushInterval; }
        public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
        public Duration getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public Duration getEmitterTimeout() { return emitterTimeout; }
        public void setEmitterTimeout(Duration emitterTimeout) { this.emitterTimeout = emitterTimeout; }
        public int getMaxClients() { return maxClients; }
        public void setMaxClients(int maxClients) { this.maxClients = maxClients; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private double requestsPerSecond = 20;
        private int burst = 40;
        /** Trust X-Forwarded-For (only behind your own reverse proxy). */
        private boolean trustForwardedFor = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getRequestsPerSecond() { return requestsPerSecond; }
        public void setRequestsPerSecond(double requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
        public boolean isTrustForwardedFor() { return trustForwardedFor; }
        public void setTrustForwardedFor(boolean trustForwardedFor) { this.trustForwardedFor = trustForwardedFor; }
    }

    /** Stale-train rules (milestone 4). */
    public static class Stale {
        /** Train data older than this is drawn faded. */
        private Duration fadeAfter = Duration.ofSeconds(90);
        /** Train data older than this is removed. */
        private Duration removeAfter = Duration.ofSeconds(300);
        /** A train missing from a fresh feed is kept (faded) this long, then removed. */
        private Duration missingGrace = Duration.ofSeconds(60);

        public Duration getFadeAfter() { return fadeAfter; }
        public void setFadeAfter(Duration fadeAfter) { this.fadeAfter = fadeAfter; }
        public Duration getRemoveAfter() { return removeAfter; }
        public void setRemoveAfter(Duration removeAfter) { this.removeAfter = removeAfter; }
        public Duration getMissingGrace() { return missingGrace; }
        public void setMissingGrace(Duration missingGrace) { this.missingGrace = missingGrace; }
    }

    public static class SystemConfig {
        private String displayName;
        /** Unpacked dir or .zip used in replay mode / when no URL is set. Relative to fixtures-dir. */
        private String staticPath;
        /** Official static GTFS download (used in live mode, refreshed nightly). */
        private String staticUrl;
        /** {@code nyct} or {@code exact}. */
        private String tripMatching = "exact";
        private String timezone = "America/New_York";
        /** Attribution shown in the UI. */
        private String attribution;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getStaticPath() { return staticPath; }
        public void setStaticPath(String staticPath) { this.staticPath = staticPath; }
        public String getStaticUrl() { return staticUrl; }
        public void setStaticUrl(String staticUrl) { this.staticUrl = staticUrl; }
        public String getTripMatching() { return tripMatching; }
        public void setTripMatching(String tripMatching) { this.tripMatching = tripMatching; }
        public String getTimezone() { return timezone; }
        public void setTimezone(String timezone) { this.timezone = timezone; }
        public String getAttribution() { return attribution; }
        public void setAttribution(String attribution) { this.attribution = attribution; }
    }

    public static class FeedConfig {
        private String id;
        private String system;
        /** Live GTFS-realtime URL. */
        private String url;
        /** Recording directory for replay mode, relative to fixtures-dir. Empty = no replay. */
        private String replayDir;
        private Duration pollInterval = Duration.ofSeconds(15);
        private Duration timeout = Duration.ofSeconds(10);
        private String apiKeyHeader = "x-api-key";
        /** Set false to switch a live feed off entirely (e.g. the optional PATH feed). */
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getReplayDir() { return replayDir; }
        public void setReplayDir(String replayDir) { this.replayDir = replayDir; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public String getApiKeyHeader() { return apiKeyHeader; }
        public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getFixturesDir() { return fixturesDir; }
    public void setFixturesDir(String fixturesDir) { this.fixturesDir = fixturesDir; }
    public String getPositionMode() { return positionMode; }
    public void setPositionMode(String positionMode) { this.positionMode = positionMode; }
    public String getMotionProfile() { return motionProfile; }
    public void setMotionProfile(String motionProfile) { this.motionProfile = motionProfile; }
    public Duration getTickInterval() { return tickInterval; }
    public void setTickInterval(Duration tickInterval) { this.tickInterval = tickInterval; }
    public Duration getStaleAfter() { return staleAfter; }
    public void setStaleAfter(Duration staleAfter) { this.staleAfter = staleAfter; }
    public String getMtaApiKey() { return mtaApiKey; }
    public void setMtaApiKey(String mtaApiKey) { this.mtaApiKey = mtaApiKey; }
    public StaticData getStaticData() { return staticData; }
    public State getState() { return state; }
    public Push getPush() { return push; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Stale getStale() { return stale; }
    public Map<String, SystemConfig> getSystems() { return systems; }
    public List<FeedConfig> getFeeds() { return feeds; }
}
