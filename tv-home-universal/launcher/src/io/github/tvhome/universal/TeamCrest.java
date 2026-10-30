package io.github.tvhome.universal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small async loader for team crests. URLs are validated by FootballData.validCrest, downloads are
 * capped, failures stay silent (the widget falls back to text only) and results live in a bounded
 * in-memory cache so a TV never accumulates bitmaps.
 */
final class TeamCrest {
    private static final int MAX_CACHE = 48;
    private static final long MAX_BYTES = 120 * 1024;

    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX_CACHE;
        }
    };
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private TeamCrest() { }

    /**
     * Attaches the crest to the team label: logoLeft=false puts it after the home name,
     * logoLeft=true puts it before the away name. Clears the label when the URL is unusable.
     */
    static void attach(TextView label, String address, int sizePx, boolean logoLeft) {
        if (label == null) return;
        if (!FootballData.validCrest(address)) {
            label.setCompoundDrawables(null, null, null, null);
            return;
        }
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(address);
            if (cached != null) {
                apply(label, cached, sizePx, logoLeft);
                return;
            }
        }
        label.setCompoundDrawables(null, null, null, null);
        synchronized (IN_FLIGHT) {
            if (IN_FLIGHT.contains(address)) return;
            IN_FLIGHT.add(address);
        }
        new Thread(() -> {
            Bitmap bitmap = download(address);
            synchronized (CACHE) {
                if (bitmap != null) CACHE.put(address, bitmap);
            }
            synchronized (IN_FLIGHT) { IN_FLIGHT.remove(address); }
            if (bitmap == null) return;
            HANDLER.post(() -> apply(label, bitmap, sizePx, logoLeft));
        }, "team-crest").start();
    }

    private static void apply(TextView label, Bitmap bitmap, int sizePx, boolean logoLeft) {
        BitmapDrawable drawable = new BitmapDrawable(label.getResources(), bitmap);
        drawable.setBounds(0, 0, sizePx, sizePx);
        if (logoLeft) label.setCompoundDrawables(drawable, null, null, null);
        else label.setCompoundDrawables(null, null, drawable, null);
    }

    private static Bitmap download(String address) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > MAX_BYTES) return null;
                    out.write(buffer, 0, read);
                }
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (decoded == null || decoded.getWidth() > 256 || decoded.getHeight() > 256) {
                if (decoded != null) decoded.recycle();
                return null;
            }
            return decoded;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
