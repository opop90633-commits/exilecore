package tw.exilecore.currency;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-26 照 PoE2：技能寶石的品質（琢星稜鏡）照每一顆寶石自己的品質效果（data/gem_quality.yml），不再全部統一成「每 1% 增加 1% 傷害」。
 * 一行是「每 1% 品質給多少」：
 * <pre>
 * fireball:                       # 技能代號（skills.yml）
 *   - { stat: skill.projectiles, type: FLAT, per: 0.05 }
 *   - { stat: ignite.chance, type: MORE, per: 1, tags: [fire] }
 * </pre>
 * INCREASED／MORE 的 per 是百分點（1 = 每 1% 品質 1%），FLAT 照屬性本身的單位。沒寫在這裡的技能照舊（SkillService 的通用規則）。
 * <p>
 * 2026-10-27 照 PoE2 0.5：寶石可以有好幾條品質效果。{@code advanced:} 區塊是「額外的品質效果」（poe2db 寶石頁 Additional Effects From Quality
 * 的第二條），只有昇華・進階奇術（Advanced Thaumaturgy：Gem Quality grants Socketed Skills an additional effect）才有：
 * <pre>
 * advanced:
 *   fireball:
 *     - { stat: ..., type: ..., per: ... }
 * </pre>
 * 純邏輯，不依賴 Bukkit。
 */
public final class GemQuality {

    /** 一條：每 1% 品質給 per。 */
    public record Line(String stat, ModType type, double per, Set<String> tags) {
    }

    private static volatile Map<String, List<Line>> table = Map.of();
    /** 2026-10-27：額外的品質效果（進階奇術）。 */
    private static volatile Map<String, List<Line>> advanced = Map.of();
    /** gem_quality.yml 裡放額外品質效果的區塊名稱（不是技能代號）。 */
    public static final String ADVANCED = "advanced";

    private GemQuality() {
    }

    /** 讀 data/gem_quality.yml（沒有這個檔就清空，全部照通用規則）。 */
    public static void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("gem_quality.yml");
        if (!Files.exists(file)) {
            table = Map.of();
            advanced = Map.of();
            return;
        }
        final YamlNode root = YamlNode.load(file);
        table = parse(root, Set.of(ADVANCED));
        advanced = root.has(ADVANCED) ? parse(root.node(ADVANCED), Set.of()) : Map.of();
    }

    private static Map<String, List<Line>> parse(final YamlNode root, final Set<String> skip) {
        final Map<String, List<Line>> parsed = new java.util.HashMap<>();
        for (final String skill : root.keys()) {
            if (skip.contains(skill)) {
                continue;
            }
            final List<Line> lines = new ArrayList<>();
            for (final YamlNode node : root.list(skill)) {
                final ModType type;
                try {
                    type = ModType.valueOf(node.requireString("type").trim().toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException e) {
                    throw new IllegalArgumentException(node.path() + ".type：要寫 FLAT、INCREASED 或 MORE");
                }
                lines.add(new Line(node.requireString("stat").trim(), type, node.decimal("per", 0), new HashSet<>(node.strings("tags"))));
            }
            parsed.put(skill, List.copyOf(lines));
        }
        return Map.copyOf(parsed);
    }

    /** 測試用：直接換掉整張表。 */
    static void set(final Map<String, List<Line>> lines) {
        table = Map.copyOf(lines);
    }

    /** 測試用：直接換掉額外品質效果的表。 */
    static void setAdvanced(final Map<String, List<Line>> lines) {
        advanced = Map.copyOf(lines);
    }

    /** 2026-10-27：這個技能有沒有額外的品質效果（進階奇術）。 */
    public static boolean hasAdvanced(final String skillId) {
        return skillId != null && advanced.containsKey(skillId);
    }

    /** 2026-10-27：這個技能 quality% 品質的額外品質效果（進階奇術）；表上沒有回傳 null。 */
    public static List<Modifier> advancedModifiers(final String skillId, final int quality, final String source) {
        final List<Line> lines = skillId == null ? null : advanced.get(skillId);
        if (lines == null) {
            return null;
        }
        return lines(lines, quality, source);
    }

    /** 這個技能有沒有自己的品質效果。 */
    public static boolean has(final String skillId) {
        return skillId != null && table.containsKey(skillId);
    }

    /** 這個技能 quality% 品質給的修飾器；沒寫在表上回傳 null（照通用規則）。 */
    public static List<Modifier> modifiers(final String skillId, final int quality, final String source) {
        final List<Line> lines = skillId == null ? null : table.get(skillId);
        if (lines == null) {
            return null;
        }
        return lines(lines, quality, source);
    }

    private static List<Modifier> lines(final List<Line> lines, final int quality, final String source) {
        if (quality <= 0) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final Line line : lines) {
            final double amount = line.per() * quality;
            final double value = line.type() == ModType.FLAT ? amount : amount / 100.0;
            list.add(new Modifier(line.stat(), line.type(), value, Set.copyOf(line.tags()), source));
        }
        return List.copyOf(list);
    }
}
