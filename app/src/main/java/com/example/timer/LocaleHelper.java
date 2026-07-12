package com.example.timer;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Forces the app to render in a fixed language regardless of the device locale.
 * The whole app is Bulgarian, so every activity wraps its base context with a
 * Bulgarian {@link Configuration} (see {@link BaseActivity}).
 */
final class LocaleHelper {

    /** The single language this app runs in. */
    static final String LANGUAGE = "bg";

    private LocaleHelper() {
    }

    static Context wrap(Context base) {
        Locale locale = new Locale(LANGUAGE);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
