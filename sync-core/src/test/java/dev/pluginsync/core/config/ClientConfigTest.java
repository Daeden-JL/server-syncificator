package dev.pluginsync.core.config;

import dev.pluginsync.core.json.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTest {

    @Test
    void freshConfigIsNotConfiguredAndYieldsNoUrls() {
        ClientConfig config = ClientConfig.createDefault();

        assertFalse(config.isConfigured());
        assertEquals("", config.syncBaseUrl(), "an unconfigured client must not invent a URL to call");
        assertEquals("", config.serverAddress());
    }

    @Test
    void syncBaseUrlIsDerivedFromHostAndSyncPort() {
        ClientConfig config = ClientConfig.create("play.example.com", 25585, 25565);

        assertTrue(config.isConfigured());
        assertEquals("http://play.example.com:25585", config.syncBaseUrl());
    }

    @Test
    void serverAddressOmitsTheDefaultMinecraftPort() {
        ClientConfig config = ClientConfig.create("play.example.com", 25585, 25565);

        assertEquals("play.example.com", config.serverAddress());
    }

    @Test
    void serverAddressIncludesANonDefaultMinecraftPort() {
        ClientConfig config = ClientConfig.create("play.example.com", 25585, 25566);

        assertEquals("play.example.com:25566", config.serverAddress());
    }

    @Test
    void syncAndMinecraftPortsAreIndependent() {
        ClientConfig config = ClientConfig.create("host", 9000, 25566);

        assertEquals("http://host:9000", config.syncBaseUrl());
        assertEquals("host:25566", config.serverAddress());
    }

    @Test
    void hostIsTrimmedSoAStraySpaceDoesNotBreakTheUrl() {
        ClientConfig config = ClientConfig.createDefault();
        config.setServerHost("  play.example.com  ");

        assertEquals("play.example.com", config.serverHost());
        assertEquals("http://play.example.com:25585", config.syncBaseUrl());
    }

    @Test
    void normalizeRepairsOutOfRangePorts() {
        ClientConfig config = ClientConfig.createDefault();
        config.setSyncPort(0);
        config.setMinecraftPort(99999);

        config.normalize();

        assertEquals(ServerConfig.DEFAULT_HTTP_PORT, config.syncPort());
        assertEquals(ClientConfig.DEFAULT_MINECRAFT_PORT, config.minecraftPort());
    }

    @Test
    void normalizeSurvivesExplicitJsonNulls() {
        // Gson writes an explicit "serverHost": null straight into the field, bypassing the
        // initializer - so normalize() has to cope with a null the constructor could never produce.
        ClientConfig config = JsonCodec.fromJson("{\"serverHost\":null,\"serverListName\":null}", ClientConfig.class);

        config.normalize();

        assertEquals("", config.serverHost());
        assertEquals("", config.serverListName());
        assertFalse(config.isConfigured());
    }

    @Test
    void copyIsIndependentOfTheOriginal() {
        ClientConfig original = ClientConfig.create("host", 25585, 25565);

        ClientConfig copy = original.copy();
        copy.setServerHost("other");
        copy.setEnabled(false);

        assertEquals("host", original.serverHost(), "editing a copy must not touch the saved config");
        assertTrue(original.enabled());
        assertEquals("other", copy.serverHost());
    }
}
