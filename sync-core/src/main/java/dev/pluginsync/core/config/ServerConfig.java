package dev.pluginsync.core.config;

import java.util.ArrayList;
import java.util.List;

/** Admin-facing config, persisted as {@code config/pluginsync-server.json} on the server. */
public final class ServerConfig {
    private String serverName = "My Server";
    private String motd = "";
    private int httpPort = 25585;
    private String httpBind = "0.0.0.0";
    /** Public host/IP clients should use to reach the sync HTTP endpoint, e.g. "play.example.com". */
    private String publicHost = "";
    private List<ServerModConfigEntry> mods = new ArrayList<>();

    public String serverName() {
        return serverName;
    }

    public String motd() {
        return motd;
    }

    public int httpPort() {
        return httpPort;
    }

    public String httpBind() {
        return httpBind;
    }

    public String publicHost() {
        return publicHost;
    }

    public List<ServerModConfigEntry> mods() {
        return mods;
    }

    public static ServerConfig createDefault(String serverName, String publicHost) {
        ServerConfig config = new ServerConfig();
        config.serverName = serverName;
        config.publicHost = publicHost;
        return config;
    }
}
