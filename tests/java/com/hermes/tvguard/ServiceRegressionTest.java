package com.hermes.tvguard;

/** Run with plain javac/java; no Android SDK or testing dependency required. */
public final class ServiceRegressionTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String own = "com.hermes.tvguard/com.hermes.tvguard.GuardService";
        String shortOwn = "com.hermes.tvguard/.GuardService";
        String others = "reader.app/.Service:keyboard.app/keyboard.app.Reader";
        check(GuardState.containsComponent(shortOwn, own), "short component membership");
        check(GuardState.containsComponent(others + ":" + own, own), "colon membership");
        check(!GuardState.containsComponent(own + "Extra", own), "no substring membership");
        check(!GuardState.containsComponent("prefix." + own, own), "no prefix membership");
        check(!GuardState.containsComponent(null, own), "null list");
        check(!GuardState.containsComponent("", own), "empty list");
        check(GuardState.withComponent(others, own, true).equals(others + ":" + own),
                "enable appends preserving others");
        check(GuardState.withComponent(others + ":" + shortOwn, own, true)
                .equals(others + ":" + shortOwn), "enable preserves short own alias");
        check(GuardState.withComponent(others + ":" + shortOwn + ":" + own, own, false)
                .equals(others), "disable removes both aliases only");
        check(GuardState.withComponent(others, own, false).equals(others), "absent removal no-op");
        check(GuardState.withComponent(shortOwn, own, false).isEmpty(), "last service removal");
        check(GuardState.withComponent(null, own, true).equals(own), "empty enable");
        check(GuardState.withComponent(own + "Extra:" + own, own, false)
                .equals(own + "Extra"), "remove does not remove lookalike");
        check(GuardState.withComponent(shortOwn + ":" + own, own, true).equals(shortOwn),
                "enable deduplicates own aliases");
        check(GuardState.sameBoot("a", "a", 100, 500), "same boot");
        check(!GuardState.sameBoot("a", "b", 100, 500), "boot ID changed");
        check(!GuardState.sameBoot("", "", 100, 500), "unknown boot is untrusted");
        check(!GuardState.sameBoot(null, "a", 100, 500), "missing boot is untrusted");
        check(!GuardState.sameBoot("a", "a", 500, 100), "elapsed clock reset is untrusted");
        check(GuardState.remainingLock(1000, 900, true, 400) == 600, "same-boot monotonic lock");
        check(GuardState.remainingLock(1000, 900, true, 1400) == 0, "expired lock");
        check(GuardState.remainingLock(1000, 900, false, 40000) == 900,
                "reboot does not compare stale elapsed deadline");
        check(GuardState.remainingLock(1000, 900, false, 1) == 900,
                "reboot restore bounded by saved remaining duration");
        check(GuardState.remainingLock(1000, -1, false, 1) == 0, "negative remaining clamped");
        check(GuardState.restoredWatched(20000, true, 100, true, 600) == 20500,
                "same boot active segment preserved conservatively");
        check(GuardState.restoredWatched(20000, false, 100, true, 600) == 20000,
                "parked state excludes process downtime");
        check(GuardState.restoredWatched(20000, true, 100, false, 600) == 20000,
                "reboot preserves accumulated but excludes stale elapsed segment");
        check(GuardState.restoredWatched(-1, false, 100, true, 600) == 0,
                "negative accumulated clamped");
        check(GuardState.restoredWatched(Long.MAX_VALUE, true, 100, true, 600)
                == Long.MAX_VALUE, "accumulated overflow saturates");
        System.out.println("PASS: " + checks + " service-state/settings regression checks");
    }
}
