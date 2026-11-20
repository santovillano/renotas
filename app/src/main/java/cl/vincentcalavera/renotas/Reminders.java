package cl.vincentcalavera.renotas;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Guarda los recordatorios con fecha y los programa como alarmas del sistema. */
final class Reminders {
    static final String PREFS = "renotas_reminders";
    static final String KEY = "list";

    private Reminders() {}

    /** Reemplaza todas las alarmas por la lista nueva: [{"id":"..","text":"..","at":epochMs}]. */
    static void sync(Context ctx, String json) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cancelAll(ctx, sp.getString(KEY, "[]"));
        sp.edit().putString(KEY, json).apply();
        scheduleAll(ctx, json);
    }

    /** Vuelve a programar lo guardado (después de reiniciar el teléfono). */
    static void restore(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        scheduleAll(ctx, sp.getString(KEY, "[]"));
    }

    private static PendingIntent pending(Context ctx, String id, String text) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction("cl.vincentcalavera.renotas.REMINDER." + id);
        i.putExtra("id", id);
        i.putExtra("text", text);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelAll(Context ctx, String json) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        try {
            JSONArray arr = new JSONArray(json);
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                am.cancel(pending(ctx, o.getString("id"), ""));
            }
        } catch (Exception ignored) {
        }
    }

    private static void scheduleAll(Context ctx, String json) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        try {
            JSONArray arr = new JSONArray(json);
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                long at = o.optLong("at", 0);
                if (at <= now) continue;
                PendingIntent pi = pending(ctx, o.getString("id"), o.optString("text", ""));
                boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
                try {
                    if (exact) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                    }
                } catch (SecurityException e) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
