package com.codec2.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Herhangi bir ses dosyasini (mp3/aac/m4a/opus/ogg/wav/flac...) cihazin KENDI
 * codec'leriyle (MediaExtractor + MediaCodec; harici kutuphane yok) PCM'e cozer.
 * - decodeMono       : MONO, dosyanin DOGAL ornekleme hizi (oynatma icin)
 * - decodeTo8kMono   : 8 kHz mono (codec2 encode girisi icin)
 */
public final class AudioDecoder {

    /** Cozulmus mono PCM + ornekleme hizi. */
    public static final class Pcm {
        public final short[] data;
        public final int rate;
        public Pcm(short[] data, int rate) { this.data = data; this.rate = rate; }
    }

    /** Dosyayi MONO PCM'e cozer, dogal ornekleme hizinda. Hata olursa Exception. */
    public static Pcm decodeMono(Context ctx, Uri uri) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r");
        if (afd == null) throw new Exception("dosya acilamadi");
        try {
            if (afd.getLength() >= 0)
                ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            else
                ex.setDataSource(afd.getFileDescriptor());

            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("ses izi bulunamadi");
            ex.selectTrack(track);

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            Log2.add(ctx.getString(R.string.conv_src_format, mime, srcRate, channels));

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;

            while (!outEos) {
                if (!inEos) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(outIdx);
                        byte[] chunk = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(chunk);
                        pcm.write(chunk);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            codec.stop();
            codec.release();

            byte[] raw = pcm.toByteArray();
            int n = raw.length / 2;
            if (channels < 1) channels = 1;
            int frames = n / channels;
            short[] mono = new short[frames];
            // 16-bit LE -> short, kanal ortalamasiyla mono
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int k = (i * channels + c) * 2;
                    sum += (short) ((raw[k] & 0xff) | (raw[k + 1] << 8));
                }
                mono[i] = (short) (sum / channels);
            }
            if (srcRate <= 0) srcRate = 8000;
            return new Pcm(mono, srcRate);
        } finally {
            try { ex.release(); } catch (Exception ignore) {}
            try { afd.close(); } catch (Exception ignore) {}
        }
    }

    /** @return 8 kHz mono 16-bit PCM (codec2 encode icin). */
    public static short[] decodeTo8kMono(Context ctx, Uri uri) throws Exception {
        Pcm p = decodeMono(ctx, uri);
        if (p.rate == 8000) return p.data;
        int frames = p.data.length;
        int outLen = (int) ((long) frames * 8000L / p.rate);
        short[] out = new short[outLen];
        double ratio = (double) p.rate / 8000.0;
        for (int i = 0; i < outLen; i++) {
            double sp = i * ratio;
            int i0 = (int) sp;
            int i1 = Math.min(i0 + 1, frames - 1);
            double fr = sp - i0;
            out[i] = (short) (p.data[i0] * (1 - fr) + p.data[i1] * fr);
        }
        return out;
    }

    /** Oynatma icin: mono, dogal ornekleme hizinda. */
    public static Pcm decodeForPlayback(Context ctx, Uri uri) throws Exception {
        return decodeMono(ctx, uri);
    }

    /** Sure (mikrosaniye), cozmeden (MediaExtractor format'indan). Bulunamazsa 0. */
    public static long durationUs(Context ctx, Uri uri) {
        MediaExtractor ex = new MediaExtractor();
        AssetFileDescriptor afd = null;
        try {
            afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) return 0;
            if (afd.getLength() >= 0)
                ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            else
                ex.setDataSource(afd.getFileDescriptor());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    try { return f.getLong(MediaFormat.KEY_DURATION); } catch (Exception e) { return 0; }
                }
            }
        } catch (Exception ignore) {
        } finally {
            try { ex.release(); } catch (Exception ignore) {}
            try { if (afd != null) afd.close(); } catch (Exception ignore) {}
        }
        return 0;
    }

    private AudioDecoder() {}
}
