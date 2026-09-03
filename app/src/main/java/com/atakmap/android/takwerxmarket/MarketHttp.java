package com.atakmap.android.takwerxmarket;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** HTTPS GET with hard limits. Nothing here accepts a plain-http URL. */
public final class MarketHttp {

    private static final String TAG = "TakwerxMarket.Http";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** A catalog is a few kilobytes; a megabyte is already a signal something is wrong. */
    public static final int MAX_CATALOG_BYTES = 1024 * 1024;
    /** No takwerx plugin is anywhere near this. It bounds a hostile or broken server. */
    /** ATAK-CIV itself is about 370 MB; plugins are a few. */
    public static final long MAX_APK_BYTES = 600L * 1024 * 1024;

    public interface Progress {
        void onProgress(long bytesRead, long total);
    }

    private MarketHttp() {
    }

    private static HttpURLConnection open(String url) throws IOException {
        if (url == null || !url.toLowerCase(Locale.US).startsWith("https://"))
            throw new IOException("refusing a non-HTTPS URL");

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (!(c instanceof HttpsURLConnection))
            throw new IOException("refusing a non-HTTPS connection");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    /** Fetch a small text resource. Throws on anything but 200. */
    public static String getText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP " + code + " for " + url);

            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_CATALOG_BYTES)
                    throw new IOException("catalog exceeds " + MAX_CATALOG_BYTES + " bytes");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Download to {@code dest}, returning the SHA-256 of what actually arrived.
     * The caller compares it to the catalog before the file is used for anything.
     */
    public static String download(String url, File dest, Progress progress) throws IOException {
        HttpURLConnection c = open(url);
        OutputStream out = null;
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP " + code + " for " + url);

            long declared = c.getContentLength();
            if (declared > MAX_APK_BYTES)
                throw new IOException("download declares " + declared + " bytes");

            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("no SHA-256 available", e);
            }

            InputStream in = c.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_APK_BYTES)
                    throw new IOException("download exceeded " + MAX_APK_BYTES + " bytes");
                sha.update(buf, 0, n);
                out.write(buf, 0, n);
                if (progress != null)
                    progress.onProgress(total, declared);
            }
            out.flush();
            return hex(sha.digest());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    Log.d(TAG, "closing " + dest.getName());
                }
            }
            c.disconnect();
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x10)
                sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
