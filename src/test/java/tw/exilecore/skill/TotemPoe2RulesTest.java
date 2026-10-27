package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26 照 PoE2 0.5 的圖騰規則：放置時間、英靈圖騰固定上限、先祖魂約、弩台抗性。 */
class TotemPoe2RulesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String skill, final StatSheet sheet) {
        return SkillBuild.of(config, config.skill(skill), 20, 60, false, List.of(), Set.of(Tags.MACE, Tags.CROSSBOW), sheet);
    }

    @Test
    void placementTimeIsTheGemCastTimeAndOnlyPlacementSpeedCounts() {
        final StatSheet plain = new StatSheet();
        assertEquals(12, build("shockwave_totem", plain).intervalTicks(plain), 1e-9, "崩震圖騰 0.6 秒");
        assertEquals(20, build("siege_ballista", plain).intervalTicks(plain), 1e-9, "弩台 1 秒");
        assertEquals(10, build("ancestral_totem", plain).intervalTicks(plain), 1e-9, "英靈圖騰 0.5 秒");
        final StatSheet fast = new StatSheet().add(Modifier.increased(StatKeys.ATTACK_SPEED, 1.0, "測試"))
                .add(Modifier.increased(StatKeys.CAST_SPEED, 1.0, "測試"));
        assertEquals(12, build("shockwave_totem", fast).intervalTicks(fast), 1e-9, "攻擊速度不影響放圖騰");
        assertEquals(20, build("dark_idol", fast).intervalTicks(fast), 1e-9, "施法速度不影響放圖騰");
        final StatSheet urgent = new StatSheet().add(Modifier.increased(tw.exilecore.stats.SupportKeys.TOTEM_PLACEMENT_SPEED, 1.0, "測試"));
        assertEquals(6, build("shockwave_totem", urgent).intervalTicks(urgent), 1e-9, "放圖騰的速度 +100%：一半");
    }

    @Test
    void ancestralWarriorTotemLimitCannotBeModified() {
        final StatSheet sheet = new StatSheet().add(Modifier.flat(StatKeys.SKILL_TOTEMS, 2, "測試"))
                .add(Modifier.flat(StatKeys.FLAG_ANCESTRAL_BOND, 1, "先祖魂約"));
        assertEquals(10, build("ancestral_totem", sheet).totems(), "英靈圖騰固定 10 根");
        assertEquals(6, build("shockwave_totem", sheet).totems(), "其他圖騰：(1 + 2) × 2");
    }

    @Test
    void totemsTakeDamageThroughResistancesAndArmour() {
        final tw.exilecore.stats.DamagePacket packet = new tw.exilecore.stats.DamagePacket();
        packet.set(tw.exilecore.stats.DamageType.FIRE, 100);
        packet.set(tw.exilecore.stats.DamageType.CHAOS, 100);
        assertEquals(25 + 65, SkillDeployables.totemTaken(75, 35, 0, packet), 1e-9, "元素 75%、混沌 35%");
        final tw.exilecore.stats.DamagePacket physical = new tw.exilecore.stats.DamagePacket();
        physical.set(tw.exilecore.stats.DamageType.PHYSICAL, 100);
        assertEquals(100, SkillDeployables.totemTaken(75, 35, 0, physical), 1e-9, "沒有護甲：物理全吃");
        assertTrue(SkillDeployables.totemTaken(75, 35, 1000, physical) < 100, "護甲減物理");
    }

    @Test
    void totemLifeFollowsThePoe2AllyTable() throws IOException {
        final java.util.function.IntToDoubleFunction life = level -> 100.0;
        final java.util.function.IntFunction<double[]> damage = level -> new double[] {4.0 * level, 4.0 * level};
        final double low = SkillDeployables.totemBase(config, 1, 1, life, damage, 1.0)[0];
        final double high = SkillDeployables.totemBase(config, 20, 1, life, damage, 1.0)[0];
        assertTrue(high > low, "寶石等級越高圖騰越硬");
        assertEquals(1.2 * high, SkillDeployables.totemBase(config, 20, 1, life, damage, SkillDeployables.ANCESTRAL_TOTEM_LIFE)[0], 1e-6,
                "英靈圖騰 120%");
        // 2026-10-27 照 PoE2：20 級（區域 90 級）的圖騰生命是原作的 2490（英靈 2988）；這次之前 PoE2 的表只到 83 級，多了兩成（約 3035）
        final tw.exilecore.monster.MonsterConfig monsters = new tw.exilecore.monster.MonsterConfig();
        monsters.load(Path.of("src/main/resources/data"));
        final double real = SkillDeployables.totemBase(config, 20, 80, monsters::lifeAt, monsters::damageAt, 1.0)[0];
        assertEquals(2490, real, 2490 * 0.02, "20 級圖騰生命跟原作一樣");
        assertEquals(2490 * 1.2, SkillDeployables.totemBase(config, 20, 80, monsters::lifeAt, monsters::damageAt,
                SkillDeployables.ANCESTRAL_TOTEM_LIFE)[0], 2490 * 1.2 * 0.02, "英靈圖騰 2988");
    }

    @Test
    void ballistasGainResistancesByLevel() {
        final StatSheet shockwave = build("shockwave_totem", new StatSheet()).profile().effective(new StatSheet());
        assertTrue(shockwave.flat(StatKeys.TOTEM_RESIST_ELEMENTAL, Set.of()) > 0);
        for (final String id : List.of("siege_ballista", "artillery_ballista")) {
            final StatSheet effective = build(id, new StatSheet()).profile().effective(new StatSheet());
            assertEquals(shockwave.flat(StatKeys.TOTEM_RESIST_ELEMENTAL, Set.of()), effective.flat(StatKeys.TOTEM_RESIST_ELEMENTAL, Set.of()), 1e-9, id);
            assertEquals(shockwave.flat(StatKeys.TOTEM_RESIST_CHAOS, Set.of()), effective.flat(StatKeys.TOTEM_RESIST_CHAOS, Set.of()), 1e-9, id);
        }
        assertTrue(config.skill("ancestral_totem").deploy().fixedMax());
    }

    // ---- 2026-10-26 照 PoE2（poe2db Spell Totem／Ancestral Warrior Totem／Shockwave Totem）----

    private static SkillBuild withQuality(final String skill, final int quality) throws IOException {
        return withQuality(skill, quality, false);
    }

    /** advanced：2026-10-27 再加上額外的品質效果（昇華・進階奇術）。 */
    private static SkillBuild withQuality(final String skill, final int quality, final boolean advanced) throws IOException {
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
        final SkillDefinition definition = config.skill(skill);
        final List<Modifier> mods = SkillService.qualityModifiers(definition, quality);
        return SkillBuild.of(config, definition, 20, 60, false, List.of(), Set.of(Tags.MACE, Tags.MELEE), new StatSheet(), Set.of(),
                advanced ? tw.exilecore.ascendancy.AscBuild.advanced(skill, quality, mods) : mods);
    }

    @Test
    void shockwaveTotemSlamsAtHalfYourAttackSpeed() {
        assertEquals(27, SkillDeployables.weaponTotemTicks(1.5, 0.5, 1.0), "武器 1.5 次／秒 × 50% = 0.75 次／秒");
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.WEAPON_APS, 1.6);
        assertEquals(25, SkillDeployables.totemTicks(build("shockwave_totem", sheet), sheet, 0), "1.6 × 50% = 0.8 次／秒");
        final StatSheet fast = new StatSheet().add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.6, "測試"));
        fast.setBase(StatKeys.WEAPON_APS, 1.6);
        assertEquals(16, SkillDeployables.totemTicks(build("shockwave_totem", fast), fast, 0), "攻速 +60%");
        assertTrue(config.skill("shockwave_totem").usableWith(Set.of(Tags.MELEE)), "任何近戰武器");
        assertEquals(3, config.skill("shockwave_totem").tier());
    }

    @Test
    void totemGemQualityFollowsPoe2() throws IOException {
        final SkillBuild shockwave = withQuality("shockwave_totem", 20);
        assertEquals(16 * 20, shockwave.durationTicks(shockwave.deploy().duration()), "崩震圖騰 12 秒 + 4 秒");
        // 2026-10-27 照 PoE2：這條是額外的品質效果（進階奇術才有，gem_quality.yml 的 advanced），一般品質沒有了
        assertEquals(1, shockwave.totems(), "一般品質不加圖騰上限");
        assertEquals(2, withQuality("shockwave_totem", 20, true).totems(), "進階奇術：20% 品質 +1 圖騰上限");
        assertEquals(1, withQuality("shockwave_totem", 10, true).totems(), "10% 品質還不到 +1");
        final SkillBuild ancestral = withQuality("ancestral_totem", 20);
        assertEquals(10 * 20, ancestral.durationTicks(ancestral.deploy().duration()), "英靈圖騰 8 秒 + 2 秒");
        final SkillBuild ancestralAdvanced = withQuality("ancestral_totem", 20, true);
        assertEquals(1.0, ancestral.profile().effective(new StatSheet()).apply(StatKeys.TOTEM_LIFE, 1.0, ancestral.profile().tags()), 1e-9);
        assertEquals(1.5, ancestralAdvanced.profile().effective(new StatSheet()).apply(StatKeys.TOTEM_LIFE, 1.0, ancestralAdvanced.profile().tags()), 1e-9,
                "進階奇術：增加 50% 圖騰生命");
        final List<Modifier> spellTotem = SkillService.qualityModifiers(config.skill("totem_casting"), 20);
        assertTrue(spellTotem.stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.TotemKeys.DURATION_FLAT) && Math.abs(m.value() - 4) < 1e-9));
        assertTrue(spellTotem.stream().noneMatch(m -> m.stat().equals(tw.exilecore.stats.TotemKeys.CAST_SPEED_PER_TOTEM)));
        assertTrue(tw.exilecore.ascendancy.AscBuild.advanced("totem_casting", 20, spellTotem).stream()
                .anyMatch(m -> m.stat().equals(tw.exilecore.stats.TotemKeys.CAST_SPEED_PER_TOTEM) && Math.abs(m.value() - 10) < 1e-9), "進階奇術");
    }

    @Test
    void spellTotemAndAncestralTotemFollowPoe2() {
        double elemental = 0;
        double chaos = 0;
        for (final ScaledMod mod : config.levelModifiers("totem_casting")) {
            final Modifier at20 = mod.toModifier(20, 1.0, "測試");
            if (at20.stat().equals(StatKeys.TOTEM_RESIST_ELEMENTAL)) {
                elemental = at20.value();
            } else if (at20.stat().equals(StatKeys.TOTEM_RESIST_CHAOS)) {
                chaos = at20.value();
            }
        }
        assertEquals(75, elemental, 1e-9);
        assertEquals(35, chaos, 1e-9);
        final SkillDefinition ancestral = config.skill("ancestral_totem");
        assertEquals(13, ancestral.tier());
        assertTrue(ancestral.tags().containsAll(Set.of(Tags.META, Tags.TOTEM, Tags.DURATION)));
        // meta 標籤不能讓能量輔助、骰運掛上英靈圖騰；能量輔助也不能掛上星咒圖騰
        assertTrue(!config.support("boundless_energy").supports(ancestral));
        assertTrue(!config.support("fluke").supports(ancestral));
        assertTrue(!config.support("boundless_energy").supports(config.skill("totem_casting")));
    }

    @Test
    void totemRecoupOnlyCountsLifeActuallyLost() {
        // 2026-10-27：圖騰受到的傷害補償為生命只算實際扣掉的生命（這次之前整下都算，便宜圖騰擋 Boss 一下補滿）
        assertEquals(100, SkillDeployables.recoupable(100, 5000), 1e-9, "超過剩下生命的部分不算");
        assertEquals(30, SkillDeployables.recoupable(100, 30), 1e-9);
        assertEquals(0, SkillDeployables.recoupable(0, 500), 1e-9, "已經倒下的不算");
        assertEquals(0, SkillDeployables.recoupable(100, -5), 1e-9);
    }
}
