package tw.exilecore.map;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-12 照站長「新增暗黑破壞神那樣的難度系統」：玩家自己選難度 1～10（1 = 原本的樣子）。
 * <ul>
 *   <li>套用：全部的地圖實例（地圖、終局、極境、巔峰、事件、深坑、劫盜…）照<b>開圖的人</b>選的難度：怪物（含 Boss）生命、傷害更多，
 *       物品數量、稀有度加在這張圖的數量、稀有度上，經驗加在地圖的經驗加成上。終局以上的 Boss 掉落平常不吃物品數量、稀有度（flat_boss_loot），
 *       難度給的照樣吃（站長：「終局以上 Boss 的獎勵要能吃得到難度給的物品數量與物品稀有度」）。地圖外面（劇情區域、營地、世界活動）不套。</li>
 *   <li>解鎖：難度 1 一開始就有；難度 N 要先打過「進階關卡 N」（要已經解鎖 N − 1）：用難度 N 打災厄級的塑星者・蒼冕
 *       （maps.yml event_bosses 的 {@link #TRIAL_KIND}，招式照 PoE1 的藍王），打倒就解鎖。關卡裡死了不扣經驗；不掉東西、沒有經驗
 *       （免費開的，不讓人拿來刷）；打過之後就不能再打。這次之前（同一天稍早）關卡是一張 T8～T16 的隨機地圖＋進階守衛。</li>
 *   <li>存在角色進度：{@link #UNLOCKED_KEY}（最高解鎖到幾）、{@link #SELECTED_KEY}（現在選幾）。</li>
 * </ul>
 * 數值在 maps.yml 的 difficulty（每一階一個值）。
 */
public final class Difficulty {

    /** 角色進度：最高解鎖到第幾階（沒寫 = 1）。 */
    public static final String UNLOCKED_KEY = "difficulty.unlocked";
    /** 角色進度：現在選第幾階（沒寫 = 1；超過解鎖的照解鎖的）。 */
    public static final String SELECTED_KEY = "difficulty.selected";
    /** 進階關卡的特別實例代號，也是 maps.yml event_bosses 裡那隻 Boss（塑星者・蒼冕）的代號。 */
    public static final String TRIAL_KIND = "difficulty_trial";

    private Difficulty() {
    }

    /** 一階：怪物生命、傷害倍率；物品數量、稀有度、經驗（百分點，+15 = +15%）。 */
    public record Tier(int level, double life, double damage, double quantity, double rarity, double experience) {
    }

    /** 全部的階。 */
    public record Table(List<Tier> tiers) {
        public Table {
            tiers = List.copyOf(tiers);
        }

        /** 站長 2026-11-12 選的表：生命每階 ×1.45、傷害每階 ×1.18；數量、經驗每階 +15%、稀有度每階 +25%。 */
        public static final Table DEFAULT = build(
                new double[] {1, 1.45, 2.10, 3.05, 4.42, 6.41, 9.29, 13.5, 19.5, 28.3},
                new double[] {1, 1.18, 1.39, 1.64, 1.94, 2.29, 2.70, 3.19, 3.76, 4.44},
                new double[] {0, 15, 30, 45, 60, 75, 90, 105, 120, 135},
                new double[] {0, 25, 50, 75, 100, 125, 150, 175, 200, 225},
                new double[] {0, 15, 30, 45, 60, 75, 90, 105, 120, 135});

        static Table build(final double[] life, final double[] damage, final double[] quantity, final double[] rarity,
                           final double[] experience) {
            final List<Tier> list = new ArrayList<>();
            for (int i = 0; i < life.length; i++) {
                list.add(new Tier(i + 1, life[i], at(damage, i, 1), at(quantity, i, 0), at(rarity, i, 0), at(experience, i, 0)));
            }
            return new Table(list);
        }

        private static double at(final double[] values, final int index, final double fallback) {
            return index < values.length ? values[index] : fallback;
        }

        /** 最高第幾階。 */
        public int max() {
            return tiers.size();
        }

        /** 第 level 階（超出範圍照最近的一階）。 */
        public Tier tier(final int level) {
            return tiers.get(Math.max(1, Math.min(max(), level)) - 1);
        }

        /** maps.yml 的 difficulty：life／damage／quantity／rarity／experience 各一串（第一個是難度 1）；沒寫的照 {@link #DEFAULT}。 */
        static Table read(final YamlNode node) {
            if (node == null || !node.has("life")) {
                return DEFAULT;
            }
            final double[] life = decimals(node, "life");
            if (life.length == 0) {
                return DEFAULT;
            }
            return build(life, decimals(node, "damage"), decimals(node, "quantity"), decimals(node, "rarity"), decimals(node, "experience"));
        }

        private static double[] decimals(final YamlNode node, final String key) {
            final List<String> raw = node.has(key) ? node.strings(key) : List.of();
            final double[] out = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                try {
                    out[i] = Double.parseDouble(raw.get(i).trim());
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(node.path() + "." + key + " 第 " + (i + 1) + " 個不是數字：" + raw.get(i));
                }
            }
            return out;
        }
    }

    // ---- 角色進度 ----

    static int parse(final String text, final int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    /** 這個角色最高解鎖到第幾階（沒角色 = 1）。 */
    public static int unlocked(final ExileCore plugin, final Player player, final Table table) {
        if (!plugin.players().get(player).hasCharacter()) {
            return 1;
        }
        return Math.max(1, Math.min(table.max(), parse(plugin.players().get(player).progress().get(UNLOCKED_KEY), 1)));
    }

    /** 這個角色現在選的難度（不會超過解鎖的）。 */
    public static int selected(final ExileCore plugin, final Player player, final Table table) {
        if (!plugin.players().get(player).hasCharacter()) {
            return 1;
        }
        return Math.max(1, Math.min(unlocked(plugin, player, table), parse(plugin.players().get(player).progress().get(SELECTED_KEY), 1)));
    }

    public static void select(final ExileCore plugin, final Player player, final int level) {
        plugin.characters().setProgress(player, SELECTED_KEY, String.valueOf(level));
    }

    static void unlock(final ExileCore plugin, final Player player, final int level) {
        plugin.characters().setProgress(player, UNLOCKED_KEY, String.valueOf(level));
    }

    /** 能不能打第 level 階的進階關卡：要已經解鎖 level − 1、還沒解鎖 level（純函式）。 */
    public static boolean canChallenge(final int unlocked, final int level, final int max) {
        return level >= 2 && level <= max && unlocked == level - 1;
    }

    /** 「×1.45」「+15%」這種字。 */
    static String times(final double value) {
        return "×" + (Math.abs(value - Math.rint(value)) < 1e-9 ? String.valueOf((long) Math.rint(value))
                : value >= 10 ? String.format(java.util.Locale.ROOT, "%.1f", value) : String.format(java.util.Locale.ROOT, "%.2f", value));
    }

    static String plus(final double value) {
        return "+" + (Math.abs(value - Math.rint(value)) < 1e-9 ? String.valueOf((long) Math.rint(value)) : String.valueOf(value)) + "%";
    }
}
