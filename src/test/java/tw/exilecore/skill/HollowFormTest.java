package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Hollow Form：空無型態是主要寶石（孔裡放近戰攻擊、按住引導顯現幻影）。 */
class HollowFormTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition definition = config.skill(id);
        assertNotNull(definition, id);
        return definition;
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition definition = config.support(id);
        assertNotNull(definition, id);
        return definition;
    }

    @Test
    void hostIsAChannelledMetaThatTakesSkillGems() {
        final SkillDefinition form = skill(HollowForm.ID);
        assertTrue(HollowForm.hosts(form));
        assertTrue(SupportDefinition.hostsSkill(form), "孔裡放技能寶石");
        assertTrue(form.tags().contains(Tags.META));
        assertTrue(form.tags().contains("channelling"));
        assertTrue(form.tags().contains(Tags.MELEE));
        assertEquals(Mechanic.META, form.mechanic());
        assertNull(config.meta(HollowForm.ID), "不是能量型的主要寶石");
        final ChargeSpec spec = config.charge(HollowForm.ID);
        assertNotNull(spec, "按住引導");
        assertEquals(ChargeSpec.Mode.STREAM, spec.mode());
        assertFalse(spec.drain(), "消耗是每個幻影花孔裡攻擊的 80%，不逐秒扣");
        // 2026-10-29 照 PoE2（Any Melee Martial Weapon）：徒手、權杖不行；這次之前徒手也行
        assertFalse(form.usableWith(Set.of()), "徒手不行");
        assertFalse(form.usableWith(Set.of("weapon", "sceptre", "caster", Tags.MELEE)), "權杖不是武鬥武器");
        assertTrue(form.usableWith(Set.of("weapon", Tags.QUARTERSTAFF, Tags.MELEE)));
        assertTrue(form.usableWith(Set.of("weapon", Tags.MACE, Tags.MELEE)), "錘也行");
        assertFalse(form.usableWith(Set.of(Tags.BOW, Tags.RANGED)), "要近戰武器");
        assertEquals(0, form.cooldown(), 1e-9);
    }

    @Test
    void socketTakesMeleeAttacksButNotChannelledOrConditional() {
        for (final String id : List.of("spine_crush", "frost_shockwave", "gale_combo", "startle_thunder_chop", "soul_sever_palm", "ground_tremor",
                "field_quake_hammers", "returning_smith_hammer", "crisscross_claw")) {
            assertNull(HollowForm.problem(skill(id)), id);
        }
        assertNotNull(HollowForm.problem(skill("startling_thunder_bell")), "要連擊（有使用條件）");
        assertNotNull(HollowForm.problem(skill("heaven_punish_maul")), "要榮耀（有使用條件）");
        for (final String id : List.of("shield_drum", "molten_crack_stomp", "charged_blow", "thousand_weight_smash", "iron_wall_ram", "brute_charge")) {
            assertNotNull(HollowForm.problem(skill(id)), id + "：引導");
        }
        assertNotNull(HollowForm.problem(skill("blazing_star_shell")), "法術不行");
        assertNotNull(HollowForm.problem(skill("thunderclap_arrow")), "遠程不行");
        assertNotNull(HollowForm.problem(skill("soaring_smash")), "跳躍不行");
        assertNotNull(HollowForm.problem(skill("instant_shadow_strike")), "瞬影不行");
        assertNotNull(HollowForm.problem(skill("hero_spirit_totem")), "圖騰不行");
        assertNotNull(HollowForm.problem(skill(HollowForm.ID)), "主要寶石不行");
        assertNotNull(HollowForm.problem(skill(DeadeyeMetas.MIRAGE)), "主要寶石不行");
        assertNotNull(HollowForm.problem(skill("asc_hollow_bell")), "隱藏技能不行");
        // 空無型態也不能放進別的主要寶石
        assertNotNull(DeadeyeMetas.problem(skill(DeadeyeMetas.MIRAGE), skill(HollowForm.ID)));
    }

    @Test
    void refNumbers() {
        assertEquals(15, HollowForm.LESS_DAMAGE, 1e-9);
        assertEquals(30, HollowForm.LESS_ATTACK_SPEED, 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(6), HollowForm.RADIUS, 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        // 每個幻影花 80% 的消耗
        assertEquals(8, HollowForm.imageCost(10));
        assertEquals(4, HollowForm.imageCost(5));
        assertEquals(0, HollowForm.imageCost(0));
        assertEquals(80, HollowForm.imageCost(100));
        // 銳星：多 2 個（倍星讓效果加倍時多 4 個）
        assertEquals(1, HollowForm.images(0));
        assertEquals(3, HollowForm.images(1));
        assertEquals(5, HollowForm.images(2));
        // 攻擊速度更少 30%：1 秒的攻擊 → 每 1/0.7 秒一個幻影；前搖 30%
        assertEquals(20 / 0.7, HollowForm.stepTicks(20), 1e-9);
        assertEquals(Math.round(20 / 0.7 * 0.3), HollowForm.startTicks(HollowForm.stepTicks(20)));
    }

    @Test
    void imageHitsDealLessAndCannotConsumeCharges() {
        final SkillBuild attack = SkillBuild.of(config, skill("spine_crush"), 20, 90, false, List.of(), Set.of(Tags.MACE, Tags.MELEE), new StatSheet());
        final SkillBuild image = HollowForm.imageBuild(attack);
        final StatSheet effective = image.profile().effective(new StatSheet());
        assertTrue(effective.flat(ChargeService.CANNOT_CONSUME, image.profile().tags()) > 0, "不能消耗戰星");
        assertEquals(0, attack.profile().effective(new StatSheet()).flat(ChargeService.CANNOT_CONSUME, attack.profile().tags()), 1e-9);
        boolean less = false;
        for (final Modifier modifier : image.profile().local()) {
            if (modifier.stat().equals(StatKeys.DAMAGE) && modifier.type() == ModType.MORE) {
                assertEquals(-0.15, modifier.value(), 1e-9);
                less = true;
            }
        }
        assertTrue(less, "傷害更少 15%");
        assertEquals(attack.manaCost(), image.manaCost(), "消耗另外算（80%）");
    }

    @Test
    void chargeSupportsWorkOnTheHostButEnergySupportsDoNot() {
        final SkillDefinition form = skill(HollowForm.ID);
        assertTrue(HollowForm.appliesToHost(support("eternal_star")));
        assertTrue(HollowForm.appliesToHost(support("double_star")));
        assertFalse(HollowForm.appliesToHost(support("quick_strike")));
        assertNull(support("eternal_star").hostRefusal(form, skill("spine_crush")));
        assertNull(support("eternal_star").hostRefusal(form, null), "孔裡沒放也能掛（套在虛空形態自己身上）");
        assertTrue(SupportDefinition.consumes(form, "power"), "虛空形態會消耗銳星");
        for (final SupportDefinition support : config.supports()) {
            boolean energy = false;
            for (final Modifier modifier : support.modifiers()) {
                energy |= modifier.stat().startsWith("meta.energy");
            }
            if (energy) {
                assertNotNull(support.hostRefusal(form, skill("spine_crush")), support.id() + "：虛空形態沒有能量");
            }
        }
    }
}
