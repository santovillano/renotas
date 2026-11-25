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
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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

import java.util.ArrayList;

/**
 * Pastilla flotante que se ve sobre otras apps y la pantalla de inicio.
 * Muestra un icono y un número por cada tipo de pendiente (recordatorios, tareas, compras).
 * Se esconde mientras Renotas está abierta.
 */
public class BubbleService extends Service {
    static final String CHANNEL = "renotas_bubble";
    static final String ACTION_REFRESH = "cl.vincentcalavera.renotas.REFRESH";
    static final String PREFS = "renotas_app";
    static volatile boolean running = false;
    static volatile boolean appForeground = false;
    static BubbleService instance;

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

    /** Con la app abierta la pastilla se esconde; al salir de la app vuelve a aparecer. */
    static void setAppForeground(boolean fg) {
        appForeground = fg;
        BubbleService s = instance;
        if (s != null && s.view != null) {
            s.view.setVisibility(fg ? View.GONE : View.VISIBLE);
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
        instance = this;
        if (view == null) addBubble();
        else applyState();
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

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private void loadCounts() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        view.setState(Math.max(0, sp.getInt("pRem", 0)), Math.max(0, sp.getInt("pOver", 0)),
                Math.max(0, sp.getInt("pTask", 0)), Math.max(0, sp.getInt("pShop", 0)));
    }

    private void placeX() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean right = sp.getInt("bubbleSide", 1) == 1;
        lp.x = right ? screenW() - lp.width : 0;
    }

    private void addBubble() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new BubbleView(this);
        loadCounts();
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(view.wPx(), view.hPx(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lp.y = Math.max(0, Math.min(sp.getInt("bubbleY", screenH() / 3), screenH() - lp.height));
        placeX();
        view.setVisibility(appForeground ? View.GONE : View.VISIBLE);
        wm.addView(view, lp);
    }

    private void applyState() {
        if (view == null || lp == null) return;
        loadCounts();
        lp.width = view.wPx();
        lp.height = view.hPx();
        if (!view.dragging) placeX();
        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
        super.onDestroy();
    }

    private class Seg {
        Drawable icon;
        String label;
        String tab;
        boolean alert;
        float x0;
        float x1;
    }

    private class BubbleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private final int slop;
        private final Drawable iBell;
        private final Drawable iTask;
        private final Drawable iCart;
        private final ArrayList<Seg> segs = new ArrayList<Seg>();
        private float pillW;
        private float pillH;
        private int wPx;
        private int hPx;
        boolean dragging = false;
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        BubbleView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            iBell = c.getDrawable(R.drawable.ic_b_bell);
            iTask = c.getDrawable(R.drawable.ic_b_task);
            iCart = c.getDrawable(R.drawable.ic_b_cart);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        int wPx() {
            return wPx;
        }

        int hPx() {
            return hPx;
        }

        private void add(Drawable ic, int n, String tab, boolean alert) {
            if (n <= 0) return;
            Seg s = new Seg();
            s.icon = ic;
            s.label = n > 99 ? "99+" : String.valueOf(n);
            s.tab = tab;
            s.alert = alert;
            segs.add(s);
        }

        void setState(int rem, int over, int task, int shop) {
            segs.clear();
            add(iBell, rem, "recordatorios", over > 0);
            add(iTask, task, "tareas", false);
            add(iCart, shop, "compras", false);
            pillH = 38 * d;
            if (segs.isEmpty()) {
                pillW = pillH;
            } else {
                p.setTextSize(13 * d);
                p.setFakeBoldText(true);
                float x = 3 * d;
                for (Seg s : segs) {
                    float w = 7 * d + 13 * d + 3 * d + p.measureText(s.label) + 7 * d;
                    s.x0 = x;
                    s.x1 = x + w;
                    x += w;
                }
                p.setFakeBoldText(false);
                pillW = x + 3 * d;
            }
            wPx = (int) Math.ceil(pillW + 8 * d);
            hPx = (int) Math.ceil(pillH + 8 * d);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float ox = 4 * d;
            float oy = 4 * d;
            boolean none = segs.isEmpty();
            RectF r = new RectF(ox, oy, ox + pillW, oy + pillH);

            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    none ? 0xFF1E8E6B : 0xFF6C4DF6,
                    none ? 0xFF2AB58A : 0xFFE8467B, Shader.TileMode.CLAMP));
            p.setShadowLayer(5 * d, 0, 2 * d, 0x55000000);
            canvas.drawRoundRect(r, pillH / 2, pillH / 2, p);
            p.setShader(null);
            p.clearShadowLayer();

            if (none) {
                float cx = ox + pillW / 2;
                float cy = oy + pillH / 2;
                Path check = new Path();
                check.moveTo(cx - 6 * d, cy);
                check.lineTo(cx - 1.5f * d, cy + 4.5f * d);
                check.lineTo(cx + 6.5f * d, cy - 5 * d);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.6f * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(0xFFFFFFFF);
                canvas.drawPath(check, p);
                p.setStyle(Paint.Style.FILL);
                p.setStrokeWidth(0);
                return;
            }

            p.setTextSize(13 * d);
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.LEFT);
            for (Seg s : segs) {
                float x0 = ox + s.x0;
                float x1 = ox + s.x1;
                if (s.alert) {
                    float h = pillH - 8 * d;
                    p.setColor(0xFFD7263D);
                    canvas.drawRoundRect(new RectF(x0, oy + 4 * d, x1, oy + 4 * d + h), h / 2, h / 2, p);
                }
                int ix = (int) (x0 + 7 * d);
                int iy = (int) (oy + pillH / 2 - 6.5f * d);
                s.icon.setBounds(ix, iy, (int) (ix + 13 * d), (int) (iy + 13 * d));
                s.icon.draw(canvas);
                p.setColor(0xFFFFFFFF);
                canvas.drawText(s.label, x0 + 7 * d + 13 * d + 3 * d, oy + pillH / 2 + 4.6f * d, p);
            }
            p.setFakeBoldText(false);
        }

        private String tabAt(float x) {
            float px = x - 4 * d;
            for (Seg s : segs) {
                if (px >= s.x0 && px <= s.x1) return s.tab;
            }
            return "inicio";
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
                    if (!moved && Math.hypot(mx, my) > slop) {
                        moved = true;
                        dragging = true;
                    }
                    if (moved) {
                        lp.x = (int) (startX + mx);
                        lp.y = (int) (startY + my);
                        wm.updateViewLayout(this, lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved) snap();
                    else openApp(tabAt(e.getX()));
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private void openApp(String tab) {
            Intent i = new Intent(BubbleService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("open_tab", tab);
            try {
                BubbleService.this.startActivity(i);
            } catch (Exception ignored) {
            }
        }

        private void snap() {
            int sw = screenW();
            int sh = screenH();
            final boolean right = (lp.x + lp.width / 2) >= sw / 2;
            int target = right ? sw - lp.width : 0;
            lp.y = Math.max(0, Math.min(lp.y, sh - lp.height));
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
            BubbleService.this.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("bubbleSide", right ? 1 : 0).putInt("bubbleY", lp.y).apply();
        }
    }
}
