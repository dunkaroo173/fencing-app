package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONObject;

final class NativeVideoMetadata {
    private NativeVideoMetadata() {}

    static JSONObject forUri(Context context, Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            JSONObject json = new JSONObject();
            json.put("displayName", displayName(context, uri));
            json.put("durationMs", longMeta(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION));
            json.put("width", intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            json.put("height", intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return json;
        } finally {
            retriever.release();
        }
    }

    private static String displayName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            } catch (Exception ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "video" : last;
    }

    private static int intMeta(MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long longMeta(MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
