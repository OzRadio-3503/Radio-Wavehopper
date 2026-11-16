package com.billybobs.radiotuner;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** The 2x1 widget: drawn by RadioWidgetProvider.refresh(), like the wide one. */
public class RadioWidgetSmallProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RadioWidgetProvider.refresh(context);
    }

    @Override
    public void onEnabled(Context context) {
        RadioWidgetProvider.refresh(context);
    }
}
