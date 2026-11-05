package cn.aihongbo.voiceweather;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small offline gazetteer. Coordinates are populated from GeoNames, never from ASR guesses. */
public final class CityCatalog {
    public static final class City {
        public final String name;
        public final double latitude, longitude;
        City(String name, double latitude, double longitude) {
            this.name = name; this.latitude = latitude; this.longitude = longitude;
        }
    }
    private final Map<String, City> cities;
    public CityCatalog(Reader data) throws IOException {
        Map<String, City> loaded = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(data)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] parts = line.split("\t");
                if (parts.length != 4) throw new IOException("城市表格式错误");
                double lat, lon;
                try { lat = Double.parseDouble(parts[1]); lon = Double.parseDouble(parts[2]); }
                catch (NumberFormatException e) { throw new IOException("城市坐标格式错误", e); }
                if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180 || loaded.containsKey(parts[0])) throw new IOException("城市数据无效");
                loaded.put(parts[0], new City(parts[0], lat, lon));
            }
        }
        if (loaded.isEmpty()) throw new IOException("城市表为空");
        cities = Collections.unmodifiableMap(loaded);
    }
    public City find(String name) { return cities.get(name.endsWith("市") ? name.substring(0,name.length()-1) : name); }
    public List<String> names() { return Collections.unmodifiableList(new ArrayList<>(cities.keySet())); }
    public int size() { return cities.size(); }
}
