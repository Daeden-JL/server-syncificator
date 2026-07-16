package dev.pluginsync.core.config;

import java.util.ArrayList;
import java.util.List;

/** Admin-facing config, persisted as {@code config/pluginsync-server.json} on the server. */
public final class ServerConfig {

    /**
     * Port the companion manifest/download HTTP server listens on. Deliberately not Minecraft's
     * own port - this is a plain HTTP server, so it needs a socket of its own. Clients default to
     * the same number via {@link ClientConfig}.
     */
    public static final int DEFAULT_HTTP_PORT = 25585;

    private String serverName = "My Server";
    private String motd = "";
    private int httpPort = DEFAULT_HTTP_PORT;
    private String httpBind = "0.0.0.0";
    /**
     * When true (the default), every jar in the server's mods folder is advertised to clients, and
     * {@link #mods} is only needed to override individual files - to mark one {@code SERVER_ONLY}
     * so it's withheld, or to give one a Modrinth download URL. Set false to go back to serving
     * <em>only</em> what {@link #mods} lists.
     */
    private boolean autoServeModsFolder = true;
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

    public boolean autoServeModsFolder() {
        return autoServeModsFolder;
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
