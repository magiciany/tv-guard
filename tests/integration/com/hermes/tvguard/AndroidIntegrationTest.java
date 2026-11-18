package com.hermes.tvguard;

import android.content.Context;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import java.lang.reflect.Method;

/** Actual service/PIN code with deterministic Android doubles, not a device test. */
public final class AndroidIntegrationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    private static void tick(GuardService s) throws Exception {
        Method method = GuardService.class.getDeclaredMethod("onTick");
        method.setAccessible(true);
        method.invoke(s);
    }
    private static void reset() {
        GuardService.INSTANCE = null;
        AccessibilityService.activeRoot = null;
        GuardService.challengeVisible = false;
        Context.preferences.clear();
        Settings.Secure.values.clear();
        Settings.Secure.writesSucceed = true;
        SystemClock.now = 10_000L;
        Settings.Global.boot = 1;
    }
    private static GuardService service() {
        GuardService service = new GuardService();
        service.onServiceConnected();
        return service;
    }
    private static void target(GuardService s) {
        s.onAccessibilityEvent(new AccessibilityEvent(Prefs.getTarget(s)));
    }
    public static void main(String[] args) throws Exception {
        reset();
        GuardService old = service();
        target(old);
        SystemClock.now += 20_000L;
        GuardService replacement = service();
        old.onDestroy();
        check(GuardService.INSTANCE == replacement, "old destruction preserves replacement reference");
        check(replacement.getAccumulatedSec() == 20, "replacement restores accumulated viewing");
        check(!replacement.isTargetForeground(), "replacement requires fresh foreground observation");
        target(replacement);
        SystemClock.now += 580_000L;
        tick(replacement);
        check(replacement.getState() == GuardService.STATE_CHALLENGE, "accumulated time triggers challenge");
        replacement.onAnsweredWrong("retained answer");
        long deadline = replacement.getLockedUntil();
        check(replacement.getState() == GuardService.STATE_LOCKED, "real wrong answer locks");
        replacement.unlockNow();
        check(replacement.getState() == GuardService.STATE_LOCKED, "unlock cannot end an unexpired lock");
        replacement = service();
        check(replacement.getState() == GuardService.STATE_LOCKED, "replacement retains active lock");
        check(replacement.getLockedUntil() == deadline, "same-boot lock retains monotonic deadline");
        check("retained answer".equals(replacement.getLastReveal()), "replacement retains answer reveal");
        // Abrupt process death: no onDestroy, but persisted critical transitions survive.
        GuardService.INSTANCE = null;
        SystemClock.now += 5_000L;
        replacement = service();
        check(replacement.getState() == GuardService.STATE_LOCKED
                && replacement.getLockedUntil() == deadline, "process recreation restores lock");
        // Reboot must not reinterpret a stale elapsed-realtime deadline or erase the penalty.
        GuardService.INSTANCE = null;
        Settings.Global.boot++;
        SystemClock.now = 1_000L;
        replacement = service();
        check(replacement.getState() == GuardService.STATE_LOCKED, "reboot preserves penalty");
        long remaining = replacement.getLockedUntil() - SystemClock.now;
        check(remaining > 0L && remaining <= 300_000L, "reboot penalty bounded by saved remaining time");
        SystemClock.now = replacement.getLockedUntil();
        tick(replacement);
        check(replacement.getState() == GuardService.STATE_CHALLENGE, "expired lock becomes pending challenge");
        replacement = service();
        check(replacement.getState() == GuardService.STATE_CHALLENGE, "pending challenge survives recreation");
        target(replacement);
        replacement.onAnsweredCorrect();
        check(replacement.getState() == GuardService.STATE_MONITORING
                && replacement.getAccumulatedSec() == 0L, "real correct answer resets viewing");
        SystemClock.now += 5_000L;
        replacement.pauseForSettings();
        long paused = replacement.getAccumulatedSec();
        SystemClock.now += 120_000L;
        tick(replacement);
        check(!replacement.isTargetForeground() && replacement.getAccumulatedSec() == paused,
                "settings pause does not charge time");
        int correct = Prefs.getCorrect(replacement);
        replacement.onAnsweredCorrect();
        check(Prefs.getCorrect(replacement) == correct, "stray answer cannot mutate production statistics");
        replacement.onAccessibilityEvent(new AccessibilityEvent(Prefs.getTarget(replacement),
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        check(!replacement.isTargetForeground(), "background content event cannot resume target");
        target(replacement);
        check(replacement.isTargetForeground(), "new target window event resumes viewing");
        Prefs.setTarget(replacement, "new.video.app");
        tick(replacement);
        check(!replacement.isTargetForeground() && replacement.getAccumulatedSec() == 0L
                && replacement.getState() == GuardService.STATE_MONITORING,
                "new target resets previous target-specific enforcement");
        target(replacement);
        SystemClock.now += 1_000L;
        GuardService.stopGuard(replacement);
        check(!Prefs.getEnabled(replacement) && GuardService.INSTANCE == null, "explicit stop detaches service");
        replacement.onDestroy();
        GuardService.startGuard(replacement);
        replacement = service();
        check(replacement.getAccumulatedSec() == 1L && !replacement.isTargetForeground(),
                "explicit re-enable preserves parked accounting");
        Settings.Secure.values.put("services", "reader.app/.Reader");
        Settings.Secure.values.put("enabled", "1");
        check(GuardService.setAccessibilitySetting(replacement, true), "enable reports write success");
        check(Settings.Secure.values.get("services").contains("reader.app/.Reader"),
                "enable preserves another accessibility service");
        GuardService.setAccessibilitySetting(replacement, false);
        check("reader.app/.Reader".equals(Settings.Secure.values.get("services"))
                && "1".equals(Settings.Secure.values.get("enabled")),
                "disable preserves unrelated service and global accessibility");
        Settings.Secure.writesSucceed = false;
        check(!GuardService.setAccessibilitySetting(replacement, true), "failed settings write reports failure");

        reset();
        GuardService quiet = service();
        target(quiet);
        quiet.forceChallenge();
        quiet.onAnsweredWrong("quiet playback");
        GuardService.INSTANCE = null;
        AccessibilityService.activeRoot = new AccessibilityNodeInfo(Prefs.getTarget(quiet));
        GuardService recovered = service();
        check(recovered.isTargetForeground() && recovered.getState() == GuardService.STATE_LOCKED,
                "quiet target is reconciled on connection without any window event");
        AccessibilityService.activeRoot = new AccessibilityNodeInfo("com.hermes.tvguard");
        recovered.pauseForSettings();
        long parked = recovered.getAccumulatedSec();
        SystemClock.now += 60_000L;
        tick(recovered);
        check(!recovered.isTargetForeground() && recovered.getAccumulatedSec() == parked,
                "active settings root keeps accounting paused");
        AccessibilityService.activeRoot = new AccessibilityNodeInfo(Prefs.getTarget(recovered));
        tick(recovered);
        check(recovered.isTargetForeground(), "quiet target is recovered on a later tick");

        reset();
        Context context = new Context();
        check(PinStore.setPin(context, "1234"), "first PIN setup succeeds");
        check(!PinStore.setPin(context, "0000"), "setup API cannot overwrite an existing PIN");
        check(PinStore.check(context, "1234"), "stored PIN verifies");
        check(!PinStore.changePin(context, "9999", "4321"), "PIN change requires old PIN");
        check(PinStore.check(context, "1234"), "failed change preserves existing PIN");
        for (int i = 0; i < 5; i++) check(!PinStore.check(new Context(), "9999"), "incorrect attempt " + (i+1));
        check(PinStore.attemptsRemaining(new Context()) == 0, "failures persist across Context recreation");
        check(PinStore.cooldownRemainingMs(new Context()) == 300_000L, "fifth failure starts five-minute cooldown");
        check(!PinStore.check(context, "1234"), "correct PIN cannot bypass active cooldown");
        Settings.Global.boot++;
        SystemClock.now = 1_000L;
        check(PinStore.cooldownRemainingMs(context) == 300_000L, "reboot does not bypass cooldown");
        SystemClock.now += 300_001L;
        check(PinStore.check(context, "1234"), "PIN works after cooldown expires");
        check(PinStore.changePin(context, "1234", "4321"), "authorized PIN change succeeds");
        check(PinStore.check(new Context(), "4321"), "new PIN persists");
        System.out.println("PASS: " + checks + " actual service/PIN integration checks (Android doubles)");
    }
}
