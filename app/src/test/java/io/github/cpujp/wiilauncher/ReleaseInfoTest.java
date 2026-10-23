package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReleaseInfoTest {
    private static final String OK_URL = Updater.RELEASE_DOWNLOAD_PREFIX + "v1.2.0/WiiLauncherTv-1.2.0.apk";

    @Test public void picksApkAsset() throws Exception {
        String json = "{\"tag_name\":\"v1.2.0\",\"body\":\"notes\",\"assets\":["
                + "{\"name\":\"checksums.txt\",\"browser_download_url\":\"x\"},"
                + "{\"name\":\"WiiLauncherTv-1.2.0.apk\",\"browser_download_url\":\"" + OK_URL + "\"}]}";
        ReleaseInfo r = ReleaseInfo.parse(json);
        assertEquals("v1.2.0", r.tag);
        assertEquals(OK_URL, r.apkUrl);
        assertEquals("notes", r.notes);
    }

    @Test public void rejectsForeignDownloadHost() throws Exception {
        String json = "{\"tag_name\":\"v9\",\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"https://evil.example/a.apk\"}]}";
        assertNull(ReleaseInfo.parse(json));
    }

    @Test public void ignoresPrereleaseAndMissingAssets() throws Exception {
        assertNull(ReleaseInfo.parse("{\"tag_name\":\"v2\",\"prerelease\":true,\"assets\":[]}"));
        assertNull(ReleaseInfo.parse("{\"tag_name\":\"v2\"}"));
    }
}
