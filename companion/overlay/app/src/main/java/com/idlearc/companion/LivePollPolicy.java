package com.idlearc.companion;

/** Backoff after failures; no assumptions about server rate limits. */
public final class LivePollPolicy {
    private LivePollPolicy() {}
    public static long failureDelayMs(int failures, boolean rateLimited) {
        if (rateLimited) return 60_000L;
        int exponent = Math.max(0, Math.min(4, failures - 1));
        return Math.min(60_000L, 5_000L * (1L << exponent));
    }
}
