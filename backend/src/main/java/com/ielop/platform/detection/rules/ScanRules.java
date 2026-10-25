package com.ielop.platform.detection.rules;

import com.ielop.platform.logsearch.LogHit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Window helpers shared by the scan rules. */
final class ScanRules {

    private ScanRules() {
    }

    static final Comparator<LogHit> BY_TIME = Comparator.comparing(LogHit::timestamp).thenComparing(LogHit::id);

    /** {@code ts} floored to a multiple of {@code window} since the epoch (the window start in dedup keys). */
    static Instant floor(Instant ts, Duration window) {
        long w = window.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(ts.toEpochMilli(), w) * w);
    }

    /**
     * Bursts in time-ordered {@code lines}: from each line not yet in a burst, the lines within
     * {@code window}; a burst is kept when {@code accept} holds, and the scan resumes after it.
     */
    static List<List<LogHit>> bursts(List<LogHit> lines, Duration window, Predicate<List<LogHit>> accept) {
        List<List<LogHit>> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Instant end = lines.get(i).timestamp().plus(window);
            int j = i;
            while (j < lines.size() && !lines.get(j).timestamp().isAfter(end)) {
                j++;
            }
            List<LogHit> burst = lines.subList(i, j);
            if (accept.test(burst)) {
                out.add(List.copyOf(burst));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Up to {@code max} document IDs, newest first. */
    static List<String> newest(List<LogHit> lines, int max) {
        List<LogHit> sorted = new ArrayList<>(lines);
        sorted.sort(BY_TIME.reversed());
        return sorted.stream().limit(max).map(LogHit::id).toList();
    }

    /** Up to {@code max} document IDs, oldest first. */
    static List<String> oldest(List<LogHit> lines, int max) {
        return lines.stream().sorted(BY_TIME).limit(max).map(LogHit::id).toList();
    }
}
