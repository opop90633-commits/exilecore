package tw.exilecore.event;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一個帳號這個月的挑戰進度（存在帳號進度 {@code event.progress} 一個值裡，255 字以內）。純資料，不依賴 Bukkit。
 * 格式：{@code 2026-10|kill=120,rare=4|done=kill,boss|t=1}（t=1 = 稱號發過了）。
 * 月份對不上（新的一個月）就從頭。
 */
public final class EventProgress {

    /** 帳號進度的鍵。 */
    public static final String KEY = "event.progress";

    private final String month;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Set<String> done = new LinkedHashSet<>();
    private boolean titleGiven;

    public EventProgress(final String month) {
        this.month = month;
    }

    public String month() {
        return month;
    }

    public int count(final String challengeId) {
        return counts.getOrDefault(challengeId, 0);
    }

    public boolean done(final String challengeId) {
        return done.contains(challengeId);
    }

    public boolean titleGiven() {
        return titleGiven;
    }

    public void markTitleGiven() {
        titleGiven = true;
    }

    /**
     * 一個挑戰加 amount 次（做完的不再加）。
     *
     * @return 這一次剛好做完
     */
    public boolean add(final EventConfig.Challenge challenge, final int amount) {
        if (done.contains(challenge.id()) || amount <= 0) {
            return false;
        }
        final int next = Math.min(challenge.count(), count(challenge.id()) + amount);
        counts.put(challenge.id(), next);
        if (next >= challenge.count()) {
            done.add(challenge.id());
            return true;
        }
        return false;
    }

    /**
     * 這一種（kind）的挑戰都加 amount 次（做完的不再加）。
     *
     * @param finished 這一次剛好做完的挑戰會放進這裡
     * @return 有沒有哪個挑戰的數字變了
     */
    public boolean advance(final List<EventConfig.Challenge> challenges, final EventConfig.Kind kind, final int amount,
                           final List<EventConfig.Challenge> finished) {
        if (amount <= 0) {
            return false;
        }
        boolean changed = false;
        for (final EventConfig.Challenge challenge : challenges) {
            if (challenge.kind() != kind || done.contains(challenge.id())) {
                continue;
            }
            changed = true;
            if (add(challenge, amount)) {
                finished.add(challenge);
            }
        }
        return changed;
    }

    /** 全部做完了沒。 */
    public boolean allDone(final List<EventConfig.Challenge> challenges) {
        for (final EventConfig.Challenge challenge : challenges) {
            if (!done.contains(challenge.id())) {
                return false;
            }
        }
        return !challenges.isEmpty();
    }

    public int doneCount() {
        return done.size();
    }

    public String encode() {
        final StringBuilder text = new StringBuilder(month).append('|');
        boolean first = true;
        for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        text.append("|done=").append(String.join(",", done));
        text.append("|t=").append(titleGiven ? 1 : 0);
        return text.toString();
    }

    /** 讀存檔；月份不同（或讀不懂）就回傳這個月全新的進度。 */
    public static EventProgress decode(final String text, final String month) {
        final EventProgress fresh = new EventProgress(month);
        if (text == null || text.isBlank()) {
            return fresh;
        }
        final String[] parts = text.split("\\|");
        if (parts.length < 2 || !parts[0].trim().equals(month)) {
            return fresh;
        }
        for (final String pair : parts[1].split(",")) {
            final int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                fresh.counts.put(pair.substring(0, equals).trim(), Math.max(0, Integer.parseInt(pair.substring(equals + 1).trim())));
            } catch (final NumberFormatException ignored) {
                // 寫壞的計數：當 0
            }
        }
        for (int i = 2; i < parts.length; i++) {
            final String part = parts[i].trim();
            if (part.startsWith("done=")) {
                for (final String id : part.substring(5).split(",")) {
                    if (!id.isBlank()) {
                        fresh.done.add(id.trim());
                    }
                }
            } else if (part.toLowerCase(Locale.ROOT).startsWith("t=")) {
                fresh.titleGiven = "1".equals(part.substring(2).trim());
            }
        }
        return fresh;
    }
}
