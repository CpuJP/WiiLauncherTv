package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionsTest {
    @Test public void newerPatchMinorMajor() {
        assertTrue(Versions.isNewer("v1.0.1", "1.0.0"));
        assertTrue(Versions.isNewer("1.1.0", "1.0.9"));
        assertTrue(Versions.isNewer("2.0", "1.99.99"));
    }

    @Test public void numericNotLexical() {
        assertTrue(Versions.isNewer("1.0.10", "1.0.9"));
    }

    @Test public void sameOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("v1.0.0", "1.0.0"));
        assertFalse(Versions.isNewer("1.0", "1.0.0"));
        assertFalse(Versions.isNewer("0.9.9", "1.0.0"));
    }

    @Test public void garbageIsNeverNewer() {
        assertFalse(Versions.isNewer(null, "1.0.0"));
        assertFalse(Versions.isNewer("", "1.0.0"));
        assertFalse(Versions.isNewer("latest", "1.0.0"));
    }

    @Test public void preReleaseSuffixIgnored() {
        assertFalse(Versions.isNewer("1.0.0-beta", "1.0.0"));
    }
}
