package io.github.cpujp.wiilauncher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Keeps the user's channel order stable while apps get installed or removed. */
public final class AppOrder {
    private AppOrder() {}

    /**
     * Saved packages that are still installed keep their position; new packages are appended
     * in the order given by {@code installedSorted}.
     */
    public static List<String> merge(List<String> saved, Collection<String> installedSorted) {
        LinkedHashSet<String> installed = new LinkedHashSet<>(installedSorted);
        List<String> out = new ArrayList<>();
        for (String p : saved) if (installed.remove(p)) out.add(p);
        out.addAll(installed);
        return out;
    }

    /** Swaps the item at {@code index} with its neighbour {@code delta} positions away; returns the new index. */
    public static int move(List<String> order, int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= order.size() || target < 0 || target >= order.size()) return index;
        String tmp = order.get(target);
        order.set(target, order.get(index));
        order.set(index, tmp);
        return target;
    }

    public static String join(List<String> order) {
        return String.join(",", order);
    }

    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }
}
