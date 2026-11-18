package com.hermes.tvguard;

/** Pure clock policy shared by persistence and deterministic regression tests. */
final class GuardState {
    private GuardState() { }

    /** Only an actual boot identifier permits reuse of an elapsedRealtime deadline. */
    static boolean sameBoot(String savedBoot, String boot, long savedAt, long now) {
        return savedBoot != null && !savedBoot.isEmpty() && savedBoot.equals(boot)
                && now >= savedAt;
    }

    /**
     * Same boot: keep the monotonic deadline, regardless of wall-clock changes.
     * Reboot/unknown boot: restart only the checkpoint's remaining duration; never
     * credit powered-off time or trust wall time. The delay is bounded by that
     * remaining duration, and a pending challenge is never automatically cleared.
     */
    static long remainingLock(long deadline, long savedRemaining, boolean sameBoot, long now) {
        return Math.max(0L, sameBoot ? deadline - now : savedRemaining);
    }

    /** Conservatively count an interrupted active segment on the same boot. */
    static long restoredWatched(long savedWatched, boolean running, long savedAt,
            boolean sameBoot, long now) {
        long gap = running && sameBoot ? Math.max(0L, now - savedAt) : 0L;
        long watched = Math.max(0L, savedWatched);
        return gap > Long.MAX_VALUE - watched ? Long.MAX_VALUE : watched + gap;
    }

    /** Match exactly, accepting Android's package/.ShortClass notation. */
    static boolean isComponent(String entry, String component) {
        return canonical(entry).equals(canonical(component));
    }

    private static String canonical(String entry) {
        if (entry == null) return "";
        int slash = entry.indexOf('/');
        if (slash < 1 || slash == entry.length() - 1) return entry;
        String pkg = entry.substring(0, slash);
        String cls = entry.substring(slash + 1);
        return pkg + "/" + (cls.startsWith(".") ? pkg + cls : cls);
    }

    static boolean containsComponent(String list, String component) {
        if (list == null) return false;
        for (String entry : list.split(":")) {
            if (isComponent(entry, component)) return true;
        }
        return false;
    }

    /** Preserve all other entries verbatim and remove only our own aliases. */
    static String withComponent(String list, String component, boolean on) {
        StringBuilder result = new StringBuilder();
        boolean found = false;
        if (list != null && !list.isEmpty()) {
            for (String entry : list.split(":")) {
                if (entry.isEmpty()) continue;
                if (isComponent(entry, component)) {
                    if (!on || found) continue;
                    found = true;
                }
                if (result.length() > 0) result.append(':');
                result.append(entry);
            }
        }
        if (on && !found) {
            if (result.length() > 0) result.append(':');
            result.append(component);
        }
        return result.toString();
    }
}
