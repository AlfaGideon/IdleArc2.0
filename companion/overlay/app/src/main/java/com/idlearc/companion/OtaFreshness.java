package com.idlearc.companion;

import java.util.Locale;

/** A stale mirror cannot prove that the installed version is current. */
final class OtaFreshness {
    private OtaFreshness() {}
    static boolean cannotConfirm(boolean releaseSeen, int installedCode, int bestCode) {
        return !releaseSeen && bestCode <= installedCode;
    }
    static String sha256FromAssetDigest(String digest) {
        if (digest == null) return "";
        String s = digest.trim().toLowerCase(Locale.ROOT);
        return s.matches("sha256:[0-9a-f]{64}") ? s.substring(7) : "";
    }
}
