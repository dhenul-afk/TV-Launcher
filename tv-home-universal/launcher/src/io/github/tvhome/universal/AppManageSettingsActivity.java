package io.github.tvhome.universal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Kelola Aplikasi Home Screen": show or hide applications, move them and mark favourites.
 * Hiding only changes this launcher's own list; nothing is uninstalled, no permission or system
 * setting is touched, and this launcher plus the system Home entry can never be hidden.
 * Choices are stored in SharedPreferences, so they survive a reboot.
 */
public class AppManageSettingsActivity extends Activity {

    private final List<AppEntry> apps = new ArrayList<>();
    private Set<String> hidden = new LinkedHashSet<>();
    private LinearLayout list;
    private TextView summary;
    private Button showAllButton;
    private boolean updating;
    private String focusPackage;
    private final java.util.Map<String, Switch> toggles = new java.util.HashMap<>();

    static final class AppEntry {
        String pkg;
        String label;
        ComponentName component;
        boolean protectedApp;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        hidden = AppVisibility.readHidden(this);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, 0);

        summary = new TextView(this);
        summary.setTextSize(11);
        summary.setTextColor(0x99FFFFFF);
        summary.setPadding(dp(14), 0, dp(14), dp(6));
        panel.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        showAllButton = new Button(this);
        Button showAll = showAllButton;
        showAll.setText(UiText.get(R.string.app_manage_show_all));
        showAll.setTag("widget_suboption");
        showAll.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        showAll.setSingleLine(true);
        showAll.setAllCaps(false);
        MenuIcon.attach(showAll, "apps", 19, Color.WHITE);
        showAll.setOnClickListener(v -> confirmShowAll());
        panel.addView(showAll, new LinearLayout.LayoutParams(-1, -2));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));

        loadApps();
        rebuildRows();

        SettingsPopup.show(this, UiText.get(R.string.app_manage_title), panel, showAllButton);
    }

    private String systemHomePackage() {
        ActivityInfo home = TvPlatform.systemHome(this);
        return home == null ? "" : home.packageName;
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        String self = getPackageName();
        String home = systemHomePackage();
        List<ResolveInfo> found = new ArrayList<>(
                pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo info : found) {
            ActivityInfo activityInfo = info.activityInfo;
            if (activityInfo == null || !seen.add(activityInfo.packageName)) continue;
            if (activityInfo.packageName.equals(self)) continue;
            AppEntry entry = new AppEntry();
            entry.pkg = activityInfo.packageName;
            entry.label = String.valueOf(info.loadLabel(pm));
            entry.component = new ComponentName(activityInfo.packageName, activityInfo.name);
            entry.protectedApp = AppVisibility.isProtected(self, home, entry.pkg);
            apps.add(entry);
        }
        // The system Home shortcut is offered as an entry so users recognise it as protected.
        ActivityInfo nativeHome = TvPlatform.systemHome(this);
        if (nativeHome != null) {
            boolean present = false;
            for (AppEntry entry : apps) if (entry.pkg.equals(nativeHome.packageName)) present = true;
            if (!present) {
                AppEntry entry = new AppEntry();
                entry.pkg = nativeHome.packageName;
                entry.label = UiText.get(R.string.system_home);
                entry.component = new ComponentName(nativeHome.packageName, nativeHome.name);
                entry.protectedApp = true;
                apps.add(entry);
            }
        }
        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    private boolean isVisible(AppEntry entry) {
        if (entry.protectedApp) return true;
        return !hidden.contains(entry.pkg);
    }

    private void rebuildRows() {
        updating = true;
        list.removeAllViews();
        toggles.clear();
        int hiddenCount = 0;
        for (final AppEntry entry : apps) {
            if (!entry.protectedApp && hidden.contains(entry.pkg)) hiddenCount++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            Switch toggle = new Switch(this);
            toggle.setText(entry.label);
            toggle.setTag("widget_suboption");
            toggle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            toggle.setSingleLine(true);
            toggle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            toggle.setTextSize(13);
            toggle.setMinHeight(dp(36));
            toggle.setSwitchMinWidth(dp(32));
            toggle.setSwitchPadding(dp(6));
            toggle.setChecked(isVisible(entry));
            toggle.setFocusable(true);
            MenuIcon.attach(toggle, "apps", 19, Color.WHITE);
            if (entry.protectedApp) {
                toggle.setEnabled(false);
                toggle.setAlpha(0.55f);
                toggle.setContentDescription(entry.label + " · "
                        + UiText.get(R.string.app_manage_protected));
            }
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (updating) return;
                applyVisibility(entry, checked);
            });
            toggles.put(entry.pkg, toggle);
            row.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1f));

            Button arrange = new Button(this);
            arrange.setText(UiText.get(R.string.app_manage_arrange));
            arrange.setTag("widget_suboption");
            arrange.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            arrange.setSingleLine(true);
            arrange.setAllCaps(false);
            arrange.setTextSize(11);
            arrange.setMinHeight(dp(30));
            arrange.setEnabled(!entry.protectedApp);
            arrange.setOnClickListener(v -> showArrange(entry));
            row.addView(arrange, new LinearLayout.LayoutParams(-2, dp(34)));

            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        summary.setText(String.format(Locale.getDefault(), "%d · %s",
                apps.size(), hiddenCount > 0
                        ? hiddenCount + " " + UiText.get(R.string.app_manage_hidden_count)
                        : UiText.get(R.string.app_manage_nothing_hidden)));
        updating = false;
        Switch target = focusPackage == null ? null : toggles.get(focusPackage);
        if (target != null && target.isEnabled()) target.requestFocus();
        else if (showAllButton != null) showAllButton.requestFocus();
    }

    private void applyVisibility(AppEntry entry, boolean visible) {
        focusPackage = entry.pkg;
        Set<String> next = AppVisibility.toggle(hidden, entry.pkg, visible,
                getPackageName(), systemHomePackage());
        if (next.equals(hidden)) {
            android.widget.Toast.makeText(this, UiText.get(R.string.app_manage_protected),
                    android.widget.Toast.LENGTH_SHORT).show();
            rebuildRows();
            return;
        }
        hidden = next;
        AppVisibility.writeHidden(this, hidden);
        rebuildRows();
        summary.requestFocus();
    }

    private void confirmShowAll() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(UiText.get(R.string.app_manage_show_all))
                .setMessage(UiText.get(R.string.app_manage_show_all_message))
                .setPositiveButton(UiText.get(R.string.ui_032), (d, w) -> {
                    hidden = AppVisibility.showAll(hidden);
                    AppVisibility.writeHidden(this, hidden);
                    rebuildRows();
                })
                .setNegativeButton(UiText.get(R.string.ui_026), null)
                .create();
        dialog.show();
        GlassControls.dialog(dialog);
    }

    private void showArrange(final AppEntry entry) {
        focusPackage = entry.pkg;
        int position = indexOf(entry.pkg);
        String[] options = {
                UiText.get(R.string.app_manage_favourite) + " (" + UiText.get(R.string.app_manage_position)
                        + " 1)",
                UiText.get(R.string.app_manage_move_up),
                UiText.get(R.string.app_manage_move_down),
                UiText.get(R.string.app_manage_open_info)
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(entry.label + " · " + UiText.get(R.string.app_manage_position) + " " + (position + 1))
                .setItems(options, (d, which) -> {
                    if (which == 0) move(entry.pkg, 0);
                    else if (which == 1) move(entry.pkg, Math.max(0, position - 1));
                    else if (which == 2) move(entry.pkg, position + 1);
                    else openAppInfo(entry);
                })
                .setNegativeButton(UiText.get(R.string.ui_026), null)
                .create();
        dialog.show();
        GlassControls.dialog(dialog);
    }

    private int indexOf(String pkg) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** Favourites and order share the single ordered list the launcher already stores. */
    private void move(String pkg, int position) {
        List<String> order = storedOrder();
        AppOrder.move(order, pkg, position);
        orderPrefs().edit().putString("app_order_v1", String.join("\n", order)).apply();
        loadApps();
        rebuildRows();
    }

    private List<String> storedOrder() {
        String saved = orderPrefs().getString("app_order_v1", "");
        List<String> order = new ArrayList<>();
        if (!saved.isEmpty()) Collections.addAll(order, saved.split("\n"));
        for (AppEntry entry : apps) if (!order.contains(entry.pkg)) order.add(entry.pkg);
        return order;
    }

    private android.content.SharedPreferences orderPrefs() {
        // MainActivity stores the order through Activity.getPreferences(), which is the file named
        // after its class; the same file keeps both screens in sync.
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    /** Read-only shortcut to the system app-info page; no setting is changed here. */
    private void openAppInfo(AppEntry entry) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + entry.pkg));
            startActivity(intent);
        } catch (RuntimeException ignored) {
            android.widget.Toast.makeText(this, UiText.get(R.string.ui_088),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
