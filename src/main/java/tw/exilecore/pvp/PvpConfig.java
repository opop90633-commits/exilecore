package tw.exilecore.pvp;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * data/pvp.yml：PvP 競技場的設定（純資料）。
 * <ul>
 *   <li>{@code damage_reduction}：專屬的 PvP 減傷（%，0–95）。玩家打玩家（含召喚物、圖騰、陷阱）的傷害都乘 1 − 這個；
 *       這一下造成的元素異常、持續傷害、暈眩與凍結的累積照縮小後的傷害算。</li>
 *   <li>{@code max_hit_percent}、{@code max_per_second_percent}（2026-10 保底）：單一下、每秒最多打掉對方總血量（生命＋護盾）的幾 %。</li>
 *   <li>{@code minion_damage}：召喚物、圖騰、陷阱打玩家的傷害再乘多少。</li>
 *   <li>{@code melee_reduction}：拿近戰武器的人受到的 PvP 傷害再少幾 %（補追人的時間）。</li>
 *   <li>{@code min_level}：進場要幾級。</li>
 *   <li>{@code flask_charges_per_kill}：擊倒一個玩家，擊倒的人藥劑充能幾次。</li>
 *   <li>{@code kill_feed}：擊倒的訊息（只給競技場裡的人）。</li>
 *   <li>{@code respawn_in_arena}：倒下後重生在競技場（不然回營地）。</li>
 * </ul>
 */
public final class PvpConfig {

    public static final double DEFAULT_REDUCTION = 85; // 2026-10：70 → 85（大家的輸出比血量高太多，70% 還是一秒倒）
    public static final double DEFAULT_HIT_CAP = 30;
    public static final double DEFAULT_SECOND_CAP = 25;
    public static final double DEFAULT_MINION_DAMAGE = 0.5;
    public static final double DEFAULT_MELEE_REDUCTION = 20;
    /** 減傷最多 95%（不能完全不痛）。 */
    public static final double MAX_REDUCTION = 95;

    private boolean enabled;
    private double reduction;
    private double hitCap;
    private double secondCap;
    private double minionDamage;
    private double meleeReduction;
    private int minLevel;
    private int flaskCharges;
    private boolean killFeed;
    private boolean respawnInArena;
    private List<String> problems = List.of();

    public PvpConfig() {
        defaults();
    }

    private void defaults() {
        enabled = true;
        reduction = DEFAULT_REDUCTION;
        hitCap = DEFAULT_HIT_CAP;
        secondCap = DEFAULT_SECOND_CAP;
        minionDamage = DEFAULT_MINION_DAMAGE;
        meleeReduction = DEFAULT_MELEE_REDUCTION;
        minLevel = 0;
        flaskCharges = 5;
        killFeed = true;
        respawnInArena = true;
        problems = List.of();
    }

    /** 讀 data/pvp.yml；沒有這個檔案就用預設值。 */
    public void load(final Path dataFolder) throws IOException {
        defaults();
        final Path file = dataFolder.resolve("pvp.yml");
        if (Files.exists(file)) {
            apply(YamlNode.load(file));
        }
    }

    /** 讀一份設定（測試用）。 */
    public static PvpConfig parse(final YamlNode root) {
        final PvpConfig config = new PvpConfig();
        config.apply(root);
        return config;
    }

    private void apply(final YamlNode root) {
        final List<String> found = new ArrayList<>();
        enabled = root.bool("enabled", true);
        final double raw = root.decimal("damage_reduction", DEFAULT_REDUCTION);
        reduction = Math.max(0, Math.min(MAX_REDUCTION, raw));
        if (raw != reduction) {
            found.add("pvp.yml：damage_reduction 要在 0–" + (int) MAX_REDUCTION + " 之間（" + raw + " 改用 " + reduction + "）");
        }
        hitCap = percent(root, "max_hit_percent", DEFAULT_HIT_CAP, 100, found);
        secondCap = percent(root, "max_per_second_percent", DEFAULT_SECOND_CAP, 100, found);
        final double rawMinion = root.decimal("minion_damage", DEFAULT_MINION_DAMAGE);
        minionDamage = Math.max(0, Math.min(1, rawMinion));
        if (rawMinion != minionDamage) {
            found.add("pvp.yml：minion_damage 要在 0–1 之間（" + rawMinion + " 改用 " + minionDamage + "）");
        }
        meleeReduction = percent(root, "melee_reduction", DEFAULT_MELEE_REDUCTION, 90, found);
        minLevel = Math.max(0, root.integer("min_level", 0));
        flaskCharges = Math.max(0, root.integer("flask_charges_per_kill", 5));
        killFeed = root.bool("kill_feed", true);
        respawnInArena = root.bool("respawn_in_arena", true);
        problems = List.copyOf(found);
    }

    private static double percent(final YamlNode root, final String key, final double fallback, final double max, final List<String> found) {
        final double raw = root.decimal(key, fallback);
        final double value = Math.max(0, Math.min(max, raw));
        if (raw != value) {
            found.add("pvp.yml：" + key + " 要在 0–" + (int) max + " 之間（" + raw + " 改用 " + value + "）");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 保底：單一下最多打掉對方總血量的幾 %（0 = 不限）。 */
    public double hitCap() {
        return hitCap;
    }

    /** 保底：每秒最多打掉對方總血量的幾 %（0 = 不限）。 */
    public double secondCap() {
        return secondCap;
    }

    /** 召喚物、圖騰、陷阱打玩家的傷害再乘這個（1 = 跟自己打的一樣）。 */
    public double minionDamage() {
        return minionDamage;
    }

    /** 拿近戰武器的人受到的 PvP 傷害再少幾 %。 */
    public double meleeReduction() {
        return meleeReduction;
    }

    /** PvP 減傷（%）。 */
    public double reduction() {
        return reduction;
    }

    /** 玩家打玩家的傷害乘上這個（1 − 減傷）。 */
    public double multiplier() {
        return 1 - reduction / 100.0;
    }

    public int minLevel() {
        return minLevel;
    }

    public int flaskCharges() {
        return flaskCharges;
    }

    public boolean killFeed() {
        return killFeed;
    }

    public boolean respawnInArena() {
        return respawnInArena;
    }

    /** 讀設定時發現的問題。 */
    public List<String> problems() {
        return problems;
    }
}
