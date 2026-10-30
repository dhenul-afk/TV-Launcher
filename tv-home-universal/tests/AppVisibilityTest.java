package io.github.tvhome.universal;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Host checks for the Home Screen app visibility rules (hide/show, protection, show all). */
public final class AppVisibilityTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    public static void main(String[] args) {
        String self = "io.github.tvhome.universal";
        String home = "com.google.android.apps.tv.launcherx";

        // Encoding survives restarts and ignores blank or padded lines.
        check(AppVisibility.decode("").isEmpty());
        check(AppVisibility.decode(null).isEmpty());
        check(AppVisibility.decode("a\nb").equals(setOf("a", "b")));
        check(AppVisibility.decode(" a \n\n b \n").equals(setOf("a", "b")));
        check(AppVisibility.decode(AppVisibility.encode(setOf("x", "y"))).equals(setOf("x", "y")));
        check(AppVisibility.encode(setOf("a", "b")).equals("a\nb"));

        // Configuration defaults and user choices merge; protected packages never hide.
        Set<String> configured = setOf("com.hidden.by.config");
        Set<String> user = setOf("com.example.kids", home, "", self);
        Set<String> effective = AppVisibility.hiddenApps(configured, user, self, home);
        check(effective.contains("com.hidden.by.config"));
        check(effective.contains("com.example.kids"));
        check(effective.contains(self));          // the launcher never lists itself
        check(!effective.contains(home));         // system Home entry is protected
        check(AppVisibility.hiddenApps(configured, null, self, home).contains("com.hidden.by.config"));

        // A protected package cannot be hidden, even if something asks for it.
        Set<String> hidden = AppVisibility.hiddenApps(configured, null, self, home);
        check(AppVisibility.isProtected(self, home, self));
        check(AppVisibility.isProtected(self, home, home));
        check(AppVisibility.isProtected(self, home, null));
        check(AppVisibility.isProtected(self, home, ""));
        check(!AppVisibility.isProtected(self, home, "com.example.tv"));
        check(AppVisibility.toggle(hidden, self, false, self, home).equals(hidden));
        check(AppVisibility.toggle(hidden, home, false, self, home).equals(hidden));

        // Show / hide round trip keeps the caller's set untouched (immutability).
        Set<String> before = AppVisibility.toggle(setOf("com.a"), "com.b", false, self, home);
        check(before.contains("com.a") && before.contains("com.b"));
        check(setOf("com.a").size() == 1);
        Set<String> shown = AppVisibility.toggle(before, "com.b", true, self, home);
        check(shown.equals(setOf("com.a")));
        check(before.contains("com.b"));

        // "Tampilkan Semua Aplikasi" clears user choices; configuration defaults are separate.
        check(AppVisibility.showAll(setOf("com.a", "com.b")).isEmpty());
        check(AppVisibility.showAll(null).isEmpty());
        Set<String> afterShowAll = AppVisibility.hiddenApps(configured,
                AppVisibility.showAll(user), self, home);
        check(!afterShowAll.contains("com.example.kids"));
        check(afterShowAll.contains("com.hidden.by.config"));

        // The launcher rebuilds only when the effective set really changed.
        Set<String> same1 = setOf("com.a", "com.b");
        Set<String> same2 = setOf("com.b", "com.a");
        check(!AppVisibility.changed(same1, same2));
        check(AppVisibility.changed(same1, setOf("com.a")));   // a removed package is a change
        check(AppVisibility.changed(same1, setOf("com.a", "com.c")));
        check(AppVisibility.changed(null, same1));

        System.out.println("PASS: app visibility rules, protection of system packages and show-all reset.");
    }

    private AppVisibilityTest() { }
}
