package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;

/** Shows the night globe full screen without waiting for the screensaver: am start -n com.gathin.tvhome/.GlobePreviewActivity */
public class GlobePreviewActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GlobeView globe = new GlobeView(this);
        setContentView(globe);
        new Thread(globe::refreshPlanes, "flights").start();
    }
}
