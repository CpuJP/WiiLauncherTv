package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeGuardTest {
    private static final String SELF = "io.github.cpujp.wiilauncher/io.github.cpujp.wiilauncher.HomeKeyService";

    @Test public void addsToEmptyOrNull() {
        assertEquals(SELF, HomeGuard.withService(null, SELF));
        assertEquals(SELF, HomeGuard.withService("", SELF));
        assertEquals(SELF, HomeGuard.withService("null", SELF));
    }

    @Test public void keepsOtherServices() {
        assertEquals("a/.B:" + SELF, HomeGuard.withService("a/.B", SELF));
    }

    @Test public void noDuplicate() {
        assertEquals("a/.B:" + SELF, HomeGuard.withService("a/.B:" + SELF, SELF));
    }

    @Test public void onlyStockHomeScreensAreReplaced() {
        assertTrue(HomeKeyService.isStockHome("com.google.android.apps.tv.launcherx.home.HomeActivity"));
        assertTrue(HomeKeyService.isStockHome("com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity"));
        assertFalse(HomeKeyService.isStockHome("com.google.android.apps.tv.launcherx.profile.ProfileActivity"));
        assertFalse(HomeKeyService.isStockHome(null));
    }
}
