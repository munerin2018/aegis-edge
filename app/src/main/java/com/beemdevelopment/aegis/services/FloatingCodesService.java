package com.beemdevelopment.aegis.services;

import android.animation.ValueAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.ui.FloatingCodesActivity;

import java.util.Collections;

public class FloatingCodesService extends Service {
    private static final String CHANNEL = "floating_codes";
    private WindowManager windows;
    private FrameLayout shortcut;
    private ImageView icon;
    private WindowManager.LayoutParams shortcutParams;
    private ValueAnimator slideAnimator;
    private int iconSize;
    private int visibleWidth;
    private boolean expanded;
    private float touchStartX;
    private boolean swiped;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.floating_title), NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, FloatingCodesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(8172, new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_aegis_quicksettings)
                .setContentTitle(getString(R.string.floating_notification))
                .setContentIntent(pending).setOngoing(true).build());
        if (shortcut == null) {
            windows = (WindowManager) getSystemService(WINDOW_SERVICE);
            float density = getResources().getDisplayMetrics().density;
            shortcut = new FrameLayout(this);
            shortcut.setClipChildren(true);
            shortcut.setContentDescription(getString(R.string.floating_open));
            shortcut.setClickable(true);
            icon = new ImageView(this);
            icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
            icon.setAlpha(0.55f);
            iconSize = (int) (48 * density);
            visibleWidth = (int) (24 * density);
            shortcut.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.START | Gravity.CENTER_VERTICAL));
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            shortcutParams = new WindowManager.LayoutParams(visibleWidth, iconSize, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            shortcutParams.gravity = Gravity.END | Gravity.TOP;
            shortcutParams.y = (int) (120 * getResources().getDisplayMetrics().density);
            shortcut.setOnClickListener(v -> {
                if (!expanded) {
                    setExpanded(true);
                } else {
                    setExpanded(false);
                    startActivity(open);
                }
            });
            shortcut.setOnLongClickListener(v -> { stopSelf(); return true; });
            shortcut.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (expanded) setExpanded(false);
                    return false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchStartX = event.getRawX();
                    swiped = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !swiped) {
                    float delta = event.getRawX() - touchStartX;
                    if (Math.abs(delta) > 28 * density) {
                        swiped = true;
                        v.setPressed(false);
                        setExpanded(delta < 0);
                        return true;
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP && swiped) {
                    return true;
                }
                return swiped;
            });
            try {
                windows.addView(shortcut, shortcutParams);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    shortcut.post(() -> shortcut.setSystemGestureExclusionRects(Collections.singletonList(
                            new Rect(0, 0, shortcut.getWidth(), shortcut.getHeight()))));
                }
            } catch (RuntimeException e) {
                shortcut = null;
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void setExpanded(boolean show) {
        if (shortcut == null || shortcutParams == null) return;
        expanded = show;
        icon.setAlpha(show ? 1f : 0.55f);
        if (slideAnimator != null) slideAnimator.cancel();
        slideAnimator = ValueAnimator.ofInt(shortcutParams.width, show ? iconSize : visibleWidth);
        slideAnimator.setDuration(160);
        slideAnimator.addUpdateListener(animation -> {
            shortcutParams.width = (int) animation.getAnimatedValue();
            windows.updateViewLayout(shortcut, shortcutParams);
        });
        slideAnimator.start();
    }

    @Override
    public void onDestroy() {
        if (slideAnimator != null) slideAnimator.cancel();
        if (shortcut != null) {
            windows.removeView(shortcut);
            shortcut = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
