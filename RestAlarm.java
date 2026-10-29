package com.pedro.farmandoaura;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Fim do descanso: vibra mesmo com o app fechado ou a tela apagada. */
public class RestAlarm extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context ctx, Intent intent) {
        RestService.visibilityChanged();
        Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null) return;
        long[] p = {0, 400, 150, 400, 150, 700};
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(p, -1), aa);
        else vib.vibrate(p, -1, aa);
    }
}
