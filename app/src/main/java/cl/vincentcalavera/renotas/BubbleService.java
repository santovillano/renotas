package cl.vincentcalavera.renotas;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/** Campana flotante que se ve sobre otras apps y la pantalla de inicio del teléfono. */
public class BubbleService extends Service {
    static final String CHANNEL = "renotas_bubble";
    static final String ACTION_REFRESH = "cl.vincentcalavera.renotas.REFRESH";
    static final String PREFS = "renotas_app";
    static volatile boolean running = false;

    private WindowManager wm;
    private BubbleView view;
    private WindowManager.LayoutParams lp;

    static void start(Context c) {
        Intent i = new Intent(c, BubbleService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BubbleService.class));
    }

    static void refresh(Context c) {
        if (!running) return;
        Intent i = new Intent(c, BubbleService.class);
        i.setAction(ACTION_REFRESH);
        try {
            c.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (view == null) addBubble();
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        view.setState(Math.max(0, sp.getInt("pendN", 0)), Math.max(0, sp.getInt("pendOver", 0)));
        running = true;
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Campana flotante", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle("Renotas")
                .setContentText("La campana flotante está activa")
                .setContentIntent(pi)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void addBubble() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new BubbleView(this);
        int size = view.sizePx();
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        lp.x = sp.getInt("bubbleX", sw - size);
        lp.y = sp.getInt("bubbleY", sh / 3);
        wm.addView(view, lp);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
        super.onDestroy();
    }

    private class BubbleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable bell;
        private final float d;
        private final int slop;
        private int n = 0;
        private int over = 0;
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        BubbleView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            bell = c.getDrawable(R.drawable.ic_stat_bell);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        int sizePx() {
            return (int) (64 * d);
        }

        void setState(int count, int overdue) {
            n = count;
            over = overdue;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getWidth();
            float r = 26 * d;
            float cx = s / 2f - 2 * d;
            float cy = s / 2f + 2 * d;

            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    n > 0 ? 0xFF6C4DF6 : 0xFF1E8E6B,
                    n > 0 ? 0xFFE8467B : 0xFF2AB58A, Shader.TileMode.CLAMP));
            p.setShadowLayer(6 * d, 0, 2 * d, 0x55000000);
            canvas.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.clearShadowLayer();

            int b = (int) (14 * d);
            bell.setBounds((int) (cx - b), (int) (cy - b), (int) (cx + b), (int) (cy + b));
            bell.draw(canvas);

            if (n > 0) {
                float br = 10 * d;
                float bx = cx + r * 0.72f;
                float by = cy - r * 0.72f;
                p.setColor(0xFFFFFFFF);
                canvas.drawCircle(bx, by, br + 1.5f * d, p);
                p.setColor(over > 0 ? 0xFFD7263D : 0xFFE8467B);
                canvas.drawCircle(bx, by, br, p);
                p.setColor(0xFFFFFFFF);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize((n > 99 ? 9 : 11) * d);
                p.setFakeBoldText(true);
                canvas.drawText(n > 99 ? "99+" : String.valueOf(n), bx, by + 4 * d, p);
                p.setFakeBoldText(false);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float mx = e.getRawX() - downX;
                    float my = e.getRawY() - downY;
                    if (!moved && Math.hypot(mx, my) > slop) moved = true;
                    if (moved) {
                        lp.x = (int) (startX + mx);
                        lp.y = (int) (startY + my);
                        wm.updateViewLayout(this, lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved) snap();
                    else openApp();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private void openApp() {
            Intent i = new Intent(BubbleService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("open_pending", true);
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }

        private void snap() {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            final int size = sizePx();
            int target = (lp.x + size / 2 < sw / 2) ? 0 : sw - size;
            lp.y = Math.max(0, Math.min(lp.y, sh - size));
            ValueAnimator a = ValueAnimator.ofInt(lp.x, target);
            a.setDuration(180);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    lp.x = (Integer) va.getAnimatedValue();
                    try {
                        wm.updateViewLayout(BubbleView.this, lp);
                    } catch (Exception ignored) {
                    }
                }
            });
            a.start();
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("bubbleX", target).putInt("bubbleY", lp.y).apply();
        }
    }
}
