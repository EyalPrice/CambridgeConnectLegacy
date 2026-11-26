package io.github.eyalprice.ccl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Shares the single report file from the app's cache with other apps (email, chat, Drive). */
public class ReportProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.eyalprice.ccl.report";
    public static final String FILE_NAME = "ccl-diagnostic-report.txt";

    public static File file(android.content.Context ctx) {
        return new File(ctx.getCacheDir(), FILE_NAME);
    }

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{FILE_NAME, file(getContext()).length()});
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
