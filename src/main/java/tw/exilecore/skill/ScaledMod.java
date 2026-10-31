package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 隨技能等級變的修飾器（光環的效果、戰吼的增益）：寫法跟輔助寶石的 modifiers 一樣，
 * 但 value 可以寫 [等級 1 的值, 等級 20 的值]。INCREASED／MORE 的值是百分比（25 = 25%）。純資料，不依賴 Bukkit。
 */
public record ScaledMod(String stat, ModType type, Scaled value, boolean percent, Set<String> tags) {

    public ScaledMod {
        tags = Set.copyOf(tags);
    }

    static ScaledMod parse(final YamlNode node) {
        final String stat = node.requireString("stat").trim();
        final ModType type;
        try {
            type = ModType.valueOf(node.string("type", "FLAT").trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(node.path() + ".type：要寫 FLAT、INCREASED 或 MORE");
        }
        final Scaled value = Scaled.parse(node.path() + ".value", node.raw("value"), 0);
        final Set<String> tags = new HashSet<>();
        for (final String tag : node.strings("tags")) {
            tags.add(tag.trim().toLowerCase(Locale.ROOT));
        }
        return new ScaledMod(stat, type, value, node.bool("percent", type != ModType.FLAT), tags);
    }

    static List<ScaledMod> parseList(final YamlNode node, final String key) {
        return node.list(key).stream().map(ScaledMod::parse).toList();
    }

    /** 這個等級、乘上效果倍率（光環效果）之後的修飾器。 */
    public Modifier toModifier(final int level, final double scale, final String source) {
        final double base = percent ? value.at(level) / 100.0 : value.at(level);
        double amount = base * scale;
        // 2026-10-31 修正：「更少」被效果放大（詛咒效果、光環效果、印記效果、戰吼效果）最多到 MAX_SCALED_LESS；
        //   這次之前萎力詛咒配一堆詛咒效果變成「124% 更少傷害」，傷害倍率變成負的，結果反而沒有減傷。
        //   本來就寫 -100% 以上的（「沒有…」這種）照寫的不動
        if (type == tw.exilecore.stats.ModType.MORE && amount < 0 && scale > 1) {
            amount = Math.max(amount, Math.min(base, -MAX_SCALED_LESS));
        }
        return new Modifier(stat, type, amount, tags, source);
    }

    /** 2026-10-31：被效果放大的「更少」最多幾成（0.75 = 75% 更少）。 */
    public static final double MAX_SCALED_LESS = 0.75;

    /** 說明文字。 */
    public String describe(final int level, final double scale) {
        return ModifierText.describe(toModifier(level, scale, ""));
    }
}
