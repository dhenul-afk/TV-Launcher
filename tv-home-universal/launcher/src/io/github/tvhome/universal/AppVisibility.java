package io.github.tvhome.universal;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Home-screen visibility rules. Hiding only affects this launcher's own list: applications stay
 * installed, their permissions and system settings are never touched.
 * Packages listed in res/values/config.xml are always hidden and cannot be shown by the user.
 * This launcher itself and the system Home entry are protected from being hidden.
 */
final class AppVisibility {
    private static final String PREFS = "app_manage";

    private AppVisibility() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> decode(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String pkg : raw.split("\n")) {
                String value = pkg.trim();
                if (!value.isEmpty()) out.add(value);
            }
        }
        return out;
    }

    static String encode(Set<String> packages) {
        StringBuilder out = new StringBuilder();
        for (String pkg : packages) {
            if (out.length() > 0) out.append('\n');
            out.append(pkg);
        }
        return out.toString();
    }

    static Set<String> readHidden(Context context) {
        return decode(preferences(context).getString("hidden", ""));
    }

    static void writeHidden(Context context, Set<String> hidden) {
        preferences(context).edit().putString("hidden", encode(hidden)).apply();
    }

    /** True when this package must always stay visible on the Home Screen. */
    static boolean isProtected(String selfPackage, String systemHomePackage, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return pkg.equals(selfPackage) || pkg.equals(systemHomePackage);
    }

    /** Effective hidden set: configuration defaults plus user choices, minus protected packages. */
    static Set<String> hiddenApps(Set<String> configured, Set<String> userHidden,
                                  String selfPackage, String systemHomePackage) {
        Set<String> out = new LinkedHashSet<>();
        if (configured != null) {
            for (String pkg : configured) {
                if (pkg != null && !pkg.isEmpty()) out.add(pkg);
            }
        }
        if (userHidden != null) {
            for (String pkg : userHidden) {
                if (!isProtected(selfPackage, systemHomePackage, pkg)) out.add(pkg);
            }
        }
        if (selfPackage != null && !selfPackage.isEmpty()) out.add(selfPackage);
        return out;
    }

    /** Returns a new user-hidden set with the package shown or hidden. Protected packages are ignored. */
    static Set<String> toggle(Set<String> userHidden, String pkg, boolean visible,
                              String selfPackage, String systemHomePackage) {
        Set<String> out = new LinkedHashSet<>(userHidden == null ? new LinkedHashSet<String>() : userHidden);
        if (isProtected(selfPackage, systemHomePackage, pkg)) return out;
        if (visible) out.remove(pkg); else out.add(pkg);
        return out;
    }

    /** "Tampilkan Semua Aplikasi": clears every user choice (configuration defaults stay). */
    static Set<String> showAll(Set<String> userHidden) {
        return new LinkedHashSet<>();
    }

    /** True when the effective hidden set changed, so the app catalog must be rebuilt. */
    static boolean changed(Set<String> previous, Set<String> next) {
        return previous == null || next == null || !previous.equals(next);
    }
}
