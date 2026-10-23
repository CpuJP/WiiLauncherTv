package io.github.cpujp.wiilauncher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Tiny synthesized UI sounds; no audio assets are shipped. */
final class Sfx {
    private static final int RATE = 22050;
    private final AudioTrack tick;
    private final AudioTrack select;

    Sfx() {
        tick = track(tone(new float[]{1900f}, 0.025f, 0.18f));
        select = track(tone(new float[]{880f, 1320f}, 0.09f, 0.25f));
    }

    void tick() {
        play(tick);
    }

    void select() {
        play(select);
    }

    void release() {
        tick.release();
        select.release();
    }

    private static void play(AudioTrack t) {
        if (t.getState() != AudioTrack.STATE_INITIALIZED) return;
        t.stop();
        t.reloadStaticData();
        t.play();
    }

    /** Sine sweep through {@code freqs} with a short attack and exponential decay. */
    private static short[] tone(float[] freqs, float seconds, float volume) {
        int n = (int) (RATE * seconds);
        short[] pcm = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float f = freqs.length == 1 ? freqs[0] : freqs[0] + (freqs[1] - freqs[0]) * Math.min(1f, t * 2f);
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1.0, i / (RATE * 0.003)) * Math.exp(-4.0 * t);
            pcm[i] = (short) (Math.sin(phase) * env * volume * Short.MAX_VALUE);
        }
        return pcm;
    }

    private static AudioTrack track(short[] pcm) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }
}
