package com.ielop.generator.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The simulated workforce: requesters and approvers with approval limits
 * (spec sections 5.4 and 5.6). Names come only from a fixed list of fictional
 * names (GEN-007); e-mail addresses use {@code acme.example}.
 */
public final class UserDirectory {

    public static final List<String> FIRST_NAMES = List.of(
            "Avery", "Blake", "Casey", "Dana", "Ellis", "Finley", "Gray", "Harper", "Indigo", "Jules",
            "Kai", "Logan", "Morgan", "Noel", "Oakley", "Parker", "Quinn", "Reese", "Sage", "Taylor",
            "Umber", "Vale", "Wren", "Yael", "Zion");
    public static final List<String> LAST_NAMES = List.of(
            "Ashford", "Birchwood", "Coldwater", "Dunmore", "Eastbrook", "Fernhill", "Glenrock", "Hollowell",
            "Ironvale", "Juniper", "Kestrel", "Larkspur", "Millbrook", "Northcote", "Oakhurst", "Pinecrest",
            "Quarry", "Ravenscroft", "Stonebridge", "Thornfield", "Underhill", "Westmere");
    static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (X11; Linux x86_64)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5)");
    static final List<BigDecimal> LIMITS = List.of(
            new BigDecimal("5000.00"), new BigDecimal("10000.00"), new BigDecimal("25000.00"), new BigDecimal("50000.00"));

    private final List<User> all;
    private final List<User> requesters;
    private final List<User> approvers;

    private UserDirectory(List<User> all) {
        this.all = List.copyOf(all);
        this.requesters = all.stream().filter(User::requester).toList();
        this.approvers = all.stream().filter(User::canApprove).toList();
    }

    public static UserDirectory create(int count, SplittableRandom rnd) {
        if (count < 4) {
            throw new IllegalArgumentException("--users must be at least 4");
        }
        Set<Integer> numbers = new HashSet<>();
        List<User> users = new ArrayList<>();
        int approverCount = Math.max(2, count / 4);
        for (int i = 0; i < count; i++) {
            int n;
            do {
                n = 1000 + rnd.nextInt(9000);
            } while (!numbers.add(n));
            String first = FIRST_NAMES.get(rnd.nextInt(FIRST_NAMES.size()));
            String last = LAST_NAMES.get(rnd.nextInt(LAST_NAMES.size()));
            String email = (first + "." + last + "." + n).toLowerCase(Locale.ROOT) + "@acme.example";
            String ip = "10." + (20 + rnd.nextInt(20)) + "." + rnd.nextInt(256) + "." + (1 + rnd.nextInt(254));
            String ua = USER_AGENTS.get(rnd.nextInt(USER_AGENTS.size()));
            boolean approver = i < approverCount;
            // The first approver always holds the highest limit, so every amount has an approver.
            BigDecimal limit = !approver ? null : (i == 0 ? LIMITS.get(LIMITS.size() - 1) : LIMITS.get(rnd.nextInt(LIMITS.size())));
            boolean requester = !approver || rnd.nextInt(3) == 0;
            users.add(new User("usr-" + n, first + " " + last, email, ip, ua, requester, limit));
        }
        users.sort(Comparator.comparing(User::id));
        return new UserDirectory(users);
    }

    public List<User> all() {
        return all;
    }

    public User randomUser(SplittableRandom rnd) {
        return all.get(rnd.nextInt(all.size()));
    }

    public User randomRequester(SplittableRandom rnd) {
        return requesters.get(rnd.nextInt(requesters.size()));
    }

    /** An approver other than {@code excluded} whose limit covers {@code amount}. */
    public User approverFor(BigDecimal amount, User excluded, SplittableRandom rnd) {
        List<User> eligible = approvers.stream()
                .filter(u -> !u.id().equals(excluded.id()) && u.approvalLimit().compareTo(amount) >= 0)
                .toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException("No approver can approve " + amount);
        }
        return eligible.get(rnd.nextInt(eligible.size()));
    }

    /** LIMIT_BREACH: an approver (not {@code excluded}) whose limit is below {@code amount}. */
    public User approverBelow(BigDecimal amount, User excluded, SplittableRandom rnd) {
        List<User> eligible = approvers.stream()
                .filter(u -> !u.id().equals(excluded.id()) && u.approvalLimit().compareTo(amount) < 0)
                .toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException("No approver has a limit below " + amount);
        }
        return eligible.get(rnd.nextInt(eligible.size()));
    }

    /** SELF_APPROVAL: a requester who may also approve this amount. */
    public User requesterWhoCanApprove(BigDecimal amount, SplittableRandom rnd) {
        List<User> eligible = all.stream()
                .filter(u -> u.requester() && u.canApprove() && u.approvalLimit().compareTo(amount) >= 0)
                .toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException("No requester can approve " + amount);
        }
        return eligible.get(rnd.nextInt(eligible.size()));
    }

    /** AUTHZ_PROBING: a user without the approve permission. */
    public User randomRequesterWithoutApproval(SplittableRandom rnd) {
        List<User> eligible = requesters.stream().filter(u -> !u.canApprove()).toList();
        return eligible.get(rnd.nextInt(eligible.size()));
    }

    /** Any approver other than the given users. */
    public User otherApprover(SplittableRandom rnd, User... excluded) {
        List<User> eligible = approvers.stream()
                .filter(u -> java.util.Arrays.stream(excluded).noneMatch(x -> x.id().equals(u.id())))
                .toList();
        return eligible.isEmpty() ? excluded[excluded.length - 1] : eligible.get(rnd.nextInt(eligible.size()));
    }
}
