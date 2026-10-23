package io.github.cpujp.wiilauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SevenSegmentTest {
    @Test public void segmentCounts() {
        int[] expected = {6, 2, 5, 5, 4, 5, 6, 3, 7, 6};
        for (int d = 0; d < 10; d++) assertEquals("digit " + d, expected[d], Integer.bitCount(SevenSegment.mask((char) ('0' + d))));
    }

    @Test public void oneIsRightSide() {
        assertEquals(SevenSegment.B | SevenSegment.C, SevenSegment.mask('1'));
        assertEquals(0, SevenSegment.mask(':'));
    }
}
