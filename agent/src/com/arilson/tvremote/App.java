package com.arilson.tvremote;

import android.app.Application;

/** Primeira coisa que roda no processo: liga a proteção contra travamentos. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Diag.install(this);
    }
}
