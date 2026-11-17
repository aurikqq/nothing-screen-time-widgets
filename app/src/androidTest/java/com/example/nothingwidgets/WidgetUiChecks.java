package com.example.nothingwidgets;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.ViewFlipper;
import com.example.nothingwidgets.render.WidgetRenderer;

/** Framework-only device checks: no test library or test data in the release app. */
public final class WidgetUiChecks extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        final View[] applied = new View[1];
        final ViewFlipper[] eyes = new ViewFlipper[1];
        Activity activity = null;
        try {
            activity = startActivitySync(new Intent().setClassName(getTargetContext(), "com.example.nothingwidgets.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity host = activity;
            runOnMainSync(() -> {
                RemoteViews remote = new RemoteViews(host.getPackageName(), R.layout.screen_time_page);
                applied[0] = remote.apply(host, null);
                eyes[0] = applied[0].findViewById(R.id.blink_eyes);
                if (eyes[0].getFlipInterval() != 60000) throw new AssertionError("Blink must default to 60 seconds");
                eyes[0].setFlipInterval(200); // Accelerate only this in-process test instance.
                host.addContentView(applied[0], new FrameLayout.LayoutParams(300, 300));
                if (!eyes[0].isFlipping()) throw new AssertionError("Host animation did not start");
                if (eyes[0].getInAnimation().getStartOffset() != 160) throw new AssertionError("Eye closure must last 160 ms");
                if (eyes[0].getChildCount() != 2) throw new AssertionError("Only eye layers may flip");
                Typeface font = Typeface.createFromAsset(host.getAssets(), "fonts/NType82-Regular.otf");
                if (font == null) throw new AssertionError("NType unavailable in debug APK");
                Bitmap overlay = WidgetRenderer.INSTANCE.screenEyes(564, 564, new WidgetRenderer.Palette(Color.BLACK, Color.WHITE, Color.GRAY, Color.RED));
                if (overlay.getPixel(243,263) != Color.WHITE || overlay.getPixel(321,263) != Color.WHITE) throw new AssertionError("Eye geometry changed");
                if (overlay.getPixel(280,263) != Color.TRANSPARENT) throw new AssertionError("Eye layer must be transparent elsewhere");
                overlay.recycle();
            });
            Thread.sleep(250);
            runOnMainSync(() -> { if (eyes[0].getDisplayedChild() != 1) throw new AssertionError("Host did not advance blink frame"); });
            runOnMainSync(() -> ((ViewGroup)applied[0].getParent()).removeView(applied[0]));
            result.putString("stream", "RemoteViews inflated; native blink advanced; 60s interval, 160ms closure, NType and eye geometry verified.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", failure.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
