package pl.ftpplayer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import android.database.Cursor;

/** Downloads use original remote files, never transient localhost preview URLs. */
public final class NativeDownloads {
    private NativeDownloads() {}

    public static long enqueue(Activity activity, String url, String filename) {
        Uri uri = Uri.parse(url);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || "127.0.0.1".equals(uri.getHost())
                || "localhost".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Brak zdalnego adresu oryginalnego pliku");
        }
        String safe = filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safe.equals(".") || safe.equals("..") || safe.isEmpty()) safe = "plik";
        // Unikalna nazwa zapobiega nadpisaniu wcześniejszego pobrania.
        String destination = "ChomikExplorer/" + System.currentTimeMillis() + "-" + safe;
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(safe).setDescription("FTP Player")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        boolean publicFolder = Build.VERSION.SDK_INT >= 29
                || Build.VERSION.SDK_INT < 23
                || activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
        if (publicFolder) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destination);
        else request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, destination);
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        return manager.enqueue(request);
    }

    public static String list(Activity activity) throws Exception {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        JSONArray items = new JSONArray();
        try (Cursor cursor = manager.query(new DownloadManager.Query())) {
            while (cursor != null && cursor.moveToNext() && items.length() < 100) {
                JSONObject item = new JSONObject();
                for (String column : new String[]{DownloadManager.COLUMN_ID, DownloadManager.COLUMN_TITLE,
                        DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES, DownloadManager.COLUMN_REASON}) {
                    int index = cursor.getColumnIndexOrThrow(column);
                    item.put(column, DownloadManager.COLUMN_TITLE.equals(column) ? cursor.getString(index) : cursor.getLong(index));
                }
                items.put(item);
            }
        }
        return items.toString();
    }

    public static void cancel(Activity activity, long id) {
        ((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).remove(id);
    }
}
