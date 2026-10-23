package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertEquals;

import io.github.cpujp.wiilauncher.Grid.Dir;
import io.github.cpujp.wiilauncher.Grid.Focus;
import org.junit.Test;

public class GridTest {
    @Test public void pageCount() {
        assertEquals(1, Grid.pageCount(0));
        assertEquals(1, Grid.pageCount(12));
        assertEquals(2, Grid.pageCount(13));
    }

    @Test public void rightEdgeFlipsToNextPageSameRow() {
        assertEquals(new Focus(1, 4), Grid.move(new Focus(0, 7), Dir.RIGHT, 2));
    }

    @Test public void rightEdgeOnLastPageStays() {
        assertEquals(new Focus(0, 3), Grid.move(new Focus(0, 3), Dir.RIGHT, 1));
    }

    @Test public void leftEdgeFlipsToPreviousPage() {
        assertEquals(new Focus(0, 11), Grid.move(new Focus(1, 8), Dir.LEFT, 2));
    }

    @Test public void downFromLastRowReachesButtons() {
        assertEquals(Grid.BTN_SETTINGS, Grid.move(new Focus(0, 9), Dir.DOWN, 1).slot);
        assertEquals(Grid.BTN_MAIL, Grid.move(new Focus(0, 10), Dir.DOWN, 1).slot);
    }

    @Test public void buttonsNavigate() {
        Focus settings = new Focus(0, Grid.BTN_SETTINGS);
        assertEquals(new Focus(0, Grid.BTN_MAIL), Grid.move(settings, Dir.RIGHT, 1));
        assertEquals(new Focus(0, 8), Grid.move(settings, Dir.UP, 1));
        assertEquals(new Focus(0, 11), Grid.move(new Focus(0, Grid.BTN_MAIL), Dir.UP, 1));
        assertEquals(-1, settings.index());
    }

    @Test public void indexSpansPages() {
        assertEquals(13, new Focus(1, 1).index());
    }
}
