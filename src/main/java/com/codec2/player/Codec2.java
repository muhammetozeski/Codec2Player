package com.codec2.player;

/**
 * Codec 2 decode kopru sinifi. Native taraf libcodec2player.so icinde
 * (JNI_OnLoad ile RegisterNatives). Yalniz cozme (decode) yolu acik.
 */
public final class Codec2 {

    static { System.loadLibrary("codec2player"); }

    // codec2 v1.0.x mod sabitleri (codec2.h ile ayni degerler)
    public static final int MODE_3200 = 0;
    public static final int MODE_2400 = 1;
    public static final int MODE_1600 = 2;
    public static final int MODE_1400 = 3;
    public static final int MODE_1300 = 4;
    public static final int MODE_1200 = 5;
    public static final int MODE_700C = 8;
    public static final int MODE_450  = 10;

    // Codec2Recorder uyumlu dosya basligi: C0 DE C2 01 00 <mod> 00
    public static final int HEADER_SIZE = 7;

    public static native long create(int mode);
    public static native int  samplesPerFrame(long con);
    public static native int  bytesPerFrame(long con);
    public static native void destroy(long con);
    public static native void decode(long con, byte[] bits, short[] outPcm);
    public static native void encode(long con, short[] pcm, byte[] bits);

    /** Baslik varsa modu dondurur, yoksa -1. */
    public static int headerMode(byte[] a) {
        if (a == null || a.length < HEADER_SIZE) return -1;
        if ((a[0] & 0xff) != 0xC0 || (a[1] & 0xff) != 0xDE || (a[2] & 0xff) != 0xC2
                || a[3] != 1 || a[4] != 0) return -1;
        return a[5] & 0xff;
    }

    private Codec2() {}
}
