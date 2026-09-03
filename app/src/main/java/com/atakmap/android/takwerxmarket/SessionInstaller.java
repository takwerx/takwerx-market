package com.atakmap.android.takwerxmarket;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a verified APK to Android through PackageInstaller rather than an
 * ACTION_VIEW intent.
 *
 * The confirmation prompt is unavoidable either way — no ordinary app installs
 * another silently, and ATAK's own package manager shows the same prompt. What
 * this buys is the OUTCOME. With ACTION_VIEW the plugin hands the file to the
 * installer and never learns what happened: whether the operator confirmed,
 * cancelled, or the install failed. Every stale row we chased came from that
 * blindness, patched by listening for package broadcasts that do not reliably
 * arrive.
 *
 * A session reports back. The row can say "Installing…", then say what actually
 * happened, and refresh itself with no broadcast involved.
 *
 * It also usually removes the installer's separate completion screen, because
 * the result comes to us instead of being shown. That varies by OEM, so it is a
 * likely improvement rather than a promised one.
 */
public final class SessionInstaller {

    private static final String TAG = "TakwerxMarket.Session";
    private static final String ACTION = "com.atakmap.android.takwerxmarket.INSTALL_RESULT";

    public interface Callback {
        /** @param message null on success, else something an operator can act on. */
        void onFinished(boolean success, String message);
    }

    private SessionInstaller() {
    }

    /**
     * Blocking up to the point the prompt is raised; the result arrives on the
     * callback later. Safe to call off the main thread.
     */
    public static void install(final Context hostContext, File apk, final String label,
            final Callback callback) {

        final PackageInstaller installer = hostContext.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId;

        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apk.length());

            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(apk);
                out = session.openWrite("apk", 0, apk.length());
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                closeQuietly(out);
                closeQuietly(in);
            }

            // Registered before commit so a fast result cannot be missed.
            registerResultReceiver(hostContext, label, callback);

            Intent intent = new Intent(ACTION).setPackage(hostContext.getPackageName());
            // MUTABLE because the system fills in the status extras. On S and up
            // an immutable PendingIntent here is rejected outright.
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(hostContext, sessionId, intent, flags);

            session.commit(pi.getIntentSender());
            Log.d(TAG, "committed session " + sessionId + " for " + label);

        } catch (Exception e) {
            if (session != null)
                session.abandon();
            Log.e(TAG, "session install failed for " + label, e);
            callback.onFinished(false, label + " could not be handed to Android: "
                    + e.getMessage());
        } finally {
            closeQuietly(session);
        }
    }

    private static void registerResultReceiver(final Context hostContext,
            final String label, final Callback callback) {

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);

                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Android wants the operator to confirm. This is the prompt
                    // that cannot be skipped; we only get to raise it.
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        hostContext.startActivity(confirm);
                    }
                    return;                       // not finished yet, stay registered
                }

                try {
                    hostContext.unregisterReceiver(this);
                } catch (Exception ignored) {
                    Log.d(TAG, "receiver already gone");
                }

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    callback.onFinished(true, null);
                } else {
                    String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    callback.onFinished(false, describe(status, label, why));
                }
            }
        };

        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hostContext.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            hostContext.registerReceiver(receiver, f);
    }

    /** Android's status codes are numbers; an operator needs a sentence. */
    private static String describe(int status, String label, String raw) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return label + " was not installed — cancelled.";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return label + " conflicts with the copy already on this device."
                        + " Uninstall it first, then install from here.";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return label + " was not installed — not enough storage.";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return label + " is not compatible with this device.";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return label + " was rejected by Android as invalid.";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return label + " was blocked by the device policy.";
            default:
                return label + " was not installed"
                        + (raw == null || raw.length() == 0 ? "." : ": " + raw);
        }
    }

    private static void closeQuietly(Object c) {
        if (c == null)
            return;
        try {
            if (c instanceof java.io.Closeable)
                ((java.io.Closeable) c).close();
            else if (c instanceof PackageInstaller.Session)
                ((PackageInstaller.Session) c).close();
        } catch (Exception ignored) {
            Log.d(TAG, "close failed");
        }
    }
}
