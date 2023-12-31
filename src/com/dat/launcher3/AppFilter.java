package com.dat.launcher3;

import android.content.ComponentName;
import android.content.Context;

import com.dat.launcher3.R;

public class AppFilter {

    public static AppFilter newInstance(Context context) {
        return Utilities.getOverrideObject(AppFilter.class, context, R.string.app_filter_class);
    }

    public boolean shouldShowApp(ComponentName app) {
        return true;
    }
}
