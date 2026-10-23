package io.github.cpujp.wiilauncher;

/** Segment masks for the Wii-style digital clock. Bits: a=1 b=2 c=4 d=8 e=16 f=32 g=64. */
public final class SevenSegment {
    public static final int A = 1, B = 2, C = 4, D = 8, E = 16, F = 32, G = 64;
    private static final int[] DIGITS = {
            A | B | C | D | E | F,          // 0
            B | C,                          // 1
            A | B | G | E | D,              // 2
            A | B | G | C | D,              // 3
            F | G | B | C,                  // 4
            A | F | G | C | D,              // 5
            A | F | G | E | D | C,          // 6
            A | B | C,                      // 7
            A | B | C | D | E | F | G,      // 8
            A | B | C | D | F | G,          // 9
    };

    private SevenSegment() {}

    public static int mask(char digit) {
        return digit >= '0' && digit <= '9' ? DIGITS[digit - '0'] : 0;
    }
}
