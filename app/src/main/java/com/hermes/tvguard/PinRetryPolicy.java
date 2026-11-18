package com.hermes.tvguard;

/** Clock-only retry policy; Android storage/clock acquisition lives in PinStore. */
final class PinRetryPolicy {
    static final int MAX_FAILS = 5;
    static final long COOLDOWN_MS = 5 * 60_000L;

    private PinRetryPolicy() { }

    static boolean mustRestartCooldown(long started, int savedBoot, long savedEpoch,
            long now, int boot, long epoch) {
        boolean sameBoot = boot >= 0 && savedBoot >= 0 ? boot == savedBoot
                : Math.abs(epoch - savedEpoch) < 2000L;
        return !sameBoot || now < started;
    }

    static long remainingMs(long until, long started, int savedBoot, long savedEpoch,
            long now, int boot, long epoch) {
        if (until == 0L) return 0L;
        if (mustRestartCooldown(started, savedBoot, savedEpoch, now, boot, epoch)) {
            return COOLDOWN_MS;
        }
        return Math.max(0L, until - now);
    }

    static int attemptsRemaining(int failures, long cooldownRemainingMs) {
        return cooldownRemainingMs > 0L ? 0 : Math.max(0, MAX_FAILS - failures);
    }
}
