package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AppOrderTest {
    @Test public void keepsSavedOrderDropsRemovedAppendsNew() {
        List<String> merged = AppOrder.merge(Arrays.asList("c", "gone", "a"), Arrays.asList("a", "b", "c"));
        assertEquals(Arrays.asList("c", "a", "b"), merged);
    }

    @Test public void moveSwapsNeighbours() {
        List<String> l = new ArrayList<>(Arrays.asList("a", "b", "c"));
        assertEquals(2, AppOrder.move(l, 1, 1));
        assertEquals(Arrays.asList("a", "c", "b"), l);
    }

    @Test public void moveOutOfRangeIsNoop() {
        List<String> l = new ArrayList<>(Arrays.asList("a", "b"));
        assertEquals(0, AppOrder.move(l, 0, -1));
        assertEquals(Arrays.asList("a", "b"), l);
    }

    @Test public void joinSplitRoundTrip() {
        List<String> l = Arrays.asList("com.a", "com.b");
        assertEquals(l, AppOrder.split(AppOrder.join(l)));
        assertEquals(0, AppOrder.split("").size());
        assertEquals(0, AppOrder.split(null).size());
    }
}
