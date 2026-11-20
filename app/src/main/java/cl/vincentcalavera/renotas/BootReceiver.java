package cl.vincentcalavera.renotas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a programar los avisos cuando el teléfono se reinicia. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Reminders.restore(ctx);
            boolean on = ctx.getSharedPreferences("renotas_app", Context.MODE_PRIVATE)
                    .getBoolean("bubbleEnabled", false);
            if (on && android.provider.Settings.canDrawOverlays(ctx)) {
                BubbleService.start(ctx);
            }
        }
    }
}
