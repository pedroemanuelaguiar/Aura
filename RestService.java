package com.pedro.farmandoaura;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Mantém o treino vivo fora do app: notificação com o timer, alarme que vibra no fim do descanso
 * (mesmo com a tela apagada) e a janela flutuante por cima de outros apps.
 */
public class RestService extends Service {
    static final String ACTION_PRIMARY = "com.pedro.farmandoaura.PRIMARY";
    private static final String CHANNEL = "treino";
    private static final int NOTIF_ID = 7;

    // Estado vindo da página (syncNative no JS).
    static String mode = "off", name = "", set = "", load = "", title = "", sub = "";
    static long end = 0;
    private static long scheduledEnd = -1;
    private static RestService instance;
    private static int posX = 24, posY = 320;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout bubble;
    private TextView bubbleTime, bubbleSub, bubbleBtn;
    private String lastPhase = "";

    /** Tela ligou com o celular bloqueado: traz o treino por cima da tela de bloqueio. */
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refresh(false);
            if (!Intent.ACTION_SCREEN_ON.equals(i.getAction()) || "off".equals(mode) || MainActivity.visible) return;
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) return;
            Intent a = new Intent(RestService.this, MainActivity.class);
            a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { startActivity(a); } catch (Exception ignored) {}
        }
    };

    /** Chamado pela página sempre que o treino muda. */
    static void update(final Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json);
            mode = o.optString("mode", "off");
            end = o.optLong("end", 0);
            name = o.optString("name", "");
            set = o.optString("set", "");
            load = o.optString("load", "");
            title = o.optString("title", "");
            sub = o.optString("sub", "");
        } catch (Exception e) {
            mode = "off";
        }
        scheduleAlarm(ctx);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if ("off".equals(mode)) {
                    if (instance != null) instance.shutdown();
                } else if (instance != null) {
                    instance.refresh(true);
                } else {
                    try {
                        Intent i = new Intent(ctx, RestService.class);
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    /** O app foi aberto ou minimizado: esconde ou mostra a janela flutuante. */
    static void visibilityChanged() {
        if (instance != null) instance.refresh(false);
    }

    private static boolean resting() { return "rest".equals(mode) && end > System.currentTimeMillis(); }

    private static void scheduleAlarm(Context ctx) {
        long want = "rest".equals(mode) && end > System.currentTimeMillis() ? end : 0;
        if (want == scheduledEnd) return;
        scheduledEnd = want;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, RestAlarm.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (want == 0) return;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, want, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, want, pi);
    }

    /** Botão "Descanso": registra a série na página e começa o descanso. */
    static void primary(Context ctx) {
        MainActivity a = MainActivity.current;
        if (a != null) a.runPrimary();
        else openApp(ctx);
    }

    static void openApp(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Treino em andamento", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        if ("off".equals(mode)) { shutdown(); return START_NOT_STICKY; }
        if (intent != null && ACTION_PRIMARY.equals(intent.getAction())) primary(this);
        refresh(true);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        return START_NOT_STICKY;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            handler.postDelayed(this, 500);
        }
    };

    private String phase() {
        if ("done".equals(mode)) return "done";
        return resting() ? "rest" : "go";
    }

    private void refresh(boolean force) {
        if ("off".equals(mode)) return;
        String ph = phase();
        if (force || !ph.equals(lastPhase)) {
            lastPhase = ph;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();
        boolean show = screenOn && !MainActivity.visible && canOverlay(this);
        if (show) showBubble(ph); else hideBubble();
    }

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    private PendingIntent openIntent() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i == null) i = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent primaryIntent() {
        Intent i = new Intent(this, RestService.class).setAction(ACTION_PRIMARY);
        return PendingIntent.getService(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openIntent())
                .setColor(0xFFFF5A36);
        String ph = phase();
        if ("done".equals(ph)) {
            b.setContentTitle(title.isEmpty() ? "Treino concluído" : title).setContentText(sub);
        } else if ("rest".equals(ph)) {
            b.setContentTitle("Descanso").setContentText("Próxima: " + name + " · " + set)
                    .setUsesChronometer(true).setShowWhen(true).setWhen(end);
            if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        } else {
            b.setContentTitle("Bora! " + name).setContentText(set + " · " + load)
                    .addAction(new Notification.Action.Builder(null, "Iniciar descanso", primaryIntent()).build());
        }
        return b.build();
    }

    // ---------- janela flutuante ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildBubble() {
        bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setPadding(dp(14), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF215171B);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(2), 0xFFFF5A36);
        bubble.setBackground(bg);
        bubble.setElevation(dp(8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        bubbleTime = new TextView(this);
        bubbleTime.setTextColor(0xFFEFEAE3);
        bubbleTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        bubbleTime.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleSub = new TextView(this);
        bubbleSub.setTextColor(0xFF9AA3B0);
        bubbleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bubbleSub.setMaxWidth(dp(170));
        bubbleSub.setSingleLine(true);
        texts.addView(bubbleTime);
        texts.addView(bubbleSub);
        bubble.addView(texts);

        bubbleBtn = new TextView(this);
        bubbleBtn.setText("▶  Descanso");
        bubbleBtn.setTextColor(0xFF15171B);
        bubbleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bubbleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFFF5A36);
        bb.setCornerRadius(dp(22));
        bubbleBtn.setBackground(bb);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(12);
        bubble.addView(bubbleBtn, blp);
        bubbleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                primary(RestService.this);
            }
        });

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) bubble.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); startX = lp.x; startY = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = posX = startX + (int) dx;
                            lp.y = posY = startY + (int) dy;
                            try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) openApp(RestService.this);
                        return true;
                }
                return false;
            }
        });
    }

    private void showBubble(String ph) {
        if (bubble == null) buildBubble();
        if ("rest".equals(ph)) {
            long left = Math.max(0, (end - System.currentTimeMillis() + 999) / 1000);
            bubbleTime.setText(left / 60 + ":" + (left % 60 < 10 ? "0" : "") + left % 60);
            bubbleTime.setTextColor(0xFFEFEAE3);
            bubbleSub.setText("Próx: " + set);
            bubbleBtn.setVisibility(View.GONE);
        } else if ("done".equals(ph)) {
            bubbleTime.setText("Fim!");
            bubbleTime.setTextColor(0xFF3FD28B);
            bubbleSub.setText(title.isEmpty() ? "Treino concluído" : title);
            bubbleBtn.setVisibility(View.GONE);
        } else {
            bubbleTime.setText("Bora!");
            bubbleTime.setTextColor(0xFF3FD28B);
            bubbleSub.setText(set + " · " + load);
            bubbleBtn.setVisibility(View.VISIBLE);
        }
        if (bubble.getParent() == null) {
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = posX;
            lp.y = posY;
            try { wm.addView(bubble, lp); } catch (Exception ignored) {}
        }
    }

    private void hideBubble() {
        if (bubble != null && bubble.getParent() != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
        }
    }

    private void shutdown() {
        handler.removeCallbacks(ticker);
        hideBubble();
        scheduleAlarm(this);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        hideBubble();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
