package com.zhb.petscreen;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal OpenAI-compatible multipart client for /audio/transcriptions. */
final class SpeechApiClient {
    static String transcribe(File wav, String base, String key, String model) throws Exception {
        if (wav == null || wav.length() < 44 || wav.length() > 2 * 1024 * 1024)
            throw new IOException("invalid_audio");
        String boundary = "pet-" + UUID.randomUUID().toString();
        HttpURLConnection connection = (HttpURLConnection) new URL(
            base.replaceAll("/+$", "") + "/audio/transcriptions").openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(90000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                field(out, boundary, "model", model);
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (FileInputStream in = new FileInputStream(wav)) {
                    byte[] buffer = new byte[4096];
                    for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readLimited(stream, 65536);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            String text = new JSONObject(response).optString("text", "").trim();
            if (text.isEmpty()) throw new IOException("empty_transcript");
            return text.length() > 500 ? text.substring(0, 500) : text;
        } finally {
            connection.disconnect();
        }
    }

    static void field(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    static String readLimited(InputStream in, int limit) throws IOException {
        if (in == null) return "";
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = source.read(buffer)) != -1;) {
                if (out.size() + count > limit) throw new IOException("response_limit");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
}
