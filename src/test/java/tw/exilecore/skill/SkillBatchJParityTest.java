package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2 0.5 補齊的機制：銀月庇（擊退、月光延遲）、掠閃（取代翻滾）、拾鱗（甲片）、調藥心得（藥劑回復分給友軍）、
 * 風之舞步（階數、疾風）、拾晶裝（限弩、榴彈冷卻）。
 */
class SkillBatchJParityTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        tw.exilecore.currency.GemQuality.load(DATA);
    }

    private static SkillBuild build(final String skill, final int level, final Set<String> weapons) {
        return SkillBuild.of(config, config.skill(skill), level, 100, false, List.of(), weapons, new StatSheet());
    }

    private static double sum(final List<Modifier> mods, final String stat) {
        return mods.stream().filter(m -> m.stat().equals(stat)).mapToDouble(Modifier::value).sum();
    }

    @Test
    void lunarBlessingKnocksBackAndMoonbeamsAreDelayed() {
        assertTrue(config.skill("silver_moon_shelter").warcry().extras().knockback(), "PoE2 global_knockback：吼的時候擊退");
        assertEquals(19, SupportProcs.moonbeamLandsAfter(0), "第一道 0.95 秒後落下");
        assertEquals(23, SupportProcs.moonbeamLandsAfter(1), "之後每道多 0.2 秒");
        assertEquals(19 + 4 * 8, SupportProcs.moonbeamLandsAfter(8));
    }

    @Test
    void blinkReplacesDodgeRoll() {
        assertEquals(0.7, config.skill("blink").castTime(), 1e-9, "PoE2 castTime 0.7");
        final java.util.UUID someone = java.util.UUID.randomUUID();
        assertTrue(BlinkDodge.blocksKey("blink", someone), "掠閃自己的按鍵不放");
        assertFalse(BlinkDodge.blocksKey("frost_blink_dash", someone));
        final String[] bound = new String[tw.exilecore.character.SkillLoadout.BIND_SLOTS];
        bound[5] = "blink";
        assertEquals(5, BlinkDodge.boundKey(slot -> bound[slot], "blink"));
        assertEquals(-1, BlinkDodge.boundKey(slot -> null, "blink"));
    }

    @Test
    void scavengedPlatingStacksByRarity() {
        assertEquals(1, PersistentBuffs.plateStacks(MonsterRarity.NORMAL));
        assertEquals(2, PersistentBuffs.plateStacks(MonsterRarity.MAGIC));
        assertEquals(5, PersistentBuffs.plateStacks(MonsterRarity.RARE));
        assertEquals(10, PersistentBuffs.plateStacks(MonsterRarity.UNIQUE));
        assertEquals(0, PersistentBuffs.plateStacks(null));
        final List<PersistentBuffs.Plate> plates = new ArrayList<>();
        PersistentBuffs.addPlates(plates, 5, 100);
        PersistentBuffs.addPlates(plates, 2, 120);
        assertEquals(7, PersistentBuffs.plateCount(plates));
        PersistentBuffs.addPlates(plates, 5, 140);
        assertEquals(10, PersistentBuffs.plateCount(plates), "最多 10 層");
        assertEquals(3, plates.getFirst().count(), "超過的從最舊的那批扣");
        PersistentBuffs.addPlates(plates, 10, 160);
        assertEquals(10, PersistentBuffs.plateCount(plates));
        assertEquals(1, plates.size(), "傳奇一次就滿，舊的全部擠掉");
        // 每層：更多 4% 護甲、荊棘 2–2（1 級）／43–64（20 級）／124–186（40 級，表裡的值）
        final List<Modifier> one = build("gathered_scales", 1, Set.of()).auraModifiers();
        assertEquals(4, sum(one, PersistentBuffs.PLATING_ARMOUR), 1e-9);
        assertEquals(2, sum(one, PersistentBuffs.PLATING_THORNS_MIN), 1e-9);
        final List<Modifier> twenty = build("gathered_scales", 20, Set.of()).auraModifiers();
        assertEquals(43, sum(twenty, PersistentBuffs.PLATING_THORNS_MIN), 1e-9);
        assertEquals(64, sum(twenty, PersistentBuffs.PLATING_THORNS_MAX), 1e-9);
        assertEquals(186, config.skill("gathered_scales").aura().effects().get(2).value().at(40), 1e-9);
        assertEquals(0, sum(twenty, tw.exilecore.stats.StatKeys.ARMOUR), 1e-9, "不再是常駐的護甲增加");
    }

    @Test
    void windDancerStagesAndGaleForce() {
        assertEquals(0, PersistentBuffs.windStages(-1, 100, 30));
        assertEquals(0, PersistentBuffs.windStages(100, 129, 30));
        assertEquals(1, PersistentBuffs.windStages(100, 130, 30));
        assertEquals(3, PersistentBuffs.windStages(100, 1000, 30), "最多 3 階");
        assertEquals(2.0, PersistentBuffs.galeRadiusScale(3), 1e-9, "3 階：範圍更多 300% = 半徑 ×2");
        assertEquals(2.5, PersistentBuffs.galeDamageScale(3), 1e-9);
        assertEquals(1.75, PersistentBuffs.galeKnockbackScale(3), 1e-9);
        assertEquals(1.5, levelValue("breeze_dance_step", PersistentBuffs.WIND_INTERVAL, 1), 1e-9);
        assertEquals(1.12, levelValue("breeze_dance_step", PersistentBuffs.WIND_INTERVAL, 20), 1e-9);
        assertEquals(0.82, levelValue("breeze_dance_step", PersistentBuffs.WIND_INTERVAL, 40), 1e-9);
        assertEquals(10, sum(build("breeze_dance_step", 1, Set.of()).auraModifiers(), PersistentBuffs.WIND_EVASION), 1e-9);
        final SkillDefinition gale = config.skill(PersistentBuffs.GALE_FORCE);
        assertNotNull(gale);
        assertTrue(gale.hidden());
        assertTrue(gale.isAttack());
        assertEquals(0.25, gale.cooldown(), 1e-9);
        assertEquals(0.82, gale.effectiveness().at(20), 1e-9);
        assertEquals(3.62, gale.effectiveness().at(40), 1e-9);
        assertTrue(gale.params().knockback() > 0, "疾風擊退");
        assertFalse(config.skill("breeze_dance_step").usableWith(Set.of()), "要拿武鬥武器");
        assertTrue(config.skill("breeze_dance_step").usableWith(Set.of(Tags.SPEAR)));
    }

    @Test
    void alchemistsBoonSharesFlaskRecovery() {
        assertEquals(54, config.skill("potion_craft_insight").aura().effects().get(2).value().at(40), 1e-9);
        assertEquals(15, sum(build("potion_craft_insight", 1, Set.of()).auraModifiers(), "flask.ally_share"), 1e-9);
        assertEquals(34, sum(build("potion_craft_insight", 20, Set.of()).auraModifiers(), "flask.ally_share"), 1e-9);
    }

    @Test
    void shardScavengerNeedsCrossbowAndRestoresGrenadeUses() {
        assertFalse(config.skill("crystal_pickup_load").usableWith(Set.of(Tags.BOW)), "PoE2 weaponTypes Crossbow");
        assertTrue(config.skill("crystal_pickup_load").usableWith(Set.of(Tags.CROSSBOW)));
        assertEquals(1, SkillRuntime.earliestPending(new long[] {50, 120, 200}, 100), "還沒好的裡面最早好的那一次");
        assertEquals(-1, SkillRuntime.earliestPending(new long[] {50, 90}, 100));
    }

    /** 2026-11-01 照 PoB2 altQualityStats：高階奇術的額外品質效果（gem_quality.yml 的 advanced），而且真的加在增益上。 */
    @Test
    void advancedQualityFollowsPob() {
        // 銀月庇：一般品質每 1% 獲得 0.5% 傷害為冰冷、額外品質每 1% 增加 2% 冷卻回復速度
        assertEquals(10, sum(tw.exilecore.currency.GemQuality.modifiers("silver_moon_shelter", 20, "q"),
                tw.exilecore.stats.StatKeys.GRANT_PREFIX + tw.exilecore.stats.StatKeys.damageExtra(tw.exilecore.stats.DamageType.COLD)), 1e-9);
        assertEquals(0.4, sum(tw.exilecore.currency.GemQuality.advancedModifiers("silver_moon_shelter", 20, "q"),
                tw.exilecore.stats.StatKeys.COOLDOWN_RECOVERY), 1e-9);
        // 拾鱗：每層甲片 4% → 5%（20% 品質）
        final SkillDefinition plating = config.skill("gathered_scales");
        final SkillBuild plated = SkillBuild.of(config, plating, 20, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                tw.exilecore.ascendancy.AscBuild.advanced("gathered_scales", 20, List.of()));
        assertEquals(5, sum(plated.auraModifiers(), PersistentBuffs.PLATING_ARMOUR), 1e-9);
        // 調藥心得：生命藥劑效果中攻擊速度、魔力藥劑效果中施法速度各增加 20%（條件交給屬性表判斷）
        final SkillBuild boon = SkillBuild.of(config, config.skill("potion_craft_insight"), 20, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                tw.exilecore.ascendancy.AscBuild.advanced("potion_craft_insight", 20, List.of()));
        assertEquals(0.2, sum(boon.auraModifiers(), "attack.speed@lifeflask"), 1e-9);
        assertEquals(0.2, sum(boon.auraModifiers(), "cast.speed@manaflask"), 1e-9);
        assertNotNull(SkillService.advancedQualityText(plating, 20));
        assertNotNull(SkillService.advancedQualityText(config.skill("potion_craft_insight"), 20));
        assertTrue(tw.exilecore.currency.GemQuality.advancedModifiers("breeze_dance_step", 20, "q") == null, "Wind Dancer 原作沒有額外品質");
    }

    private static double levelValue(final String skillId, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skillId)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return Double.NaN;
    }
}
