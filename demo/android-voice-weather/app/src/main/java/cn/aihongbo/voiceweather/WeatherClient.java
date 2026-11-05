package cn.aihongbo.voiceweather;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class WeatherClient {
    static final String[] PREFERRED_CITIES = {"北京", "上海", "广州", "深圳"};
    static String query(CityCatalog.City city, int day) throws Exception {
        String endpoint = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Asia%%2FShanghai&forecast_days=2", city.latitude, city.longitude);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("天气服务 HTTP " + connection.getResponseCode());
            String body;
            try (java.io.InputStream input = connection.getInputStream()) { java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count); body = bytes.toString(StandardCharsets.UTF_8.name()); }
            JSONObject daily = new JSONObject(body).getJSONObject("daily");
            double low = daily.getJSONArray("temperature_2m_min").getDouble(day);
            double high = daily.getJSONArray("temperature_2m_max").getDouble(day);
            int rain = daily.getJSONArray("precipitation_probability_max").getInt(day);
            int code = daily.getJSONArray("weather_code").getInt(day);
            return String.format(Locale.CHINA, "%s%s，%s，最低%.0f度，最高%.0f度，降水概率百分之%d。", city.name, day == 1 ? "明天" : "今天", condition(code), low, high, rain);
        } finally { connection.disconnect(); }
    }
    private static String condition(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云或阴天";
        if (code == 45 || code == 48) return "有雾";
        if (code >= 95) return "有雷雨";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "有雪";
        if (code >= 51 && code <= 82) return "有雨";
        return "天气情况请查看预报";
    }
}
