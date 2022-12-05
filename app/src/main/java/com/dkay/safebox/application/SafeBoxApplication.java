package com.dkay.safebox.application;

import android.app.Application;

import com.dkay.safebox.util.debug.DebugInfoDumper;

import java.io.File;

import xcrash.XCrash;

public class SafeBoxApplication extends Application {
    private static SafeBoxApplication application;

    @Override
    public void onCreate() {
        super.onCreate();
        application = this;
        initCrashDumper();
    }

    private void initCrashDumper() {
        XCrash.InitParameters initParameters = new XCrash.InitParameters();
        File dir = new File(DebugInfoDumper.CRASH_LOG_DIR);
        if (dir.isFile()){
            dir.delete();
        }
        if (!dir.exists()){
            dir.mkdirs();
        }
        initParameters.setLogDir(DebugInfoDumper.CRASH_LOG_DIR);
        XCrash.init(application, initParameters);
    }

    @Override
    public void onTerminate() {
        application = null;
        super.onTerminate();
    }

    public static SafeBoxApplication getApplication() {
        return application;
    }
}
