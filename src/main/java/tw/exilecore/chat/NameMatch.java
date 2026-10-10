package tw.exilecore.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 用名字找在線的人：先找角色名稱，再找玩家 ID（都不分大小寫、要完全一樣；不猜開頭，私訊才不會送錯人）。
 * 舊的角色可能同名：同名的有好幾個時回傳全部，讓玩家改打玩家 ID。純邏輯，不依賴 Bukkit。
 */
public final class NameMatch {

    private NameMatch() {
    }

    /**
     * 一個在線的人。
     *
     * @param value     找到時回傳的東西（玩家）
     * @param character 角色名稱（還沒選角色是 null）
     * @param account   玩家 ID（Minecraft 帳號名稱）
     */
    public record Candidate<T>(T value, String character, String account) {

        /** 別人看到的名字：角色名稱，沒有角色時是玩家 ID。 */
        public String shown() {
            return character == null || character.isBlank() ? account : character;
        }
    }

    /**
     * 找的結果。
     *
     * @param found     找到的那一個（沒有或同名好幾個時是 null）
     * @param ambiguous 同名的角色（找到或沒有時是空的）
     */
    public record Result<T>(T found, List<Candidate<T>> ambiguous) {

        public Result {
            ambiguous = List.copyOf(ambiguous);
        }

        static <T> Result<T> none() {
            return new Result<>(null, List.of());
        }

        public boolean isEmpty() {
            return found == null && ambiguous.isEmpty();
        }
    }

    /** 角色名稱（唯一的話）→ 玩家 ID → 同名的全部 → 沒有。 */
    public static <T> Result<T> resolve(final Collection<Candidate<T>> candidates, final String query) {
        final String wanted = key(query);
        if (wanted.isEmpty()) {
            return Result.none();
        }
        final List<Candidate<T>> sameCharacter = new ArrayList<>();
        for (final Candidate<T> candidate : candidates) {
            if (candidate.character() != null && key(candidate.character()).equals(wanted)) {
                sameCharacter.add(candidate);
            }
        }
        if (sameCharacter.size() == 1) {
            return new Result<>(sameCharacter.getFirst().value(), List.of());
        }
        // 玩家 ID 不會重複：角色同名時可以改打玩家 ID
        for (final Candidate<T> candidate : candidates) {
            if (candidate.account() != null && key(candidate.account()).equals(wanted)) {
                return new Result<>(candidate.value(), List.of());
            }
        }
        return sameCharacter.isEmpty() ? Result.none() : new Result<>(null, sameCharacter);
    }

    /** 指令補完：開頭符合的名字（別人看到的名字；打玩家 ID 開頭也補玩家 ID），照字母排、不重複。 */
    public static List<String> suggest(final Collection<? extends Candidate<?>> candidates, final String typed) {
        final String prefix = key(typed);
        final TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Candidate<?> candidate : candidates) {
            final String shown = candidate.shown();
            if (shown != null && key(shown).startsWith(prefix)) {
                result.add(shown);
            } else if (!prefix.isEmpty() && candidate.account() != null && key(candidate.account()).startsWith(prefix)) {
                result.add(candidate.account());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 「名字 訊息」切成名字與訊息（名字不會有空白；訊息前後的空白去掉）。
     *
     * @return 兩個元素：名字、訊息（沒有訊息時是空字串）
     */
    public static String[] splitTarget(final String input) {
        final String text = input == null ? "" : input.strip();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return new String[]{text.substring(0, i), text.substring(i).strip()};
            }
        }
        return new String[]{text, ""};
    }

    /** 比對用：全形換半形（NFKC）、去掉前後空白、轉小寫。 */
    static String key(final String text) {
        return text == null ? "" : java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
