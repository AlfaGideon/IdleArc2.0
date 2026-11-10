package com.idlearc.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Легковесный встроенный ContentProvider (без внешних зависимостей AndroidX) для передачи скачанного
 * по воздуху (OTA) APK системному установщику пакетов Android 7.0–15+.
 */
public class OtaFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.idlearc.companion.fileprovider";
    private static final String MIME_APK = "application/vnd.android.package-archive";

    public static File getOtaDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "ota");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri getUriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("ota")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveSafeFile(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || uri == null) throw new FileNotFoundException("Invalid context or URI");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Invalid file name");
        }
        File baseDir = getOtaDir(ctx);
        File target = new File(baseDir, name);
        try {
            String baseCanon = baseDir.getCanonicalPath();
            String targetCanon = target.getCanonicalPath();
            if (!targetCanon.startsWith(baseCanon + File.separator)) {
                throw new FileNotFoundException("Path traversal blocked");
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!target.exists() || !target.isFile()) {
            throw new FileNotFoundException("OTA APK not found: " + name);
        }
        return target;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolveSafeFile(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        String[] finalCols = new String[cols.length];
        Object[] values = new Object[cols.length];
        int idx = 0;
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                finalCols[idx] = OpenableColumns.DISPLAY_NAME;
                values[idx++] = file.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                finalCols[idx] = OpenableColumns.SIZE;
                values[idx++] = file.length();
            }
        }
        String[] trimmedCols = new String[idx];
        Object[] trimmedVals = new Object[idx];
        System.arraycopy(finalCols, 0, trimmedCols, 0, idx);
        System.arraycopy(values, 0, trimmedVals, 0, idx);
        MatrixCursor cursor = new MatrixCursor(trimmedCols, 1);
        cursor.addRow(trimmedVals);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveSafeFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only OTA provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only OTA provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only OTA provider");
    }
}
