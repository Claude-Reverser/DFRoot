package df.root;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.widget.Toast;

public class SettingsFragment extends PreferenceFragment {
    private RunCoordinator runs;
    private SharedPreferences dePrefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        runs = AppRuns.get(ctx);
        dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        SwitchPreference bootPref = (SwitchPreference) findPreference("boot_start");
        bootPref.setPersistent(false);
        int state = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        bootPref.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        bootPref.setOnPreferenceChangeListener((pref, value) -> {
            try {
                if ((Boolean) value) {
                    String manager = dePrefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
                    if (manager == null || !ExploitRunner.hasKsud(
                            ctx.getPackageManager().getApplicationInfo(manager, 0))) {
                        throw new IllegalStateException("Select an available SU manager first");
                    }
                }
                boolean changed = runs.whileIdle(() ->
                        ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                                (Boolean) value ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP));
                if (!changed) showError("Settings cannot change during a run");
                return changed;
            } catch (Exception e) {
                showError("Could not change Start at Boot: " + e.getMessage());
                return false;
            }
        });

        SwitchPreference softRebootPref = (SwitchPreference) findPreference("soft_reboot");
        softRebootPref.setPersistent(false);
        softRebootPref.setChecked(dePrefs.getBoolean("soft_reboot", false));
        softRebootPref.setOnPreferenceChangeListener((pref, value) ->
                saveBoolean("soft_reboot", (Boolean) value));

        SwitchPreference disableModulesPref = (SwitchPreference) findPreference("disable_modules");
        disableModulesPref.setPersistent(false);
        disableModulesPref.setChecked(dePrefs.getBoolean("disable_modules", false));
        disableModulesPref.setOnPreferenceChangeListener((pref, value) ->
                saveBoolean("disable_modules", (Boolean) value));
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().setEnabled(!runs.isRunning());
        ((SwitchPreference) findPreference("soft_reboot")).setChecked(
                dePrefs.getBoolean("soft_reboot", false));
        ((SwitchPreference) findPreference("disable_modules")).setChecked(
                dePrefs.getBoolean("disable_modules", false));
    }

    // Bootstrap reads the XML directly; report persistence failure to the user.
    @SuppressLint("ApplySharedPref")
    private boolean saveBoolean(String key, boolean value) {
        boolean oldValue = dePrefs.getBoolean(key, false);
        boolean[] saved = {false};
        boolean idle = runs.whileIdle(() -> {
            saved[0] = dePrefs.edit().putBoolean(key, value).commit();
            if (!saved[0]) {
                // commit() can update memory even if disk persistence failed.
                dePrefs.edit().putBoolean(key, oldValue).commit();
            }
        });
        if (!idle) showError("Settings cannot change during a run");
        else if (!saved[0]) showError("Could not save this setting");
        return idle && saved[0];
    }

    private void showError(String text) {
        Toast.makeText(getActivity(), text, Toast.LENGTH_LONG).show();
    }
}
