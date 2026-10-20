package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "dfroot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        if (new File("/dev/df").exists()) {
            Log.i(TAG, "boot: an attempt already exists; skipping without inferring root status");
            return;
        }
        final Context deCtx = context.createDeviceProtectedStorageContext();
        // Both broadcasts can arrive during the same boot. A failed attempt is
        // not permission to silently retry at unlock or after process restart.
        synchronized (BootReceiver.class) {
            int bootCount;
            try {
                bootCount = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BOOT_COUNT, -1);
            } catch (RuntimeException e) {
                Log.e(TAG, "boot: cannot identify this boot; automatic launch skipped", e);
                return;
            }
            if (bootCount < 0) {
                Log.e(TAG, "boot: boot count unavailable; automatic launch skipped");
                return;
            }
            var attempts = deCtx.getSharedPreferences("boot_attempt", Context.MODE_PRIVATE);
            if (attempts.getInt("boot_count", -1) == bootCount) {
                Log.i(TAG, "boot: request already handled this boot");
                return;
            }
            if (!attempts.edit().putInt("boot_count", bootCount).commit()) {
                Log.e(TAG, "boot: cannot record attempt; automatic launch skipped");
                return;
            }
        }
        Log.i(TAG, "boot: " + action);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Log.e(TAG, "boot: PowerManager unavailable");
            return;
        }
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        wl.acquire(120_000L);
        Runnable release = () -> {
            try {
                if (wl.isHeld()) wl.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "boot: wake lock already released", e);
            }
        };
        try {
            AppRuns.start(deCtx, Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                    ? "locked boot" : "boot", release);
        } catch (RuntimeException e) {
            release.run();
            Log.e(TAG, "boot: could not schedule launch", e);
        }
    }
}
