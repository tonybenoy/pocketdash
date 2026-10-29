package com.tonybenoy.dashboard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Open-Meteo forecast for a location found from the phone's public IP (no API keys). */
public class Weather {

    public static class Hour { public String label; public double temp; public int code; public boolean day; }

    public static class Data {
        public double temp, hi, lo, wind;
        public int code;
        public boolean day;
        public String city = "";
        public final List<Hour> hours = new ArrayList<>();
    }

    private static double lat = Double.NaN, lon;
    private static String city = "";

    public static Data fetch() throws Exception {
        if (Double.isNaN(lat)) {
            JSONObject g = new JSONObject(get("https://ipwho.is/"));
            if (!g.optBoolean("success", true)) throw new Exception("geolocation failed");
            lat = g.getDouble("latitude");
            lon = g.getDouble("longitude");
            city = g.optString("city", "");
        }
        JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
            + "&longitude=" + lon
            + "&current=temperature_2m,weather_code,wind_speed_10m,is_day"
            + "&hourly=temperature_2m,weather_code,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min"
            + "&forecast_days=2&timezone=auto"));
        Data d = new Data();
        d.city = city;
        JSONObject cur = j.getJSONObject("current");
        d.temp = cur.getDouble("temperature_2m");
        d.code = cur.getInt("weather_code");
        d.wind = cur.getDouble("wind_speed_10m");
        d.day = cur.getInt("is_day") == 1;
        JSONObject daily = j.getJSONObject("daily");
        d.hi = daily.getJSONArray("temperature_2m_max").getDouble(0);
        d.lo = daily.getJSONArray("temperature_2m_min").getDouble(0);

        JSONObject h = j.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time");
        String nowHour = cur.getString("time").substring(0, 13); // yyyy-MM-ddTHH
        int start = 0;
        for (int i = 0; i < times.length(); i++) {
            if (times.getString(i).compareTo(nowHour) > 0) { start = i; break; }
        }
        for (int i = start; i < Math.min(start + 5, times.length()); i++) {
            Hour x = new Hour();
            x.label = times.getString(i).substring(11, 16);
            x.temp = h.getJSONArray("temperature_2m").getDouble(i);
            x.code = h.getJSONArray("weather_code").getInt(i);
            x.day = h.getJSONArray("is_day").getInt(i) == 1;
            d.hours.add(x);
        }
        return d;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    public static String emoji(int c, boolean day) {
        if (c == 0) return day ? "☀️" : "🌙";
        if (c == 1) return day ? "🌤️" : "🌙";
        if (c == 2) return "⛅";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫️";
        if (c >= 51 && c <= 57) return "🌦️";
        if (c >= 61 && c <= 67) return "🌧️";
        if (c >= 71 && c <= 77) return "🌨️";
        if (c >= 80 && c <= 82) return "🌧️";
        if (c == 85 || c == 86) return "🌨️";
        if (c >= 95) return "⛈️";
        return "🌡️";
    }

    public static String text(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "Unknown";
    }
}
