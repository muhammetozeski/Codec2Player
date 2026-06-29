package com.codec2.player;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Uygulama-ici dil secimi. "" = sistem; "en"/"tr" = zorla. Her Activity/Service
 *  attachBaseContext'te wrap eder. */
public final class LocaleHelper {

    public static String getLang(Context c) {
        return c.getSharedPreferences("c2player", Context.MODE_PRIVATE).getString("lang", "");
    }

    public static void setLang(Context c, String lang) {
        c.getSharedPreferences("c2player", Context.MODE_PRIVATE).edit().putString("lang", lang).apply();
    }

    public static Context wrap(Context ctx) {
        String lang = getLang(ctx);
        if (lang == null || lang.isEmpty()) return ctx;
        Locale loc = new Locale(lang);
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
        cfg.setLocale(loc);
        return ctx.createConfigurationContext(cfg);
    }

    private LocaleHelper() {}
}
