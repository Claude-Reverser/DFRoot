package df.root;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int SAVE_LOGS = 1;
    private static final String PENDING_EXPORT = "pendingExport";

    private Button btnRun;
    private ScrollView outputScroll;
    private TextView outputView;
    private Spinner spinnerSuManager;
    private TextView statusView;
    private ProgressBar runProgress;
    private Context mDeCtx;
    private RunCoordinator mRuns;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private int mValidSuManagerPos = 0;
    private boolean mVisible;
    private boolean mRefreshPending;
    private boolean mExportPreparing;
    private File mPendingExport;
    private String mRenderedLog = "";
    private final Runnable mRefresh = this::refreshLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        mRuns = AppRuns.get(this);
        setContentView(R.layout.activity_main);
        if (getActionBar() != null) getActionBar().setSubtitle("@diabl0w github/xda");

        btnRun = findViewById(R.id.btnRun);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);
        statusView = findViewById(R.id.statusView);
        runProgress = findViewById(R.id.runProgress);
        if (savedInstanceState != null) {
            String name = savedInstanceState.getString(PENDING_EXPORT);
            if (name != null && name.matches("dfroot-export-[a-zA-Z0-9-]+\\.txt")) {
                mPendingExport = new File(getCacheDir(), name);
                AppRuns.ACTIVE_EXPORTS.add(name);
            }
        }
        File cache = getCacheDir();
        AppRuns.IO.execute(() -> AppRuns.pruneExports(cache));

        PackageManager pm = getPackageManager();
        List<SuManagerEntry> entries = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!ExploitRunner.hasKsud(ai)) continue;
            entries.add(new SuManagerEntry(ai.packageName, pm.getApplicationLabel(ai), pm.getApplicationIcon(ai)));
        }
        entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        entries.add(0, new SuManagerEntry(null, "Select a SU Manager", null));

        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        boolean savedFound = false;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).packageName.equals(saved)) {
                spinnerSuManager.setSelection(i);
                mValidSuManagerPos = i;
                savedFound = true;
                break;
            }
        }
        if (!savedFound && saved != null) { // Saved manager was uninstalled
            mRuns.whileIdle(() -> prefs.edit().remove(ExploitRunner.PREF_SU_MANAGER).apply());
        }

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                if (!mRuns.whileIdle(() ->
                        prefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, e.packageName).apply())) {
                    spinnerSuManager.setSelection(mValidSuManagerPos);
                    return;
                }
                mValidSuManagerPos = pos;
                updateRunButton();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateRunButton();

        btnRun.setOnClickListener(v -> {
            AppRuns.start(this, "manual", null);
            updateRunButton();
            refreshLog();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mVisible = true;
        refreshLog();
    }

    @Override
    protected void onStop() {
        mVisible = false;
        mMain.removeCallbacks(mRefresh);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (mPendingExport != null) state.putString(PENDING_EXPORT, mPendingExport.getName());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) discardExport();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            if (!mRuns.isRunning()) startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_save_logs) {
            prepareExport();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateRunButton() {
        boolean running = mRuns.isRunning();
        btnRun.setEnabled(!running && mValidSuManagerPos >= 1 && !new File("/dev/df").exists());
        spinnerSuManager.setEnabled(!running);
        runProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_settings).setEnabled(!mRuns.isRunning());
        menu.findItem(R.id.action_save_logs).setEnabled(
                !mExportPreparing && mPendingExport == null && !AppRuns.EXPORT_BUSY.get());
        return super.onPrepareOptionsMenu(menu);
    }

    private void refreshLog() {
        if (!mVisible || mRefreshPending) return;
        mRefreshPending = true;
        AppRuns.IO.execute(() -> {
            RunCoordinator.Snapshot loaded = null;
            String error = null;
            try {
                loaded = mRuns.snapshot();
            } catch (RuntimeException e) {
                error = "Could not read saved logs: " + e.getMessage();
            }
            RunCoordinator.Snapshot snapshot = loaded;
            String readError = error;
            mMain.post(() -> {
                mRefreshPending = false;
                if (!mVisible || isDestroyed()) return;
                boolean follow = outputScroll.getScrollY() + outputScroll.getHeight()
                        >= outputView.getHeight() - 32;
                if (snapshot != null && !snapshot.text.equals(mRenderedLog)) {
                    mRenderedLog = snapshot.text;
                    outputView.setText(snapshot.text);
                    if (follow) outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
                }
                statusView.setText(snapshot == null ? readError : snapshot.status);
                updateRunButton();
                mMain.removeCallbacks(mRefresh);
                mMain.postDelayed(mRefresh, 500);
            });
        });
    }

    private void prepareExport() {
        if (mExportPreparing || mPendingExport != null || AppRuns.EXPORT_BUSY.get()) return;
        mExportPreparing = true;
        invalidateOptionsMenu();
        Context app = getApplicationContext();
        AppRuns.EXPORT.execute(() -> {
            File staged = null;
            try {
                String text = mRuns.exportText();
                if (text.isEmpty()) throw new IOException("No logs to save");
                // Export owns a frozen snapshot, independent of later runs or
                // activity recreation while the system document picker is open.
                staged = File.createTempFile("dfroot-export-", ".txt", app.getCacheDir());
                AppRuns.ACTIVE_EXPORTS.add(staged.getName());
                AppRuns.pruneExports(app.getCacheDir());
                try (FileOutputStream out = new FileOutputStream(staged)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                File ready = staged;
                mMain.post(() -> {
                    mExportPreparing = false;
                    if (!mVisible || isFinishing() || isDestroyed()) {
                        deleteExport(ready);
                        return;
                    }
                    mPendingExport = ready;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TITLE, "DFRoot-logs-" + System.currentTimeMillis() + ".txt");
                    try {
                        startActivityForResult(intent, SAVE_LOGS);
                    } catch (RuntimeException e) {
                        discardExport();
                        Toast.makeText(app, "Could not open the document picker: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                    invalidateOptionsMenu();
                });
            } catch (Exception e) {
                if (staged != null) deleteExport(staged);
                mMain.post(() -> {
                    mExportPreparing = false;
                    if (!isDestroyed()) invalidateOptionsMenu();
                    Toast.makeText(app, "Could not prepare logs: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void discardExport() {
        if (mPendingExport != null) deleteExport(mPendingExport);
        mPendingExport = null;
    }

    private static void deleteExport(File file) {
        AppRuns.ACTIVE_EXPORTS.remove(file.getName());
        file.delete();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_LOGS) return;
        File staged = mPendingExport;
        Uri destination = data == null ? null : data.getData();
        if (resultCode != RESULT_OK) {
            discardExport();
            invalidateOptionsMenu();
            return;
        }
        mPendingExport = null;
        Context app = getApplicationContext();
        if (!AppRuns.EXPORT_BUSY.compareAndSet(false, true)) {
            if (staged != null) deleteExport(staged);
            Toast.makeText(app, "Another log export is still in progress", Toast.LENGTH_LONG).show();
            invalidateOptionsMenu();
            return;
        }
        invalidateOptionsMenu();
        AppRuns.EXPORT.execute(() -> {
            String result;
            try {
                if (staged == null || !staged.isFile())
                    throw new IOException("Saved snapshot is unavailable; choose Save logs again");
                if (destination == null || !"content".equals(destination.getScheme()))
                    throw new IOException("No document destination was provided");
                try (OutputStream out = app.getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("The document provider did not open the file");
                    Files.copy(staged.toPath(), out);
                }
                result = "Logs saved";
            } catch (Exception e) {
                result = "Could not save logs: " + e.getMessage();
            } finally {
                if (staged != null) deleteExport(staged);
                AppRuns.EXPORT_BUSY.set(false);
            }
            String message = result;
            mMain.post(() -> {
                if (!isDestroyed()) invalidateOptionsMenu();
                Toast.makeText(app, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private static class SuManagerEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static class SuManagerAdapter extends ArrayAdapter<SuManagerEntry> {
        SuManagerAdapter(Context ctx, List<SuManagerEntry> items) {
            super(ctx, R.layout.item_su_manager, items);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.FALSE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager_closed, parent, false);
            v.setTag(Boolean.FALSE);
            return bindClosedView(pos, v);
        }

        @Override
        public View getDropDownView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.TRUE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager, parent, false);
            v.setTag(Boolean.TRUE);
            return bindView(pos, v);
        }

        @Override
        public boolean isEnabled(int pos) {
            return getItem(pos).packageName != null;
        }

        private View bindClosedView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ImageView icon = v.findViewById(R.id.iconApp);
            TextView label = v.findViewById(R.id.labelApp);
            if (e.packageName == null) {
                icon.setVisibility(View.GONE);
                label.setVisibility(View.VISIBLE);
                label.setText(e.label);
            } else {
                icon.setVisibility(View.VISIBLE);
                label.setVisibility(View.GONE);
                icon.setImageDrawable(e.icon);
            }
            return v;
        }

        private View bindView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ((ImageView) v.findViewById(R.id.iconApp)).setImageDrawable(e.icon);
            ((TextView)  v.findViewById(R.id.labelApp)).setText(e.label);
            return v;
        }
    }
}
