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
 *   <li>{@code min_level}：進場要幾級。</li>
 *   <li>{@code flask_charges_per_kill}：擊倒一個玩家，擊倒的人藥劑充能幾次。</li>
 *   <li>{@code kill_feed}：擊倒的訊息（只給競技場裡的人）。</li>
 *   <li>{@code respawn_in_arena}：倒下後重生在競技場（不然回營地）。</li>
 * </ul>
 */
public final class PvpConfig {

    public static final double DEFAULT_REDUCTION = 70;
    /** 減傷最多 95%（不能完全不痛）。 */
    public static final double MAX_REDUCTION = 95;

    private boolean enabled;
    private double reduction;
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
        minLevel = Math.max(0, root.integer("min_level", 0));
        flaskCharges = Math.max(0, root.integer("flask_charges_per_kill", 5));
        killFeed = root.bool("kill_feed", true);
        respawnInArena = root.bool("respawn_in_arena", true);
        problems = List.copyOf(found);
    }

    public boolean enabled() {
        return enabled;
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
