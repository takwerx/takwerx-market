package com.atakmap.android.takwerxmarket;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads the signing certificates of an installed package and of an APK file.
 *
 * Android refuses to replace an app with one signed by a different key, and the
 * message it shows for that is "App not installed as package appears to be
 * invalid" — which sends the operator looking for a corrupt download that does
 * not exist. Measured 2026-09-01: the market's PLSS Grid is signed
 * "TAK Product Center ATAK Untrusted Plugin Release" and the dev build on the
 * test phone by "WinTec Arrowmaker", the SDK's shared keystore.
 *
 * Comparing the two before launching the installer lets the market say what is
 * actually wrong. Comparing certificates directly, rather than checking against
 * a hardcoded list of TAK's own certificates, means nothing here has to be
 * maintained when TAK rotates a key.
 */
public final class Signers {

    private static final String TAG = "TakwerxMarket.Signers";

    /**
     * SHA-256 of the two TAK Product Center certificates that sign ATAK plugins.
     *
     * Lifted from ATAK's own ACCEPTABLE_KEY_LIST in AtakPluginRegistry — these are
     * its trust anchors, not ours. Cross-checked 2026-09-02: the "Untrusted Plugin
     * Release" digest below is byte-for-byte what apksigner reports for the
     * tak.gov-built Traffic 0.5 that this plugin installed from the catalog.
     *
     * ATAK's list also carries bundle-release certs and an Android debug cert.
     * Both are deliberately excluded. The debug cert especially: every Android
     * developer on earth has that key, so accepting it would mean accepting
     * anything, which is the opposite of the point.
     */
    private static final String[] TAK_PLUGIN_CERTS = {
            // TAK Product Center ATAK Trusted Plugin Release
            "33cdcb132a0ef15c93cdc2f7db9751d88772942070741a62e4d9afabcea49316",
            // TAK Product Center ATAK Untrusted Plugin Release
            "f24a38057275fcecf67be975ab803d12f75dc23581bef69cba9eb03a15bb8c17",
    };

    /**
     * The certificate ATAK-CIV itself is signed with, read 2026-09-02 from the
     * tak.gov 5.7.0.14 and 5.8.0.4 downloads and from the official 5.7.0.5
     * installed on a phone. It is NOT a plugin certificate and must not be
     * accepted for one; it is accepted only for ATAK's own package.
     */
    private static final String ATAK_CIV_CERT =
            "94cf4bac08acfd8a90ddfce88f5772215ae0639833d5dd8bfe3fd6819c8961da";

    /** ATAK's own package, the one this code runs inside of. */
    public static boolean isAtakPackage(Context context, String packageName) {
        return packageName != null && packageName.equals(context.getPackageName());
    }

    /**
     * Per-package pin: ATAK's own package must be signed with ATAK's own
     * certificate; everything else must be signed with a TAK plugin-release
     * certificate. Fails closed on an empty set either way.
     */
    public static boolean isTakSigned(Context context, String packageName, Set<String> apkSigners) {
        if (isAtakPackage(context, packageName))
            return allKnown(apkSigners, new String[] { ATAK_CIV_CERT });
        return isTakSigned(apkSigners);
    }

    /** True when the ATAK this runs inside was itself signed by tak.gov. */
    public static boolean hostIsTakSigned(Context context) {
        return allKnown(ofInstalled(context, context.getPackageName()),
                new String[] { ATAK_CIV_CERT });
    }

    private static boolean allKnown(Set<String> signers, String[] allowed) {
        if (signers.isEmpty())
            return false;
        for (String s : signers) {
            boolean known = false;
            for (String ok : allowed) {
                if (ok.equalsIgnoreCase(s)) {
                    known = true;
                    break;
                }
            }
            if (!known)
                return false;
        }
        return true;
    }

    private Signers() {
    }

    /**
     * True when every certificate on this APK is a TAK plugin-release certificate.
     *
     * The hash check only proves the bytes match the catalog — worthless if the
     * catalog itself is hostile. This is the check that says the binary came from
     * tak.gov's signing pipeline, and it is the only thing standing between a
     * tampered catalog and an arbitrary APK reaching the installer.
     *
     * An empty set means we could not read the signers, and that is NOT a pass.
     * Unlike {@link #conflict}, which only improves an error message, this one
     * gates an install, so it fails closed.
     */
    public static boolean isTakSigned(Set<String> apkSigners) {
        if (apkSigners.isEmpty())
            return false;
        for (String s : apkSigners) {
            boolean known = false;
            for (String ok : TAK_PLUGIN_CERTS) {
                if (ok.equalsIgnoreCase(s)) {
                    known = true;
                    break;
                }
            }
            if (!known)
                return false;
        }
        return true;
    }

    /** SHA-256 digests of the certificates an installed package is signed with. */
    public static Set<String> ofInstalled(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(packageName, flags());
            return digestsOf(pi);
        } catch (Exception e) {
            Log.d(TAG, "no signers for installed " + packageName + ": " + e.getMessage());
            return new HashSet<>();
        }
    }

    /** SHA-256 digests of the certificates an APK on disk is signed with. */
    public static Set<String> ofApk(Context context, File apk) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), flags());
            return digestsOf(pi);
        } catch (Exception e) {
            Log.d(TAG, "no signers for " + apk.getName() + ": " + e.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * True when the two sets share no certificate AND both are known. An empty
     * set means we could not read them, which is not evidence of a mismatch —
     * never block an install on a reading we did not manage to take.
     */
    public static boolean conflict(Set<String> installed, Set<String> candidate) {
        if (installed.isEmpty() || candidate.isEmpty())
            return false;
        for (String s : candidate) {
            if (installed.contains(s))
                return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static int flags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> digestsOf(PackageInfo pi) {
        Set<String> out = new HashSet<>();
        if (pi == null)
            return out;

        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null) {
            sigs = pi.signingInfo.hasMultipleSigners()
                    ? pi.signingInfo.getApkContentsSigners()
                    : pi.signingInfo.getSigningCertificateHistory();
        }
        if (sigs == null)
            sigs = pi.signatures;
        if (sigs == null)
            return out;

        for (Signature s : sigs) {
            String d = sha256(s.toByteArray());
            if (d != null)
                out.add(d);
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            return MarketHttp.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }
}
