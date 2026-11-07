package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * Weather for the home screen and the screensaver: locate the TV, fetch from Open-Meteo (met.no when
 * it's unreachable), cache the reading in the launcher's preferences. No API keys.
 */
final class Weather {
    static final long INTERVAL_MS = 30 * 60 * 1000;
    static final int DAYS = 5;

    final String city;
    final int code, temp, wind, humidity; // WMO code, °C, m/s, %
    final boolean day;
    final double lat, lon;
    final int[][] days; // {WMO code, max, min} from today on

    private Weather(SharedPreferences p) {
        city = p.getString("w_city", "");
        code = p.getInt("w_code", -1);
        temp = p.getInt("w_temp", 0);
        wind = p.getInt("w_wind", -1);
        humidity = p.getInt("w_hum", -1);
        day = p.getBoolean("w_day", true);
        lat = p.getFloat("w_lat", 0);
        lon = p.getFloat("w_lon", 0);
        String[] d = p.getString("w_days", "").split(";");
        int n = 0;
        int[][] parsed = new int[d.length][];
        for (String s : d) {
            String[] f = s.split(",");
            if (f.length == 3) parsed[n++] = new int[]{Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])};
        }
        days = java.util.Arrays.copyOf(parsed, n);
    }

    /** The launcher activity's own preferences file, so readings saved before the split still count. */
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    /** The last saved reading, or null before the first one. */
    static Weather load(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains("w_temp") ? new Weather(p) : null;
    }

    /** Locate (newest known fix, else ask for one, else the last place), fetch, save; done runs on the main thread. */
    static void refresh(Context c, Runnable done) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        try {
            List<String> providers = lm.getProviders(true);
            Location best = null;
            for (String p : providers) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) { fetch(c, best.getLatitude(), best.getLongitude(), done); return; }
            String p = providers.contains("fused") ? "fused"
                    : providers.contains(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
            if (p != null) {
                lm.getCurrentLocation(p, null, c.getMainExecutor(), l -> {
                    if (l != null) fetch(c, l.getLatitude(), l.getLongitude(), done);
                    else fetchSaved(c, done);
                });
                return;
            }
        } catch (RuntimeException e) { android.util.Log.w("tvhome", "location", e); } // no permission or location off
        fetchSaved(c, done);
    }

    private static void fetchSaved(Context c, Runnable done) {
        SharedPreferences p = prefs(c);
        if (p.contains("w_lat")) fetch(c, p.getFloat("w_lat", 0), p.getFloat("w_lon", 0), done);
    }

    private static void fetch(Context c, double lat, double lon, Runnable done) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            // Open-Meteo first; met.no when it's unreachable (some ISPs block Open-Meteo's servers).
            SharedPreferences.Editor e = null;
            try { e = openMeteo(app, lat, lon); } catch (Exception x) { android.util.Log.w("tvhome", "open-meteo: " + x); }
            if (e == null) {
                try { e = metNo(app, lat, lon); } catch (Exception x) { android.util.Log.w("tvhome", "met.no: " + x); }
            }
            if (e == null) return; // offline: keep showing the last reading
            e.putString("w_city", cityName(app, lat, lon)).putFloat("w_lat", (float) lat).putFloat("w_lon", (float) lon)
                    .putLong("w_at", System.currentTimeMillis()).commit();
            new Handler(Looper.getMainLooper()).post(done);
        }).start();
    }

    private static SharedPreferences.Editor openMeteo(Context c, double lat, double lon) throws Exception {
        JSONObject j = new JSONObject(httpGet(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=%d"
                        + "&wind_speed_unit=ms&timezone=auto", lat, lon, DAYS)));
        JSONObject cur = j.getJSONObject("current");
        JSONObject d = j.getJSONObject("daily");
        JSONArray codes = d.getJSONArray("weather_code"), max = d.getJSONArray("temperature_2m_max"),
                min = d.getJSONArray("temperature_2m_min");
        StringBuilder days = new StringBuilder();
        for (int i = 0; i < codes.length(); i++) {
            days.append(codes.getInt(i)).append(',').append(Math.round(max.getDouble(i))).append(',')
                    .append(Math.round(min.getDouble(i))).append(';');
        }
        return prefs(c).edit()
                .putInt("w_code", cur.getInt("weather_code"))
                .putInt("w_temp", (int) Math.round(cur.getDouble("temperature_2m")))
                .putBoolean("w_day", cur.optInt("is_day", 1) == 1)
                .putInt("w_hum", cur.optInt("relative_humidity_2m", -1))
                .putInt("w_wind", (int) Math.round(cur.optDouble("wind_speed_10m", -1)))
                .putString("w_days", days.toString());
    }

    /** Norwegian Meteorological Institute; its symbol codes are mapped onto WMO codes. */
    private static SharedPreferences.Editor metNo(Context c, double lat, double lon) throws Exception {
        JSONArray ts = new JSONObject(httpGet(String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.3f&lon=%.3f", lat, lon)))
                .getJSONObject("properties").getJSONArray("timeseries");
        JSONObject now = ts.getJSONObject(0).getJSONObject("data");
        JSONObject inst = now.getJSONObject("instant").getJSONObject("details");
        String sym = symbol(now, "next_1_hours", "next_6_hours");

        // Daily outlook: min/max over each local day, the sky from the 6-hour window nearest midday.
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        int[] code = new int[DAYS], noon = new int[DAYS];
        double[] max = new double[DAYS], min = new double[DAYS];
        for (int i = 0; i < DAYS; i++) { code[i] = -1; noon[i] = 99; max[i] = -999; min[i] = 999; }
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            ZonedDateTime t = Instant.parse(e.getString("time")).atZone(zone);
            int d = (int) ChronoUnit.DAYS.between(today, t.toLocalDate());
            if (d < 0 || d >= DAYS) continue;
            JSONObject data = e.getJSONObject("data");
            double air = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            max[d] = Math.max(max[d], air);
            min[d] = Math.min(min[d], air);
            JSONObject six = data.optJSONObject("next_6_hours");
            if (six != null && six.optJSONObject("details") != null) {
                JSONObject det = six.getJSONObject("details");
                if (det.has("air_temperature_max")) max[d] = Math.max(max[d], det.getDouble("air_temperature_max"));
                if (det.has("air_temperature_min")) min[d] = Math.min(min[d], det.getDouble("air_temperature_min"));
            }
            String s = symbol(data, "next_6_hours", "next_12_hours");
            int dist = Math.abs(t.getHour() + 3 - 13);
            if (s != null && dist < noon[d]) { noon[d] = dist; code[d] = metCode(s); }
        }
        StringBuilder days = new StringBuilder();
        for (int i = 0; i < DAYS && code[i] >= 0; i++) {
            days.append(code[i]).append(',').append(Math.round(max[i])).append(',').append(Math.round(min[i])).append(';');
        }
        return prefs(c).edit()
                .putInt("w_code", sym == null ? -1 : metCode(sym))
                .putInt("w_temp", (int) Math.round(inst.getDouble("air_temperature")))
                .putBoolean("w_day", sym == null || !sym.endsWith("_night"))
                .putInt("w_hum", (int) Math.round(inst.optDouble("relative_humidity", -1)))
                .putInt("w_wind", (int) Math.round(inst.optDouble("wind_speed", -1)))
                .putString("w_days", days.toString());
    }

    private static String symbol(JSONObject data, String... periods) {
        for (String p : periods) {
            JSONObject o = data.optJSONObject(p);
            JSONObject s = o == null ? null : o.optJSONObject("summary");
            if (s != null) return s.optString("symbol_code", null);
        }
        return null;
    }

    private static int metCode(String s) {
        return s.contains("thunder") ? 95
                : s.contains("snow") ? 71
                : s.contains("sleet") ? 66
                : s.contains("rainshowers") ? 80
                : s.contains("rain") ? 61
                : s.startsWith("fog") ? 45
                : s.startsWith("cloudy") ? 3
                : s.startsWith("partlycloudy") ? 2
                : s.startsWith("fair") ? 1 : 0;
    }

    /** City name in Russian: the system geocoder, else BigDataCloud, else the last known name. */
    private static String cityName(Context c, double lat, double lon) {
        try {
            List<Address> found = new Geocoder(c, new Locale("ru")).getFromLocation(lat, lon, 1);
            if (found != null && !found.isEmpty()) {
                Address a = found.get(0);
                String n = a.getLocality() != null ? a.getLocality()
                        : a.getSubAdminArea() != null ? a.getSubAdminArea() : a.getAdminArea();
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        try {
            JSONObject j = new JSONObject(httpGet(String.format(Locale.US,
                    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.3f&longitude=%.3f&localityLanguage=ru",
                    lat, lon)));
            String n = j.optString("city");
            if (n.isEmpty()) n = j.optString("locality");
            if (!n.isEmpty()) return n;
        } catch (Exception ignored) { }
        return prefs(c).getString("w_city", "");
    }

    private static String httpGet(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "tv-home/1.0 github.com/bapuk01/tv-home"); // met.no requires one
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (Scanner s = new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        } finally {
            c.disconnect();
        }
    }

    /** "+3°", "−15°" (true minus sign). */
    static String degrees(int t) {
        return (t > 0 ? "+" : t < 0 ? "−" : "") + Math.abs(t) + "°";
    }

    /** WMO weather code in words. */
    static String describe(int code) {
        if (code == 0) return "Ясно";
        if (code == 1) return "Малооблачно";
        if (code == 2) return "Переменная облачность";
        if (code == 3) return "Пасмурно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Морось";
        if (code == 66 || code == 67) return "Мокрый снег";
        if (code >= 61 && code <= 65) return "Дождь";
        if (code >= 71 && code <= 77) return "Снег";
        if (code >= 80 && code <= 82) return "Ливень";
        if (code == 85 || code == 86) return "Снегопад";
        if (code >= 95) return "Гроза";
        return "";
    }
}
