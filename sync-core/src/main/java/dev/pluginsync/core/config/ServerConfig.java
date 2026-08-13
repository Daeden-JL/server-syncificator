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
    /**
     * When true (the default), the server checks GitHub for a newer syncificator release itself -
     * on startup and once a day while running - and downloads it if found. The old jar is removed
     * immediately if possible, or queued for removal the moment this JVM exits otherwise; either
     * way the update only takes effect the next time an admin restarts the server themselves - this
     * never triggers a restart on its own. Once the new jar is being served from the mods folder,
     * clients pick it up the same way they pick up any other updated mod.
     */
    private boolean selfUpdateEnabled = true;
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

    public boolean selfUpdateEnabled() {
        return selfUpdateEnabled;
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
