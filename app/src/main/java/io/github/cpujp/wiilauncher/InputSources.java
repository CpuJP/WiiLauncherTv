package io.github.cpujp.wiilauncher;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;

/** The TV's physical inputs (HDMI, antenna...) and the standard intent that switches to each one. */
final class InputSources {
    static final class Source {
        final String id;
        final String label;

        Source(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private InputSources() {}

    /** Hardware inputs in port order; app-provided inputs (IPTV and the like) are left out. */
    static List<Source> list(Context context) {
        List<Source> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
        inputs.sort((a, b) -> order(a) != order(b) ? Integer.compare(order(a), order(b)) : a.getId().compareTo(b.getId()));
        // Hardware tuners come from the same vendor package as the HDMI inputs; app inputs (IPTV) do not.
        java.util.Set<String> hardwarePkgs = new java.util.HashSet<>();
        for (TvInputInfo info : inputs) if (info.isPassthroughInput()) hardwarePkgs.add(info.getServiceInfo().packageName);
        for (TvInputInfo info : inputs) {
            boolean tuner = info.getType() == TvInputInfo.TYPE_TUNER && hardwarePkgs.contains(info.getServiceInfo().packageName);
            if (info.isHidden(context) || !(info.isPassthroughInput() || tuner)) continue;
            if (info.getParentId() != null) continue; // CEC devices behind a port; the port itself is listed
            CharSequence custom = info.loadCustomLabel(context);
            CharSequence label = custom != null && custom.length() > 0 ? custom : info.loadLabel(context);
            out.add(new Source(info.getId(), String.valueOf(label)));
        }
        return out;
    }

    static Intent intentFor(Source s) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(s.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Antenna first, then HDMI ports, then anything else; same-type inputs sort by id (HW15, HW16...). */
    private static int order(TvInputInfo info) {
        if (info.getType() == TvInputInfo.TYPE_TUNER) return 0;
        return info.getType() == TvInputInfo.TYPE_HDMI ? 1 : 2;
    }
}
