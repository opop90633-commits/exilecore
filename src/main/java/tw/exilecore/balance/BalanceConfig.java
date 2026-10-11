package tw.exilecore.balance;

import tw.exilecore.character.CharacterClass;
import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * data/balance.yml：平衡的參考角色與目標（/ec balance 用）。純資料，不依賴 Bukkit。
 * 載入時先全部解析完再換上去，寫錯時保留原本的設定。
 */
public final class BalanceConfig {

    /** 參考角色身上一條傷害詞綴：從幾級開始有、哪條詞綴。 */
    public record GearAffix(int from, String affix) {
    }

    /** 一個參考角色（攻擊或法術）。 */
    public record Build(CharacterClass characterClass, String weapon, String skill, List<GearAffix> gear, List<String> supports) {
        public Build {
            gear = List.copyOf(gear);
            supports = List.copyOf(supports);
        }
    }

    private NavigableMap<Integer, Double> killSeconds = new TreeMap<>(Map.of(1, 0.4, 60, 1.0, 84, 1.25, 100, 1.4));
    private NavigableMap<Integer, Double> hitPercent = new TreeMap<>(Map.of(1, 5.0, 60, 8.0, 84, 9.0, 100, 10.0));
    private NavigableMap<Integer, Double> bossSeconds = new TreeMap<>(Map.of(12, 30.0, 60, 75.0, 84, 95.0, 100, 110.0));
    private double spellVsAttack = 0.8;
    private int gearTier = 2;
    private int itemLevelCap = 84;
    private double treeDamagePerLevel = 2;
    private double treeLifePerLevel = 1;
    private double attributePerLevel = 0.5;
    private NavigableMap<Integer, Integer> lifeAffixes = new TreeMap<>(Map.of(1, 0, 5, 1, 15, 2, 30, 3));
    private NavigableMap<Integer, Integer> supportSockets = new TreeMap<>(Map.of(1, 2, 15, 3, 30, 4, 45, 5));
    private Build attack = new Build(CharacterClass.MARAUDER, "war_hammer", "boneshatter",
            List.of(new GearAffix(1, "phys_inc"), new GearAffix(1, "added_phys_local"), new GearAffix(5, "added_phys"), new GearAffix(10, "added_phys")),
            List.of("added_fire", "faster_attacks", "heft", "elemental_focus"));
    private Build spell = new Build(CharacterClass.SORCERESS, "wand", "fireball",
            // 跟出貨的 balance.yml 一樣（2026-10 PoE2 詞綴：沒有「法術附加傷害」，改成增加、額外獲得與 +技能等級）
            List.of(new GearAffix(1, "spell_inc_weapon_wand"), new GearAffix(1, "fire_inc_weapon_wand"), new GearAffix(5, "spell_inc"),
                    new GearAffix(5, "fire_inc"), new GearAffix(10, "extra_fire"), new GearAffix(5, "skill_level_spell"),
                    new GearAffix(10, "skill_level_spell_amulet")),
            List.of("faster_casting", "faster_projectiles", "concentrated_effect", "elemental_focus", "pierce"));

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("balance.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode targets = root.node("targets");
        final NavigableMap<Integer, Double> newKill = targets.has("kill_seconds") ? decimals(targets.node("kill_seconds")) : killSeconds;
        final NavigableMap<Integer, Double> newHit = targets.has("hit_percent") ? decimals(targets.node("hit_percent")) : hitPercent;
        final NavigableMap<Integer, Double> newBoss = targets.has("boss_seconds") ? decimals(targets.node("boss_seconds")) : bossSeconds;
        final double newSpellVsAttack = Math.max(0.05, targets.decimal("spell_vs_attack", spellVsAttack));
        final YamlNode reference = root.node("reference");
        final int newGearTier = Math.max(1, reference.integer("gear_tier", gearTier));
        final int newItemLevelCap = Math.max(1, reference.integer("item_level_cap", itemLevelCap));
        final YamlNode tree = reference.node("tree");
        final double newTreeDamage = tree.decimal("damage_per_level", treeDamagePerLevel);
        final double newTreeLife = tree.decimal("life_per_level", treeLifePerLevel);
        final double newAttribute = tree.decimal("attribute_per_level", attributePerLevel);
        final NavigableMap<Integer, Integer> newLifeAffixes = reference.has("life_affixes") ? integers(reference.node("life_affixes")) : lifeAffixes;
        final NavigableMap<Integer, Integer> newSockets = reference.has("support_sockets") ? integers(reference.node("support_sockets")) : supportSockets;
        final Build newAttack = reference.has("attack") ? build(reference.node("attack")) : attack;
        final Build newSpell = reference.has("spell") ? build(reference.node("spell")) : spell;
        if (newKill.isEmpty() || newHit.isEmpty() || newBoss.isEmpty()) {
            throw new IllegalArgumentException("balance.yml：targets.kill_seconds、hit_percent、boss_seconds 至少要有一個等級");
        }
        killSeconds = newKill;
        hitPercent = newHit;
        bossSeconds = newBoss;
        spellVsAttack = newSpellVsAttack;
        gearTier = newGearTier;
        itemLevelCap = newItemLevelCap;
        treeDamagePerLevel = newTreeDamage;
        treeLifePerLevel = newTreeLife;
        attributePerLevel = newAttribute;
        lifeAffixes = newLifeAffixes;
        supportSockets = newSockets;
        attack = newAttack;
        spell = newSpell;
    }

    private static NavigableMap<Integer, Double> decimals(final YamlNode node) {
        final NavigableMap<Integer, Double> map = new TreeMap<>();
        for (final String key : node.keys()) {
            map.put(level(node, key), node.decimal(key, 0));
        }
        return map;
    }

    private static NavigableMap<Integer, Integer> integers(final YamlNode node) {
        final NavigableMap<Integer, Integer> map = new TreeMap<>();
        for (final String key : node.keys()) {
            map.put(level(node, key), Math.max(0, node.integer(key, 0)));
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一個等級");
        }
        return map;
    }

    private static int level(final YamlNode node, final String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(node.path() + "：等級要是數字，卻是「" + key + "」");
        }
    }

    private static Build build(final YamlNode node) {
        final CharacterClass characterClass = CharacterClass.parse(node.string("class", null), null);
        if (characterClass == null) {
            throw new IllegalArgumentException(node.path() + ".class：不認得的職業 " + node.string("class", ""));
        }
        final List<GearAffix> gear = new ArrayList<>();
        for (final YamlNode entry : node.list("gear")) {
            gear.add(new GearAffix(Math.max(1, entry.integer("from", 1)), entry.requireString("affix").trim()));
        }
        final List<String> supports = new ArrayList<>();
        for (final String id : node.strings("supports")) {
            if (id != null && !id.isBlank()) {
                supports.add(id.trim());
            }
        }
        return new Build(characterClass, node.requireString("weapon").trim(), node.requireString("skill").trim(), gear, supports);
    }

    /** 目標擊殺時間（秒，生命 ÷ 每秒傷害的換算值，等比內插）。 */
    public double killSeconds(final int level) {
        return LevelCurve.at(killSeconds, level, 1.0, LevelCurve.Mode.GEOMETRIC);
    }

    /** 目標：同等級普通怪打一下扣參考角色幾 % 最大生命（等比內插）。 */
    public double hitPercent(final int level) {
        return LevelCurve.at(hitPercent, level, 5.0, LevelCurve.Mode.GEOMETRIC);
    }

    /** 目標：參考攻擊角色站著打區域 Boss 要幾秒（等比內插）。 */
    public double bossSeconds(final int level) {
        return LevelCurve.at(bossSeconds, level, 60.0, LevelCurve.Mode.GEOMETRIC);
    }

    public double spellVsAttack() {
        return spellVsAttack;
    }

    public int gearTier() {
        return gearTier;
    }

    /** 參考角色的裝備最高幾級的物品等級（最高階地圖的怪物等級：再高的角色也拿不到更高的裝備）。 */
    public int itemLevelCap() {
        return itemLevelCap;
    }

    public double treeDamagePerLevel() {
        return treeDamagePerLevel;
    }

    public double treeLifePerLevel() {
        return treeLifePerLevel;
    }

    public double attributePerLevel() {
        return attributePerLevel;
    }

    /** 這個等級身上有幾條生命詞綴（階梯：取 ≤ 等級的最後一筆）。 */
    public int lifeAffixes(final int level) {
        return step(lifeAffixes, level);
    }

    /** 這個等級技能有幾個輔助孔（階梯）。 */
    public int supportSockets(final int level) {
        return step(supportSockets, level);
    }

    private static int step(final NavigableMap<Integer, Integer> table, final int level) {
        final Map.Entry<Integer, Integer> entry = table.floorEntry(level);
        return entry == null ? 0 : entry.getValue();
    }

    public Build attack() {
        return attack;
    }

    public Build spell() {
        return spell;
    }
}
