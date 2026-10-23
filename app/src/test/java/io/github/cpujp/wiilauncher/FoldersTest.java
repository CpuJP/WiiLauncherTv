package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class FoldersTest {
    @Test public void createAddAndRoundTrip() {
        Folders f = new Folders();
        Folders.Folder a = f.create("Streaming");
        f.add(a.id, "com.netflix");
        f.add(a.id, "com.youtube");
        Folders back = Folders.parse(f.toJson());
        assertEquals("Streaming", back.get(a.id).name);
        assertEquals(Arrays.asList("com.netflix", "com.youtube"), back.get(a.id).pkgs);
        assertEquals(a.id, Folders.idFromKey(a.key()));
    }

    @Test public void appLivesInOneFolderOnly() {
        Folders f = new Folders();
        Folders.Folder a = f.create("A"), b = f.create("B");
        f.add(a.id, "x");
        f.add(b.id, "x");
        assertTrue(a.pkgs.isEmpty());
        assertEquals(b.id, f.folderOf("x"));
    }

    @Test public void idsAreUnique() {
        Folders f = new Folders();
        String first = f.create("A").id;
        f.delete(first);
        f.create("B");
        assertEquals(1, f.all().size());
        assertTrue(!f.create("C").id.equals(f.all().iterator().next().id));
    }

    @Test public void pruneDropsUninstalledAndEmpty() {
        Folders f = new Folders();
        Folders.Folder a = f.create("A"), b = f.create("B");
        f.add(a.id, "keep");
        f.add(a.id, "gone");
        f.add(b.id, "gone2");
        f.prune(Arrays.asList("keep"));
        assertEquals(Arrays.asList("keep"), f.get(a.id).pkgs);
        assertNull(f.get(b.id));
    }

    @Test public void corruptJsonMeansNoFolders() {
        assertEquals(0, Folders.parse("{not json").all().size());
        assertEquals(0, Folders.parse(null).all().size());
        assertNull(Folders.idFromKey("com.app"));
    }
}
