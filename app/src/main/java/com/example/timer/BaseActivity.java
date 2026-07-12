package com.example.timer;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base for every activity: pins the UI language to Bulgarian by wrapping the
 * base context, so the app looks the same on any device locale.
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }
}
