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

    /** Tam cozmeden sure (sn): baslik (varsa) + dosya boyutundan hesaplar. -1 = bilinmiyor. */
    public static int quickDuration(byte[] head, long size) {
        if (size <= 0) return -1;
        int offset = 0, mode = Codec2.MODE_1300;
        int hm = Codec2.headerMode(head);
        if (hm >= 0) { mode = hm; offset = Codec2.HEADER_SIZE; }
        long c2 = Codec2.create(mode);
        if (c2 == 0) return -1;
        int spf = Codec2.samplesPerFrame(c2);
        int bpf = Codec2.bytesPerFrame(c2);
        Codec2.destroy(c2);
        if (spf <= 0 || bpf <= 0) return -1;
        long frames = (size - offset) / bpf;
        if (frames <= 0) return -1;
        return (int) (frames * spf / 8000);
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

    /** PCM short[] -> WAV (mono 16-bit, hz). */
    public static void writeWav(java.io.File out, short[] pcm, int hz) throws java.io.IOException {
        int dataLen = pcm.length * 2;
        java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out));
        try {
            wstr(os, "RIFF"); wint(os, 36 + dataLen); wstr(os, "WAVE");
            wstr(os, "fmt "); wint(os, 16); wsh(os, (short) 1); wsh(os, (short) 1);
            wint(os, hz); wint(os, hz * 2); wsh(os, (short) 2); wsh(os, (short) 16);
            wstr(os, "data"); wint(os, dataLen);
            byte[] buf = new byte[dataLen];
            for (int i = 0; i < pcm.length; i++) {
                buf[i * 2] = (byte) (pcm[i] & 0xff);
                buf[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            os.write(buf);
        } finally { os.close(); }
    }

    private static void wstr(java.io.OutputStream os, String s) throws java.io.IOException { os.write(s.getBytes("US-ASCII")); }
    private static void wint(java.io.OutputStream os, int v) throws java.io.IOException {
        os.write(v & 0xff); os.write((v >> 8) & 0xff); os.write((v >> 16) & 0xff); os.write((v >> 24) & 0xff);
    }
    private static void wsh(java.io.OutputStream os, short v) throws java.io.IOException { os.write(v & 0xff); os.write((v >> 8) & 0xff); }

    private Decoder() {}
}
