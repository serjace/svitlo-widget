package ua.mk.svitlo;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Android 12+: take the app colours from the wallpaper (Material You)
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
