package dev.pluginsync.core.selfupdate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void comparesNumericallyNotLexically() {
        // Lexical comparison would get "0.1.10" < "0.1.9" wrong - versions must compare per-component.
        assertTrue(Versions.compare("0.1.10", "0.1.9") > 0);
    }

    @Test
    void treatsMissingTrailingComponentsAsZero() {
        assertEquals(0, Versions.compare("0.2", "0.2.0"));
    }

    @Test
    void detectsANewerMajorVersion() {
        assertTrue(Versions.compare("1.0.0", "0.9.9") > 0);
    }

    @Test
    void detectsAnOlderVersion() {
        assertTrue(Versions.compare("0.1.3", "0.1.4") < 0);
    }

    @Test
    void isEqualForIdenticalVersions() {
        assertEquals(0, Versions.compare("0.1.4", "0.1.4"));
    }

    @Test
    void toleratesANonNumericComponentInsteadOfThrowing() {
        assertEquals(0, Versions.compare("abc", "0"));
    }
}
