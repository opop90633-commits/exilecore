package tw.exilecore.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/**
 * 練等的速度：升一級要殺幾隻同等級的普通怪（character.yml 的升級經驗 ÷ monsters.yml 的怪物經驗）。純函式，不依賴 Bukkit。
 * <p>
 * 兩個檔案是一起調的：其中一個是舊版（被改過的檔案不會自動更新，新版放在 .new）時，練等會快幾十倍或慢幾十倍，
 * 所以啟動與 /ec reload 時檢查一次、在主控台寫出來。
 */
public final class LevelPace {

    /** 檢查的等級（劇情打完的等級）。 */
    public static final int CHECK_LEVEL = 60;
    /** 少於這麼多隻就提醒（出貨的設定 60 級約 870 隻）。 */
    public static final double MIN_KILLS = 20;
    /** 多於這麼多隻就提醒。 */
    public static final double MAX_KILLS = 10_000;

    private LevelPace() {
    }

    /**
     * 從 level 升到下一級要殺幾隻同等級的普通怪（沒有等級差懲罰、不算稀有度）。
     * 滿級回傳 0；怪物不給經驗回傳正無限大。
     */
    public static double sameLevelKills(final CharacterConfig character, final IntToDoubleFunction monsterExperience, final int level) {
        final long need = character.experienceToNext(level);
        if (need <= 0) {
            return 0;
        }
        final double each = monsterExperience.applyAsDouble(level);
        return each <= 0 ? Double.POSITIVE_INFINITY : need / each;
    }

    /** 檢查的等級：60 級，等級上限比較低時用上限的前一級。 */
    public static int checkLevel(final CharacterConfig character) {
        return Math.max(1, Math.min(CHECK_LEVEL, character.levelCap() - 1));
    }

    /** 兩個檔案搭不搭；沒問題回傳空的。 */
    public static List<String> problems(final CharacterConfig character, final IntToDoubleFunction monsterExperience) {
        final List<String> problems = new ArrayList<>();
        final int level = checkLevel(character);
        final double kills = sameLevelKills(character, monsterExperience, level);
        if (Double.isInfinite(kills)) {
            problems.add("monsters.yml：" + level + " 級的怪物不給經驗（experience 表或 experience_multiplier 是 0），打怪不會升級");
        } else if (kills > 0 && kills < MIN_KILLS) {
            problems.add(String.format(Locale.ROOT, "練等速度不對：%d 級升一級只要殺 %.1f 隻同等級的普通怪（正常是幾百隻）。"
                    + "character.yml 的經驗表可能還是舊版（看有沒有 character.yml.new，/ec data status），或 monsters.yml 的怪物經驗太高",
                    level, kills));
        } else if (kills > MAX_KILLS) {
            problems.add(String.format(Locale.ROOT, "練等速度不對：%d 級升一級要殺 %,.0f 隻同等級的普通怪（正常是幾百隻）。"
                    + "monsters.yml 的怪物經驗可能還是舊版（看有沒有 monsters.yml.new，/ec data status），或 experience_multiplier 太小",
                    level, kills));
        }
        return problems;
    }

    /**
     * 主控台的一句：「60 級升一級要殺 874 隻同等級的普通怪」；有全服經驗加倍時加上
     * 「（全服經驗 ×2：實際 437 隻）」。上面的檢查（{@link #problems}）不算加倍：那是在看兩個檔案搭不搭。
     */
    public static String summary(final CharacterConfig character, final IntToDoubleFunction monsterExperience) {
        final int level = checkLevel(character);
        final double kills = sameLevelKills(character, monsterExperience, level);
        if (Double.isInfinite(kills)) {
            return level + " 級的怪物不給經驗";
        }
        final String bonus = character.experienceBonusText();
        return String.format(Locale.ROOT, "%d 級升一級要殺 %,.0f 隻同等級的普通怪", level, kills)
                + (bonus == null ? "" : String.format(Locale.ROOT, "（全服經驗 %s：實際 %,.0f 隻）", bonus, kills / character.experienceBonus()));
    }

    /**
     * 地圖裝置用：這個等級打這個等級的怪，經驗剩多少、為什麼，
     * 例如「經驗 25%（比怪物高 15 級，怪物最高算 80 級；高等級的經驗減少）」。沒有減少回傳 null。
     */
    public static String penaltyNote(final CharacterConfig character, final int playerLevel, final int monsterLevel) {
        final double total = character.levelPenalty(playerLevel, monsterLevel);
        if (total >= 0.995) {
            return null;
        }
        final List<String> reasons = new ArrayList<>();
        if (character.levelDifferenceFactor(playerLevel, monsterLevel) < 0.995) {
            final int cap = character.monsterLevelCap();
            final int counted = cap > 0 ? Math.min(monsterLevel, cap) : monsterLevel;
            reasons.add((playerLevel > counted ? "比怪物高 " + (playerLevel - counted) : "比怪物低 " + (counted - playerLevel)) + " 級"
                    + (counted < monsterLevel ? "，怪物最高算 " + counted + " 級" : ""));
        }
        if (character.highLevelMultiplier(playerLevel) < 0.995) {
            reasons.add("高等級的經驗減少");
        }
        return "經驗 " + percent(total) + (reasons.isEmpty() ? "" : "（" + String.join("；", reasons) + "）");
    }

    /** 0.917 → 「92%」，0.059 → 「5.9%」。 */
    public static String percent(final double ratio) {
        final double value = ratio * 100;
        // 9.95 以上一位小數會變成 10.0，直接寫整數
        return value >= 9.95 ? Math.round(value) + "%" : String.format(Locale.ROOT, "%.1f%%", value);
    }
}
