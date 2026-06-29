package com.codec2.player;

/** Bir codec2 byte dizisini PCM'e cozer. Mod, dosya basligindan (varsa) okunur. */
public final class Decoder {

    public static final class Result {
        public short[] pcm;
        public int mode;
        public int sampleRate = 8000;
    }

    /** @param fallbackMode baslik yoksa kullanilacak mod. null donerse cozulemedi. */
    public static Result decode(byte[] data, int fallbackMode) {
        if (data == null || data.length == 0) return null;

        int offset = 0;
        int mode = fallbackMode;
        int hm = Codec2.headerMode(data);
        if (hm >= 0) { mode = hm; offset = Codec2.HEADER_SIZE; }

        long c2 = Codec2.create(mode);
        if (c2 == 0) return null;
        int spf = Codec2.samplesPerFrame(c2);
        int bpf = Codec2.bytesPerFrame(c2);
        if (spf <= 0 || bpf <= 0) { Codec2.destroy(c2); return null; }

        int frames = (data.length - offset) / bpf;
        if (frames <= 0) { Codec2.destroy(c2); return null; }

        short[] pcm = new short[frames * spf];
        byte[] bits = new byte[bpf];
        short[] out = new short[spf];
        int pos = offset;
        for (int f = 0; f < frames; f++) {
            System.arraycopy(data, pos, bits, 0, bpf);
            Codec2.decode(c2, bits, out);
            System.arraycopy(out, 0, pcm, f * spf, spf);
            pos += bpf;
        }
        Codec2.destroy(c2);

        Result r = new Result();
        r.pcm = pcm;
        r.mode = mode;
        return r;
    }

    /** Dalga formu icin tepe degerleri (0..1), buckets adet. */
    public static float[] peaks(short[] pcm, int buckets) {
        float[] p = new float[buckets];
        if (pcm == null || pcm.length == 0) return p;
        int per = Math.max(1, pcm.length / buckets);
        for (int b = 0; b < buckets; b++) {
            int start = b * per;
            int end = Math.min(pcm.length, start + per);
            int peak = 0;
            for (int i = start; i < end; i++) {
                int v = pcm[i]; if (v < 0) v = -v;
                if (v > peak) peak = v;
            }
            p[b] = peak / 32768f;
        }
        return p;
    }

    private Decoder() {}
}
