package com.spx.chat;

import android.app.Application;

public final class SpxApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        SSLHelper.init(this);
    }
}
