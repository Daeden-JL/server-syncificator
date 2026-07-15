package dev.pluginsync.core.config;

/** Client-facing config, persisted as {@code config/pluginsync-client.json}. */
public final class ClientConfig {
    private boolean enabled = true;
    private String syncBaseUrl = "";
    private String serverAddress = "";
    private String serverListName = "";
    private boolean autoRestart = true;
    /** Pin the server to the top of the multiplayer server list. */
    private boolean pinToServerList = true;

    public boolean enabled() {
        return enabled;
    }

    public String syncBaseUrl() {
        return syncBaseUrl;
    }

    public String serverAddress() {
        return serverAddress;
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

    public static ClientConfig create(String syncBaseUrl, String serverAddress, String serverListName) {
        ClientConfig config = new ClientConfig();
        config.syncBaseUrl = syncBaseUrl;
        config.serverAddress = serverAddress;
        config.serverListName = serverListName;
        return config;
    }
}
