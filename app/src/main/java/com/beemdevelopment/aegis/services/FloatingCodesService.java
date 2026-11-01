package com.beemdevelopment.aegis.services;

import android.animation.ValueAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.ui.FloatingCodesActivity;

import java.util.Collections;

public class FloatingCodesService extends Service {
    private static final String CHANNEL = "floating_codes";
    public static final String ACTION_STATE_CHANGED = "com.beemdevelopment.aegis.FLOATING_STATE_CHANGED";
    private static volatile boolean running;
    private WindowManager windows;
    private FrameLayout shortcut;
    private ImageView icon;
    private WindowManager.LayoutParams shortcutParams;
    private ValueAnimator slideAnimator;
    private int iconSize;
    private int visibleWidth;
    private int hiddenWidth;
    private boolean expanded;
    private boolean hidden;
    private boolean onLeft;
    private boolean dragging;
    private float touchStartX;
    private float touchStartY;
    private int dragStartX;
    private int dragStartY;
    private boolean swiped;
    private SharedPreferences positionPrefs;
    private TextView removeTarget;

    public static boolean isRunning() { return running; }

    private void setRunning(boolean active) {
        running = active;
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

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
            positionPrefs = getSharedPreferences("floating_shortcut_position", MODE_PRIVATE);
            onLeft = positionPrefs.getBoolean("left", false);
            hidden = positionPrefs.getBoolean("hidden", false);
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
            hiddenWidth = Math.max(1, (int) (8 * density));
            shortcut.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize,
                    (onLeft ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            shortcutParams = new WindowManager.LayoutParams(visibleWidth, iconSize, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            shortcutParams.gravity = (onLeft ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                shortcutParams.setFitInsetsSides(WindowInsets.Side.TOP | WindowInsets.Side.BOTTOM);
            }
            shortcutParams.y = clampY(positionPrefs.getInt("top", (int) (120 * density)));
            updateHiddenAppearance();
            shortcut.setOnClickListener(v -> {
                if (hidden) setHidden(false);
                if (!expanded) {
                    setExpanded(true);
                } else {
                    setExpanded(false);
                    startActivity(open);
                }
            });
            shortcut.setOnLongClickListener(v -> {
                startDrag();
                return true;
            });
            shortcut.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (expanded && !dragging) setExpanded(false);
                    return false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    swiped = false;
                    dragging = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging) {
                    shortcutParams.x = clampX(dragStartX + Math.round(event.getRawX() - touchStartX));
                    shortcutParams.y = clampY(dragStartY + Math.round(event.getRawY() - touchStartY));
                    windows.updateViewLayout(shortcut, shortcutParams);
                    updateRemoveTarget(isOverRemoveTarget(event));
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP && dragging) {
                    if (isOverRemoveTarget(event)) {
                        dragging = false;
                        hideRemoveTarget();
                        stopSelf();
                    } else {
                        finishDrag();
                    }
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && dragging) {
                    finishDrag();
                    return true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !swiped) {
                    float delta = event.getRawX() - touchStartX;
                    if (Math.abs(delta) > 10 * density) {
                        swiped = true;
                        v.setPressed(false);
                        boolean inward = onLeft ? delta > 0 : delta < 0;
                        if (inward) {
                            if (hidden) setHidden(false);
                            setExpanded(true);
                        } else {
                            setHidden(true);
                        }
                        return true;
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP && swiped) {
                    return true;
                }
                return swiped;
            });
            try {
                windows.addView(shortcut, shortcutParams);
                setRunning(true);
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

    private void startDrag() {
        if (shortcut == null || shortcutParams == null) return;
        dragging = true;
        swiped = true;
        if (hidden) setHidden(false);
        if (slideAnimator != null) slideAnimator.cancel();
        int[] location = new int[2];
        shortcut.getLocationOnScreen(location);
        dragStartX = clampX(location[0]);
        dragStartY = shortcutParams.y;
        expanded = true;
        icon.setAlpha(1f);
        shortcutParams.width = iconSize;
        shortcutParams.gravity = Gravity.LEFT | Gravity.TOP;
        shortcutParams.x = dragStartX;
        setIconGravity(false);
        windows.updateViewLayout(shortcut, shortcutParams);
        showRemoveTarget();
    }

    private void finishDrag() {
        dragging = false;
        hideRemoveTarget();
        onLeft = shortcutParams.x + iconSize / 2 < screenSize().widthPixels / 2;
        shortcutParams.gravity = (onLeft ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
        shortcutParams.x = 0;
        shortcutParams.y = clampY(shortcutParams.y);
        setIconGravity(onLeft);
        windows.updateViewLayout(shortcut, shortcutParams);
        positionPrefs.edit().putBoolean("left", onLeft).putInt("top", shortcutParams.y).apply();
        setExpanded(false);
    }

    private void setIconGravity(boolean leftEdge) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) icon.getLayoutParams();
        params.gravity = (leftEdge ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        icon.setLayoutParams(params);
    }

    private DisplayMetrics screenSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getMetrics(metrics);
        return metrics;
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(x, Math.max(0, screenSize().widthPixels - iconSize)));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(y, Math.max(0, screenSize().heightPixels - iconSize)));
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

    private void setHidden(boolean value) {
        hidden = value;
        positionPrefs.edit().putBoolean("hidden", value).apply();
        updateHiddenAppearance();
        if (value) setExpanded(false);
    }

    private void updateHiddenAppearance() {
        icon.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (hidden) {
            GradientDrawable rim = new GradientDrawable();
            rim.setColor(0x66808080);
            rim.setCornerRadius(8 * getResources().getDisplayMetrics().density);
            int transparentWidth = visibleWidth - hiddenWidth;
            shortcut.setBackground(new InsetDrawable(rim, onLeft ? 0 : transparentWidth, 0,
                    onLeft ? transparentWidth : 0, 0));
        } else {
            shortcut.setBackground(null);
        }
        // 背景の余白がアイコンの配置に引き継がれないようにする。
        shortcut.setPadding(0, 0, 0, 0);
    }

    private void showRemoveTarget() {
        if (removeTarget != null) return;
        float density = getResources().getDisplayMetrics().density;
        removeTarget = new TextView(this);
        removeTarget.setText("×");
        removeTarget.setTextSize(36);
        removeTarget.setTextColor(0xffffffff);
        removeTarget.setGravity(Gravity.CENTER);
        removeTarget.setContentDescription(getString(R.string.floating_drop_to_stop));
        updateRemoveTarget(false);
        int size = (int) (72 * density);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = (int) (48 * density);
        params.alpha = 0.75f;
        try {
            windows.addView(removeTarget, params);
        } catch (RuntimeException e) {
            removeTarget = null;
        }
    }

    private void updateRemoveTarget(boolean selected) {
        if (removeTarget == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(selected ? 0xffd32f2f : 0xff494d58);
        removeTarget.setBackground(background);
    }

    private boolean isOverRemoveTarget(MotionEvent event) {
        if (removeTarget == null || !removeTarget.isAttachedToWindow()) return false;
        int[] location = new int[2];
        removeTarget.getLocationOnScreen(location);
        return event.getRawX() >= location[0] && event.getRawX() <= location[0] + removeTarget.getWidth()
                && event.getRawY() >= location[1] && event.getRawY() <= location[1] + removeTarget.getHeight();
    }

    private void hideRemoveTarget() {
        if (removeTarget != null) {
            windows.removeView(removeTarget);
            removeTarget = null;
        }
    }

    @Override
    public void onDestroy() {
        if (slideAnimator != null) slideAnimator.cancel();
        hideRemoveTarget();
        if (shortcut != null) {
            windows.removeView(shortcut);
            shortcut = null;
        }
        setRunning(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
