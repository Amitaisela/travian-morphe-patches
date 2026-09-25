package com.travianpatch.notifier;

import java.util.Map;

/** Decides whether one game action may be sent now. Pure logic, no Android APIs. */
final class ActionGuard {

    /** Only these request paths may ever be sent. Anything else (and every /premium path) is refused. */
    static final String[] ALLOWED_PREFIXES = {
            "/building/build/", "/building/cancel/", "/units/research", "/units/improve",
            "/farm-list/send",
    };
    static final String TRAIN_MARK = "/train";
    static final long DEDUPE_MS = 60_000L;

    static final class Input {
        String path;
        boolean automated;
        boolean masterOn;
        boolean dryRun;
        long nowMs;
        long nextAttackLandingMs;
        int attackPauseMinutes;
        String dedupeKey;
        Map<String, Long> recentKeys;
    }

    static final class Verdict {
        final boolean allowed;
        final String reason;

        Verdict(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    static Verdict check(Input in) {
        if (in.path == null || in.path.startsWith("/premium")) {
            return new Verdict(false, "gold actions are never sent");
        }
        if (!pathAllowed(in.path)) {
            return new Verdict(false, "not an allowed action: " + in.path);
        }
        if (in.automated && !in.masterOn) {
            return new Verdict(false, "automation is off");
        }
        if (in.automated && in.nextAttackLandingMs > 0
                && in.nextAttackLandingMs - in.nowMs < in.attackPauseMinutes * 60_000L) {
            return new Verdict(false, "paused: an attack lands soon");
        }
        Long last = in.recentKeys == null ? null : in.recentKeys.get(in.dedupeKey);
        if (last != null && in.nowMs - last < DEDUPE_MS) {
            return new Verdict(false, "already sent less than a minute ago");
        }
        return new Verdict(true, in.dryRun ? "dry run" : "ok");
    }

    private static boolean pathAllowed(String path) {
        for (String p : ALLOWED_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return path.startsWith("/building/") && (path.endsWith("/trainUnits") || path.endsWith(TRAIN_MARK));
    }
}
