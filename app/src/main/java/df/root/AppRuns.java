package df.root;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide ownership; never retains an Activity or its views. */
final class AppRuns {
    static final ExecutorService IO = Executors.newFixedThreadPool(2);
    static final ExecutorService EXPORT = Executors.newSingleThreadExecutor();
    static final AtomicBoolean EXPORT_BUSY = new AtomicBoolean();
    static final Set<String> ACTIVE_EXPORTS = ConcurrentHashMap.newKeySet();
    private static RunCoordinator coordinator;

    private AppRuns() {}

    static synchronized RunCoordinator get(Context context) {
        if (coordinator == null) {
            Context app = context.getApplicationContext().createDeviceProtectedStorageContext();
            coordinator = new RunCoordinator(Executors.newSingleThreadExecutor(),
                    new RunLogStore(app.getFilesDir()), message -> Log.i("dfroot", message.trim()));
        }
        return coordinator;
    }

    static boolean start(Context context, String origin, Runnable completion) {
        Context app = context.getApplicationContext().createDeviceProtectedStorageContext();
        return get(app).start(origin, reporter -> {
            try (RunService.Lease ignored = RunService.acquire(app)) {
                return ExploitRunner.run(app, reporter);
            }
        }, completion);
    }

    /** Only our own abandoned snapshots; active picker snapshots are retained. */
    static void pruneExports(File cache) {
        File[] files = cache.listFiles(file -> file.isFile()
                && file.getName().matches("dfroot-export-[a-zA-Z0-9-]+\\.txt"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long oldest = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        int retained = 0;
        for (File file : files) {
            if (ACTIVE_EXPORTS.contains(file.getName())) continue;
            if (file.lastModified() < oldest || retained >= 4) file.delete();
            else retained++;
        }
    }
}
