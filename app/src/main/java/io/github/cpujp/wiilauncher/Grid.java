package io.github.cpujp.wiilauncher;

/** Channel grid geometry and D-pad navigation, independent of Android views. */
public final class Grid {
    public static final int COLS = 4;
    public static final int ROWS = 3;
    public static final int PER_PAGE = COLS * ROWS;

    /** Focus targets outside the channel grid. */
    public static final int BTN_SETTINGS = -1;
    public static final int BTN_MAIL = -2;

    public enum Dir { LEFT, RIGHT, UP, DOWN }

    /** Immutable focus position: a page plus a slot (0..PER_PAGE-1) or a bottom button. */
    public static final class Focus {
        public final int page;
        public final int slot;

        public Focus(int page, int slot) {
            this.page = page;
            this.slot = slot;
        }

        public boolean onButton() {
            return slot < 0;
        }

        /** Index in the full channel list, or -1 when focus is on a button. */
        public int index() {
            return onButton() ? -1 : page * PER_PAGE + slot;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Focus && ((Focus) o).page == page && ((Focus) o).slot == slot;
        }

        @Override public int hashCode() {
            return page * 31 + slot;
        }

        @Override public String toString() {
            return "Focus(" + page + "," + slot + ")";
        }
    }

    private Grid() {}

    public static int pageCount(int channels) {
        return Math.max(1, (channels + PER_PAGE - 1) / PER_PAGE);
    }

    /** Moves focus one step; crossing the left/right edge of the grid flips pages like the Wii menu. */
    public static Focus move(Focus f, Dir d, int pages) {
        if (f.onButton()) {
            switch (d) {
                case UP: return new Focus(f.page, f.slot == BTN_SETTINGS ? (ROWS - 1) * COLS : PER_PAGE - 1);
                case LEFT: return new Focus(f.page, BTN_SETTINGS);
                case RIGHT: return new Focus(f.page, BTN_MAIL);
                default: return f;
            }
        }
        int row = f.slot / COLS, col = f.slot % COLS;
        switch (d) {
            case LEFT:
                if (col > 0) return new Focus(f.page, f.slot - 1);
                if (f.page > 0) return new Focus(f.page - 1, row * COLS + COLS - 1);
                return f;
            case RIGHT:
                if (col < COLS - 1) return new Focus(f.page, f.slot + 1);
                if (f.page < pages - 1) return new Focus(f.page + 1, row * COLS);
                return f;
            case UP:
                return row > 0 ? new Focus(f.page, f.slot - COLS) : f;
            case DOWN:
                if (row < ROWS - 1) return new Focus(f.page, f.slot + COLS);
                return new Focus(f.page, col < COLS / 2 ? BTN_SETTINGS : BTN_MAIL);
        }
        return f;
    }
}
