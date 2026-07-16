package dev.pluginsync.core.config;

/**
 * Client-facing config, persisted as {@code config/<modid>-client.json} by {@link ClientConfigStore}.
 *
 * <p>The server is described as a plain host plus two ports rather than a URL: {@code syncPort} is
 * the companion HTTP endpoint this mod talks to (see {@link ServerConfig#DEFAULT_HTTP_PORT}), and
 * {@code minecraftPort} is the game port used when pinning the server to the multiplayer list.
 * They're usually different, and hand-writing a URL for one of them is easy to get wrong, so the
 * URL is derived rather than stored - see {@link #syncBaseUrl()}.
 *
 * <p>Mutable, because the in-game config screen edits an instance in place before saving it.
 */
public final class ClientConfig {

    /** Minecraft's default server port, omitted from a server-list address when it's in use. */
    public static final int DEFAULT_MINECRAFT_PORT = 25565;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private boolean enabled = true;
    private String serverHost = "";
    private int syncPort = ServerConfig.DEFAULT_HTTP_PORT;
    private int minecraftPort = DEFAULT_MINECRAFT_PORT;
    /** Name shown in the multiplayer list; falls back to the address itself when blank. */
    private String serverListName = "";
    private boolean autoRestart = true;
    /** Pin the server to the top of the multiplayer server list. */
    private boolean pinToServerList = true;

    public boolean enabled() {
        return enabled;
    }

    public String serverHost() {
        return serverHost;
    }

    public int syncPort() {
        return syncPort;
    }

    public int minecraftPort() {
        return minecraftPort;
    }

    public String serverListName() {
        return serverListName;
    }

    public boolean autoRestart() {
        return autoRestart;
    }

    public boolean pinToServerList() {
        return pinToServerList;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setServerHost(String serverHost) {
        this.serverHost = serverHost == null ? "" : serverHost.trim();
    }

    public void setSyncPort(int syncPort) {
        this.syncPort = syncPort;
    }

    public void setMinecraftPort(int minecraftPort) {
        this.minecraftPort = minecraftPort;
    }

    public void setServerListName(String serverListName) {
        this.serverListName = serverListName == null ? "" : serverListName.trim();
    }

    public void setAutoRestart(boolean autoRestart) {
        this.autoRestart = autoRestart;
    }

    public void setPinToServerList(boolean pinToServerList) {
        this.pinToServerList = pinToServerList;
    }

    /** True once there's a host to sync against - until then the mod stays out of the way. */
    public boolean isConfigured() {
        return !serverHost.isEmpty();
    }

    /** Base URL of the companion HTTP endpoint, or {@code ""} when no host is configured. */
    public String syncBaseUrl() {
        return isConfigured() ? "http://" + serverHost + ":" + syncPort : "";
    }

    /**
     * Address to pin to the multiplayer list, or {@code ""} when no host is configured. The port is
     * omitted when it's Minecraft's default, matching what a player would type by hand.
     */
    public String serverAddress() {
        if (!isConfigured()) {
            return "";
        }
        return minecraftPort == DEFAULT_MINECRAFT_PORT ? serverHost : serverHost + ":" + minecraftPort;
    }

    /**
     * Repairs anything a hand-edited file (or a stale schema) could leave in an unusable state:
     * Gson leaves missing keys at their defaults but writes explicit {@code null}s straight into
     * String fields, and nothing stops a user typing a port of 0 or 99999.
     */
    public ClientConfig normalize() {
        serverHost = serverHost == null ? "" : serverHost.trim();
        serverListName = serverListName == null ? "" : serverListName.trim();
        if (!isValidPort(syncPort)) {
            syncPort = ServerConfig.DEFAULT_HTTP_PORT;
        }
        if (!isValidPort(minecraftPort)) {
            minecraftPort = DEFAULT_MINECRAFT_PORT;
        }
        return this;
    }

    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    public static ClientConfig createDefault() {
        return new ClientConfig();
    }

    public static ClientConfig create(String serverHost, int syncPort, int minecraftPort) {
        ClientConfig config = new ClientConfig();
        config.setServerHost(serverHost);
        config.syncPort = syncPort;
        config.minecraftPort = minecraftPort;
        return config;
    }

    /** Field-by-field copy, so the config screen can edit a scratch instance and discard it. */
    public ClientConfig copy() {
        ClientConfig copy = new ClientConfig();
        copy.enabled = enabled;
        copy.serverHost = serverHost;
        copy.syncPort = syncPort;
        copy.minecraftPort = minecraftPort;
        copy.serverListName = serverListName;
        copy.autoRestart = autoRestart;
        copy.pinToServerList = pinToServerList;
        return copy;
    }
}
