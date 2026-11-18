package com.hermes.tvguard;

/** Run with javac/java; no Android runtime, dependencies, or assertion flags required. */
public final class PinRegressionTest {
    private static int checks;

    private static void equal(long expected, long actual, String label) {
        checks++;
        if (expected != actual) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        long start = 10_000L;
        long until = start + PinRetryPolicy.COOLDOWN_MS;
        long epoch = 1_700_000_000_000L;
        equal(5, PinRetryPolicy.MAX_FAILS, "five failed attempts");
        equal(300_000L, PinRetryPolicy.COOLDOWN_MS, "five-minute cooldown");
        for (int fails = 0; fails <= 5; fails++) {
            equal(5 - fails, PinRetryPolicy.attemptsRemaining(fails, 0L), "remaining " + fails);
        }
        equal(0, PinRetryPolicy.attemptsRemaining(99, 0L), "attempts never negative");
        equal(0, PinRetryPolicy.attemptsRemaining(0, 1L), "cooldown blocks even correct attempt");
        equal(0L, PinRetryPolicy.remainingMs(0L, 0L, 1, epoch, start, 2, epoch), "no cooldown");
        equal(300_000L, PinRetryPolicy.remainingMs(until, start, 7, epoch, start, 7, epoch), "starts full");
        equal(299_000L, PinRetryPolicy.remainingMs(until, start, 7, epoch, start + 1000L, 7, epoch), "counts down");
        equal(1L, PinRetryPolicy.remainingMs(until, start, 7, epoch, until - 1L, 7, epoch), "last ms blocked");
        equal(0L, PinRetryPolicy.remainingMs(until, start, 7, epoch, until, 7, epoch), "exact expiry");
        equal(0L, PinRetryPolicy.remainingMs(until, start, 7, epoch, until + 1L, 7, epoch), "after expiry");
        equal(299_000L, PinRetryPolicy.remainingMs(until, start, 7, epoch,
                start + 1000L, 7, epoch + 86_400_000L), "wall advance ignored with boot count");
        equal(299_000L, PinRetryPolicy.remainingMs(until, start, 7, epoch,
                start + 1000L, 7, epoch - 86_400_000L), "wall rollback ignored with boot count");
        equal(300_000L, PinRetryPolicy.remainingMs(until, start, 7, epoch,
                100L, 8, epoch + 5000L), "reboot restarts cooldown");
        equal(300_000L, PinRetryPolicy.remainingMs(until, start, 7, epoch,
                start - 1L, 7, epoch), "elapsed clock rollback fails closed");
        equal(299_000L, PinRetryPolicy.remainingMs(until, start, -1, epoch,
                start + 1000L, -1, epoch + 1L), "old-platform same-boot fallback");
        equal(300_000L, PinRetryPolicy.remainingMs(until, start, -1, epoch,
                start + 1000L, -1, epoch + 2000L), "fallback clock change fails closed");
        // Reconstruction from persisted values gives the same result: no Activity/static counters.
        long restoredUntil = Long.parseLong(Long.toString(until));
        equal(298_000L, PinRetryPolicy.remainingMs(restoredUntil, start, 7, epoch,
                start + 2000L, 7, epoch), "restored persisted deadline");
        System.out.println("PinRegressionTest: " + checks + " checks passed");
    }
}
