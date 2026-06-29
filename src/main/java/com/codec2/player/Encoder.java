package com.codec2.player;

/** 8 kHz mono PCM -> codec2 .c2 (7 baytlik Codec2Recorder uyumlu baslik + kareler). */
public final class Encoder {

    public static byte[] encodeToC2(short[] pcm, int mode) {
        if (pcm == null) return null;
        long c2 = Codec2.create(mode);
        if (c2 == 0) return null;
        int spf = Codec2.samplesPerFrame(c2);
        int bpf = Codec2.bytesPerFrame(c2);
        if (spf <= 0 || bpf <= 0) { Codec2.destroy(c2); return null; }

        int frames = pcm.length / spf;
        byte[] out = new byte[7 + frames * bpf];
        out[0] = (byte) 0xC0; out[1] = (byte) 0xDE; out[2] = (byte) 0xC2;
        out[3] = 1; out[4] = 0; out[5] = (byte) mode; out[6] = 0;

        short[] frame = new short[spf];
        byte[] bits = new byte[bpf];
        for (int f = 0; f < frames; f++) {
            System.arraycopy(pcm, f * spf, frame, 0, spf);
            Codec2.encode(c2, frame, bits);
            System.arraycopy(bits, 0, out, 7 + f * bpf, bpf);
        }
        Codec2.destroy(c2);
        return out;
    }

    private Encoder() {}
}
