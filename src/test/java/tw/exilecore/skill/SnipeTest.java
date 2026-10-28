package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Snipe（穿雲箭）：標籤、攻擊速度影響每秒消耗、爆炸／寒冰爆破的傷害表與半徑、品質。 */
class SnipeTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String id, final int level, final StatSheet sheet, final List<Modifier> extra) {
        return SkillBuild.of(config, config.skill(id), level, 100, false, List.of(), Set.of(Tags.BOW), sheet, Set.of(), extra);
    }

    @Test
    void tagsAndCostFollowPoe2() {
        final SkillDefinition snipe = config.skill("snipe");
        assertTrue(snipe.tags().containsAll(Set.of(Tags.ATTACK, Tags.AREA, Tags.PROJECTILE, Tags.COLD, "channelling")));
        assertEquals(0.45, snipe.attackSpeed(), 1e-9);
        assertEquals(18, snipe.mana().at(1), 1e-9, "每秒 17.55");
        assertEquals(119, snipe.mana().at(20), 1e-9, "每秒 118.57");
        assertEquals(462, snipe.mana().at(40), 1e-9);
        assertTrue(config.charge("snipe").channel().attackSpeedCost());
    }

    @Test
    void attackSpeedScalesCostPerSecond() {
        assertEquals(179, ChargeSpec.scaledCost(119, 1.5));
        assertEquals(119, ChargeSpec.scaledCost(119, 1.0));
        assertEquals(1, ChargeSpec.scaledCost(1, 0.2), "至少 1");
        final ChargeSpec spec = config.charge("snipe");
        final SkillBuild plain = build("snipe", 20, new StatSheet(), List.of());
        final StatSheet fast = new StatSheet().add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.5, "測試"));
        final SkillBuild quick = build("snipe", 20, fast, List.of());
        assertEquals(plain.manaCost(), spec.drainCost(plain, new StatSheet()));
        assertEquals(ChargeSpec.scaledCost(quick.manaCost(), 1.5), spec.drainCost(quick, fast), "增加 50% 攻擊速度：每秒消耗 ×1.5");
        // 其他引導技能不受影響
        final ChargeSpec strike = config.charge("true_strike");
        final SkillBuild trueStrike = SkillBuild.of(config, config.skill("true_strike"), 20, 100, false, List.of(), Set.of(Tags.MACE), fast);
        assertEquals(trueStrike.manaCost(), strike.drainCost(trueStrike, fast));
    }

    @Test
    void explosionAndIcyBlastUseTheirOwnTables() {
        final SkillDefinition snipe = config.skill("snipe");
        assertEquals(4.0, config.parentShare("proc_snipe_blast", snipe, 1), 1e-9, "400% ÷ 100%");
        assertEquals(14.49 / 3.12, config.parentShare("proc_snipe_blast", snipe, 20), 1e-9);
        assertEquals(6.3, config.parentShare("proc_snipe_ice_blast", snipe, 1), 1e-9, "630% ÷ 100%");
        assertEquals(30.67 / 3.12, config.parentShare("proc_snipe_ice_blast", snipe, 20), 1e-9, "3067% ÷ 312%");
        assertEquals(206.36 / 8.28, config.parentShare("proc_snipe_ice_blast", snipe, 40), 1e-9);
        assertTrue(snipe.modifiers().stream().anyMatch(m -> m.stat().equals("proc.perfect_hit_frozen")
                && m.tags().contains("proc_snipe_ice_blast")), "完美時機直接打中凍結的敵人：寒冰爆破");
        assertTrue(snipe.modifiers().stream().anyMatch(m -> m.stat().equals("proc.perfect_hit")
                && m.tags().contains("proc_snipe_blast")));
        assertTrue(config.skill("proc_snipe_ice_blast").hidden());
    }

    @Test
    void icyBlastConvertsToColdAndHitsUniquesHarder() {
        final SkillBuild parent = build("snipe", 20, new StatSheet(), List.of());
        final SkillBuild sub = build("proc_snipe_ice_blast", 20, new StatSheet(), List.of());
        final SkillBuild ice = SupportProcs.ownSubBuild(sub, parent, config.parentShare("proc_snipe_ice_blast", config.skill("snipe"), 20), new StatSheet());
        final StatSheet effective = ice.profile().effective(new StatSheet());
        assertEquals(100, effective.flat(StatKeys.DAMAGE_CONVERT + "cold", ice.profile().tags()), 1e-9, "物理 100% 轉冰冷");
        assertEquals(1.5, effective.more(StatKeys.DAMAGE_VS_UNIQUE, ice.profile().tags()), 1e-9, "20 級對傳奇更多 50%");
        assertEquals(1.31, build("proc_snipe_ice_blast", 1, new StatSheet(), List.of()).profile().effective(new StatSheet())
                .more(StatKeys.DAMAGE_VS_UNIQUE, Set.of(Tags.ATTACK)), 1e-9, "1 級 31%");
        assertTrue(ice.profile().tags().contains(Tags.AREA));
        assertEquals(tw.exilecore.core.Units.metres(3.0), ice.radius(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals("proc_snipe_ice_blast", ice.profile().id());
        // 一般的爆炸不轉換
        final SkillBuild blast = SupportProcs.ownSubBuild(build("proc_snipe_blast", 20, new StatSheet(), List.of()), parent, 4, new StatSheet());
        assertEquals(0, blast.profile().effective(new StatSheet()).flat(StatKeys.DAMAGE_CONVERT + "cold", blast.profile().tags()), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.5), blast.radius(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertNotNull(DamageType.fromTag("cold"));
    }

    @Test
    void qualityWidensExplosionsAndAreaComesFromSnipe() {
        final SkillDefinition snipe = config.skill("snipe");
        final List<Modifier> quality = SkillService.qualityModifiers(snipe, 20);
        assertTrue(quality.stream().anyMatch(m -> m.stat().equals(SupportProcs.EXPLOSION_RADIUS_FLAT) && m.type() == ModType.FLAT
                && Math.abs(m.value() - 0.4) < 1e-9), "20% 品質：爆炸半徑 +0.4 格");
        final SkillBuild parent = build("snipe", 20, new StatSheet(), quality);
        final SkillBuild blast = SupportProcs.ownSubBuild(build("proc_snipe_blast", 20, new StatSheet(), List.of()), parent, 4, new StatSheet());
        assertEquals(tw.exilecore.core.Units.metres(2.5 + 0.4), blast.radius(), 1e-9); // 2026-10-29 照站長：基礎 2.5 公尺、品質 +0.4 公尺都換算成格
        final SkillBuild ice = SupportProcs.ownSubBuild(build("proc_snipe_ice_blast", 20, new StatSheet(), List.of()), parent, 6, new StatSheet());
        assertEquals(tw.exilecore.core.Units.metres(3.0 + 0.4), ice.radius(), 1e-9);
        // 穿雲箭上的增加範圍（輔助、角色）也加在爆炸上，不會重複算角色的
        final StatSheet wide = new StatSheet().add(Modifier.increased(StatKeys.SKILL_AREA, 0.44, "測試"));
        final SkillBuild wideBlast = SupportProcs.ownSubBuild(build("proc_snipe_blast", 20, wide, List.of()), build("snipe", 20, wide, List.of()), 4, wide);
        assertEquals(tw.exilecore.core.Units.metres(2.5) * 1.2, wideBlast.radius(), 1e-9, "增加 44% 範圍 = 半徑 ×1.2");
        assertEquals("品質 +20%：爆炸與寒冰爆破的半徑 +0.6 格", SkillService.qualityText(snipe, 20)); // 2026-10-29：+0.4 公尺 × 1.5
        // 額外的品質效果（進階奇術）：更多攻擊速度、更長的完美時機區間
        final List<Modifier> advanced = tw.exilecore.currency.GemQuality.advancedModifiers("snipe", 20, "測試");
        assertTrue(advanced.stream().anyMatch(m -> m.stat().equals(StatKeys.ATTACK_SPEED) && m.type() == ModType.MORE));
        assertTrue(advanced.stream().anyMatch(m -> m.stat().equals(tw.exilecore.item.unique.UniqueStats.PERFECT_WINDOW)
                && m.type() == ModType.INCREASED && Math.abs(m.value() - 0.2) < 1e-9));
    }
}
