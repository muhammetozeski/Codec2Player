package com.codec2.player;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Uygulama girisi. Tek isi: SESSIZ cokmeyi engellemek. Global bir
 * UncaughtExceptionHandler kurar; herhangi bir is parcaciginda yakalanmamis
 * bir istisna olursa raporu dosyaya yazar, sonra normal akisina birakir.
 * MainActivity bir sonraki acilista bu raporu kullaniciya gosterir.
 * (Not: native SIGSEGV gibi cokmeler Java tarafinda yakalanamaz; onlari
 *  dosya turune gore dogru cozucuye yonlendirerek bastan onluyoruz.)
 */
public final class App extends Application {

    public static final String CRASH_FILE = "last_crash.txt";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("Codec2 Player - crash raporu");
                pw.println("zaman  : " + new java.util.Date());
                pw.println("thread : " + t.getName());
                pw.println("android: " + android.os.Build.VERSION.RELEASE
                        + " (API " + android.os.Build.VERSION.SDK_INT + ")");
                pw.println("cihaz  : " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                pw.println();
                e.printStackTrace(pw);
                pw.flush();
                File f = new File(getFilesDir(), CRASH_FILE);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(sw.toString().getBytes("UTF-8"));
                fos.close();
                Log2.add("ÇÖKME: " + e);
            } catch (Throwable ignore) {}
            if (prev != null) prev.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        });
    }
}
