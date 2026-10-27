package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.WarcryKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照 PoE2：裂甲熊咆插人形戰吼（Ferocious Roar 的 Socketed Warcry）、各戰吼的品質效果。 */
class SocketedWarcryTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
    }

    private static double flat(final SkillBuild build, final String stat) {
        return build.profile().effective(new StatSheet()).flat(stat, build.profile().tags());
    }

    private static SkillBuild withQuality(final String skill, final int quality, final List<Modifier> more) {
        final SkillDefinition definition = config.skill(skill);
        // 2026-10-27：測試照有進階奇術算（一般品質＋額外的品質效果；這次之前額外的那幾條算在一般品質裡）
        final List<Modifier> normal = SkillService.qualityModifiers(definition, quality);
        final List<Modifier> extra = new java.util.ArrayList<>(GemQuality.hasAdvanced(skill)
                ? tw.exilecore.ascendancy.AscBuild.advanced(skill, quality, normal) : normal);
        extra.addAll(more);
        return SkillBuild.of(config, definition, 20, 90, false, List.of(), Set.of(Tags.MELEE, Tags.MACE), new StatSheet(), Set.of(), extra);
    }

    @Test
    void ferociousRoarTakesOnlyHumanFormWarcries() {
        final SkillDefinition roar = config.skill("ferocious_roar");
        assertTrue(SocketedWarcry.hosts(roar));
        assertFalse(SupportDefinition.hostsSkill(roar), "不是主要寶石那種宿主：沒放戰吼時輔助照樣給熊咆自己");
        for (final String id : List.of("infernal_cry", "seismic_cry", "fortifying_cry", "ancestral_cry")) {
            assertNull(SocketedWarcry.problem(config.skill(id)), id);
            assertFalse(SocketedWarcry.hosts(config.skill(id)), id);
        }
        // 原作 excluded_types：Shapeshift、Meta
        assertNotNull(SocketedWarcry.problem(config.skill("arctic_howl")), "變身的戰吼不行");
        assertNotNull(SocketedWarcry.problem(roar), "熊咆自己不行");
        assertNotNull(SocketedWarcry.problem(config.skill("lunar_blessing")), "銀月庇是變身技能");
        assertNotNull(SocketedWarcry.problem(config.skill("fireball")), "法術不行");
        assertNotNull(SocketedWarcry.problem(config.skill("war_banner")), "戰旗不是戰吼");
        assertNotNull(SocketedWarcry.problem(config.skill("infernal_combust")), "隱藏技能不行");
    }

    @Test
    void socketedWarcryNumbersFollowPoe2() {
        assertEquals(30, SocketedWarcry.damage(1), 1e-9);
        assertEquals(87, SocketedWarcry.damage(20), 1e-9);
        assertEquals(132, SocketedWarcry.damage(40), 1e-9);
        assertEquals(1.0, SocketedWarcry.costMultiplier(1), 1e-9);
        assertEquals(2.0, SocketedWarcry.costMultiplier(20), 1e-9);
        final List<Modifier> roarQuality = tw.exilecore.ascendancy.AscBuild.advanced("ferocious_roar", 20,
                SkillService.qualityModifiers(config.skill("ferocious_roar"), 20)); // 2026-10-27：插槽戰吼的冷卻回復是額外的品質效果（進階奇術）
        final List<Modifier> socket = SocketedWarcry.modifiers(20, roarQuality);
        final SkillBuild plain = withQuality("seismic_cry", 0, List.of());
        final SkillBuild socketed = withQuality("seismic_cry", 0, socket);
        assertEquals(plain.areaScale() * Math.sqrt(1.5), socketed.areaScale(), 1e-9, "範圍增加 50%");
        assertEquals(87 + 30, flat(socketed, WarcryKeys.EMPOWERED_DAMAGE), 1e-9, "被強化的攻擊增加 87%＋品質 30%");
        assertEquals(Math.round(plain.cooldownTicks() / 1.4), socketed.cooldownTicks(), 1, "熊咆品質：插槽戰吼冷卻回復速度增加 40%");
        assertEquals(1.87, socketed.profile().effective(new StatSheet()).apply(StatKeys.DAMAGE, 1.0, socketed.profile().tags()), 1e-9);
        final List<Modifier> empowered = Warcries.empoweredExtra(socketed.profile().effective(new StatSheet()), socketed.profile().tags(), "震地戰吼");
        assertEquals(1, empowered.size());
        assertEquals(1.17, empowered.getFirst().value(), 1e-9);
        assertEquals(SkillService.socketedCryCost(withQuality("ferocious_roar", 0, List.of()), socketed), socketed.manaCost() * 2);
    }

    @Test
    void roarSocketSavesLikeOtherHosts() {
        // 存檔格式跟英靈圖騰、主要寶石一樣（@代號/等級/孔數/品質）；舊存檔沒放就是熊咆自己
        final SkillLoadout.Entry entry = SkillLoadout.Entry.decode("ferocious_roar", 12, "3:@infernal_cry/10/2/q5,warcry_power,");
        assertNotNull(entry.inner());
        assertEquals("infernal_cry", entry.inner().skillId());
        assertEquals(5, entry.inner().quality());
        assertEquals(List.of("warcry_power"), entry.supportIds());
        assertEquals(1, entry.firstSupportSocket());
        assertEquals(entry, SkillLoadout.Entry.decode("ferocious_roar", 12, entry.encodeSupports()));
        final SkillLoadout.Entry old = SkillLoadout.Entry.decode("ferocious_roar", 12, "2:warcry_power,");
        assertNull(old.inner());
        assertEquals(List.of("warcry_power"), old.supportIds());
    }

    @Test
    void warcryQualityFollowsPoe2() {
        for (final String id : List.of("infernal_cry", "seismic_cry", "fortifying_cry", "ancestral_cry", "arctic_howl", "ferocious_roar", "lunar_blessing")) {
            assertTrue(GemQuality.has(id), id);
            final String text = SkillService.qualityText(config.skill(id), 20);
            assertNotNull(text, id);
            assertFalse(text.contains("戰吼效果"), id + "：不再是通用的戰吼效果");
        }
        assertFalse(GemQuality.has("shrine_prayer"), "神龕祝禱原作沒有：照通用規則");
        // 焚獄：強化攻擊 +10% 額外火焰、半徑 +3 格
        final SkillBuild infernal = withQuality("infernal_cry", 20, List.of());
        assertEquals(10, Warcries.empoweredExtra(infernal.profile().effective(new StatSheet()), infernal.profile().tags(), "x").getFirst().value(), 1e-9);
        assertEquals(StatKeys.damageExtra(DamageType.FIRE), Warcries.empoweredExtra(infernal.profile().effective(new StatSheet()), infernal.profile().tags(), "x").getFirst().stat());
        assertEquals(7, Warcries.baseRadius(infernal.definition().warcry(), infernal.profile().effective(new StatSheet()), infernal.profile().tags()), 1e-9);
        assertEquals(4, Warcries.baseRadius(infernal.definition().warcry(), withQuality("infernal_cry", 0, List.of()).profile().effective(new StatSheet()),
                Set.of()), 1e-9);
        // 震地：餘震更多 10%、物理的 20% 獲得為火焰
        final SkillBuild seismic = withQuality("seismic_cry", 20, List.of());
        assertEquals(10, flat(seismic, WarcryKeys.AFTERSHOCK_MORE), 1e-9);
        assertEquals(20, flat(seismic, StatKeys.DAMAGE_GAIN + "fire"), 1e-9);
        // 堅壁：守護更多 20%、盾波 2 格 → 2.4 格
        final SkillBuild fortifying = withQuality("fortifying_cry", 20, List.of());
        assertEquals(20, flat(fortifying, WarcryKeys.GUARD_MORE), 1e-9);
        assertEquals(1.2, SupportMechanics.triggerRadiusScale(config.skill("fortifying_shield_wave"), 20, flat(fortifying, WarcryKeys.TRIGGER_RADIUS_FLAT)), 1e-9);
        // 熔魂嘯：每顆磐星 +20% 持續時間、增益更多 10% 火焰傷害
        final SkillBuild ancestral = withQuality("ancestral_cry", 20, List.of());
        assertEquals(20, flat(ancestral, WarcryKeys.DURATION_PER_ENDURANCE), 1e-9);
        assertTrue(ancestral.warcryOwnModifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && Math.abs(m.value() - 0.1) < 1e-9
                && m.tags().contains(Tags.FIRE)), "增益給更多火焰傷害");
        // 寒夜嗥：隊友 +20% 凍結累積、20% 機率不算次數
        final SkillBuild arctic = withQuality("arctic_howl", 20, List.of());
        assertEquals(20, flat(arctic, WarcryKeys.ALLY_FREEZE_BUILDUP), 1e-9);
        assertEquals(20, flat(arctic, tw.exilecore.stats.SupportKeys.WARCRY_TIRELESS), 1e-9);
        // 裂甲熊咆：每點強度 +1 怒氣
        assertEquals(1, flat(withQuality("ferocious_roar", 20, List.of()), WarcryKeys.RAGE_PER_POWER), 1e-9);
        // 銀月庇：增益多 10% 冰冷、冷卻回復 +40%
        final SkillBuild lunar = withQuality("lunar_blessing", 20, List.of());
        assertTrue(lunar.warcryOwnModifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.damageExtra(DamageType.COLD)) && Math.abs(m.value() - 10) < 1e-9));
        assertEquals(Math.round(22 * 20 / 1.4), lunar.cooldownTicks(), 1);
    }
}
