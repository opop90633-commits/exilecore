package tw.exilecore.anticheat;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * data/anticheat.yml（2026-10）：防作弊的設定（純資料）。只管「改遊戲數值」那一類——移動速度、飛行、懸空、瞬移；
 * 腳本、連點那一類不管。
 */
public final class AntiCheatConfig {

    private boolean enabled;
    private boolean speed;
    private boolean flight;
    private double tolerance;
    private int hoverTicks;
    private double maxRise;
    private double decayPerTick;
    private int strikesToKick;
    private int strikeWindowSeconds;
    private boolean notifyAdmins;
    private boolean tellPlayer;
    private int graceTicks;
    private List<String> problems = List.of();

    public AntiCheatConfig() {
        defaults();
    }

    private void defaults() {
        enabled = true;
        speed = true;
        flight = true;
        tolerance = MovementCheck.Config.DEFAULT.tolerance();
        hoverTicks = MovementCheck.Config.DEFAULT.hoverTicks();
        maxRise = MovementCheck.Config.DEFAULT.maxRise();
        decayPerTick = MovementCheck.Config.DEFAULT.decayPerTick();
        strikesToKick = 8;
        strikeWindowSeconds = 120;
        notifyAdmins = true;
        tellPlayer = true;
        graceTicks = 40;
        problems = List.of();
    }

    /** 讀 data/anticheat.yml；沒有這個檔案就用預設值。 */
    public void load(final Path dataFolder) throws IOException {
        defaults();
        final Path file = dataFolder.resolve("anticheat.yml");
        if (Files.exists(file)) {
            apply(YamlNode.load(file));
        }
    }

    /** 讀一份設定（測試用）。 */
    public static AntiCheatConfig parse(final YamlNode root) {
        final AntiCheatConfig config = new AntiCheatConfig();
        config.apply(root);
        return config;
    }

    private void apply(final YamlNode root) {
        final List<String> found = new ArrayList<>();
        enabled = root.bool("enabled", true);
        final YamlNode checks = root.node("checks");
        speed = checks.bool("speed", true);
        flight = checks.bool("flight", true);
        tolerance = clamp(root.decimal("tolerance", tolerance), 1.0, 3.0, "tolerance", found);
        hoverTicks = (int) clamp(root.integer("hover_ticks", hoverTicks), 6, 100, "hover_ticks", found);
        maxRise = clamp(root.decimal("max_rise", maxRise), 1.3, 10, "max_rise", found);
        decayPerTick = clamp(root.decimal("decay_per_tick", decayPerTick), 0.001, 1, "decay_per_tick", found);
        strikesToKick = (int) clamp(root.integer("strikes_to_kick", strikesToKick), 0, 1000, "strikes_to_kick", found);
        strikeWindowSeconds = (int) clamp(root.integer("strike_window_seconds", strikeWindowSeconds), 10, 86_400, "strike_window_seconds", found);
        notifyAdmins = root.bool("notify_admins", true);
        tellPlayer = root.bool("tell_player", true);
        graceTicks = (int) clamp(root.integer("grace_ticks", graceTicks), 0, 400, "grace_ticks", found);
        problems = List.copyOf(found);
    }

    private static double clamp(final double raw, final double min, final double max, final String key, final List<String> problems) {
        final double value = Math.max(min, Math.min(max, raw));
        if (value != raw) {
            problems.add("anticheat.yml：" + key + " 要在 " + min + "–" + max + " 之間（" + raw + " 改用 " + value + "）");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean speed() {
        return speed;
    }

    public boolean flight() {
        return flight;
    }

    public MovementCheck.Config movement() {
        return new MovementCheck.Config(tolerance, hoverTicks, maxRise, decayPerTick);
    }

    /** 幾次拉回之後踢出（0 = 不踢，只拉回）。 */
    public int strikesToKick() {
        return strikesToKick;
    }

    /** 幾秒內的拉回才算在一起。 */
    public int strikeWindowSeconds() {
        return strikeWindowSeconds;
    }

    public boolean notifyAdmins() {
        return notifyAdmins;
    }

    public boolean tellPlayer() {
        return tellPlayer;
    }

    /** 登入、傳送、重生、被擊退之後幾刻內不檢查。 */
    public int graceTicks() {
        return graceTicks;
    }

    public List<String> problems() {
        return problems;
    }
}
