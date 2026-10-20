package df.root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Foreground lifetime for one coordinator-owned run; never starts native work. */
public final class RunService extends Service {
    private static final String TAG = "dfroot";
    private static final String CHANNEL_ID = "dfroot_run";
    private static final String EXTRA_TOKEN = "df.root.RUN_TOKEN";
    private static final int NOTIFICATION_ID = 1;
    private static final long READY_TIMEOUT_SECONDS = 5;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // These references never leave the process. A redelivered intent cannot
    // create a lease or restart work after process death.
    private static Lease active;
    private static RunService currentService;
    private Lease ownedLease;
    private int latestStartId;
    private boolean destroyed;

    /** Called on the coordinator worker, before creating any native resources. */
    static Lease acquire(Context app) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Foreground readiness must not block the main thread");
        Lease lease = new Lease();
        synchronized (LOCK) {
            if (active != null)
                throw new IllegalStateException("A foreground run is already owned");
            active = lease;
        }
        boolean acquired = false;
        try {
            Intent intent = new Intent(app, RunService.class).putExtra(EXTRA_TOKEN, lease.token);
            if (app.startForegroundService(intent) == null)
                throw new IllegalStateException("Foreground service could not be started");
            if (!lease.ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new TimeoutException("Foreground service was not ready; launch skipped");
            synchronized (LOCK) {
                if (lease.failure != null) throw lease.failure;
                if (active != lease || lease.closed || lease.owner == null
                        || lease.owner.destroyed)
                    throw new IllegalStateException("Foreground service stopped before launch");
                acquired = true;
                return lease;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (!acquired) lease.close();
        }
    }

    /** Close only when the runner has confirmed that its native child exited. */
    static final class Lease implements AutoCloseable {
        private final String token = UUID.randomUUID().toString();
        private final CountDownLatch ready = new CountDownLatch(1);
        // All fields below are guarded by LOCK.
        private RunService owner;
        private Exception failure;
        private boolean closed;

        private Lease() {}

        @Override
        public void close() {
            final RunService service;
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                if (active == this) active = null;
                service = currentService;
                ready.countDown();
            }
            // Main-thread cleanup rechecks both the instance and active lease.
            // An old close must not stop a newly acquired run on the same service.
            if (service != null) MAIN.post(service::stopIfIdle);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (LOCK) {
            currentService = this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final Lease lease;
        synchronized (LOCK) {
            latestStartId = startId;
            String token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
            lease = active != null && active.token.equals(token) ? active : null;
            if (lease != null) {
                ownedLease = lease;
                lease.owner = this;
            }
        }
        if (lease == null) {
            // Cancelled, stale or redelivered starts never execute work.
            stopIfIdle();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            synchronized (LOCK) {
                if (active == lease && !lease.closed && !destroyed) {
                    lease.ready.countDown();
                    Log.i(TAG, "run service: foreground owner ready");
                }
            }
            // Readiness might have timed out while Android promoted the service.
            stopIfIdle();
        } catch (RuntimeException e) {
            synchronized (LOCK) {
                lease.failure = e;
                lease.ready.countDown();
            }
            Log.e(TAG, "run service: foreground promotion failed; launch skipped", e);
            lease.close();
            stopIfIdle();
        }
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("NotificationManager unavailable");
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "DFRoot startup", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while DFRoot is starting");
        manager.createNotificationChannel(channel);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("DFRoot is starting")
                .setContentText("Open DFRoot to view progress")
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    /** Main thread only; stop IDs also protect starts not delivered to us yet. */
    private void stopIfIdle() {
        synchronized (LOCK) {
            if (destroyed || currentService != this || active != null) return;
            if (ownedLease != null && !ownedLease.closed) return;
            if (latestStartId != 0 && stopSelfResult(latestStartId)) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                ownedLease = null;
                Log.i(TAG, "run service: foreground owner released");
            }
        }
    }

    @Override
    public void onDestroy() {
        synchronized (LOCK) {
            destroyed = true;
            if (currentService == this) currentService = null;
            if (ownedLease != null && ownedLease.owner == this) {
                ownedLease.owner = null;
                if (!ownedLease.closed) {
                    ownedLease.failure = new IllegalStateException("Foreground owner was destroyed");
                    ownedLease.ready.countDown();
                    Log.w(TAG, "run service: owner destroyed during a run; no automatic retry");
                }
                ownedLease = null;
            }
        }
        // Android removes this service's notification. Do not cancel by shared
        // notification ID here: a newer service instance may already own it.
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
