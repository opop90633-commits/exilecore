package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三部分（四）光環與戰吼：設定、保留、效果隨等級與光環效果倍率、捷的隱藏技能、戰吼的增益與強化（純邏輯的部分）。 */
class AuraWarcryTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static SkillBuild build(final String skill, final int gemLevel, final StatSheet sheet, final SupportDefinition... supports) {
        return SkillBuild.of(config, config.skill(skill), gemLevel, 100, false, List.of(supports), Set.of(Tags.MACE), sheet);
    }

    @Test
    void aurasParseWithReservationAndScaledEffects() {
        final SkillDefinition hatred = config.skill("hatred");
        assertEquals(Mechanic.AURA, hatred.mechanic());
        assertNotNull(hatred.aura());
        assertEquals(35, hatred.aura().reserve(), 1e-9);
        assertTrue(hatred.aura().allies());
        assertFalse(hatred.aura().herald());
        assertTrue(hatred.tags().containsAll(Set.of(Tags.AURA, Tags.AREA, Tags.SPELL)));
        assertFalse(hatred.dealsDamage());
        assertNull(hatred.damage());
        assertEquals(0, hatred.mana().last(), 1e-9, "光環用保留的");
        assertEquals(2, hatred.aura().effects().size());
        final ScaledMod gain = hatred.aura().effects().getFirst();
        assertEquals(StatKeys.damageGain(tw.exilecore.stats.DamageType.COLD), gain.stat());
        assertEquals(18, gain.value().at(1), 1e-9);
        assertEquals(34, gain.value().at(20), 1e-9);

        final StatSheet owner = new StatSheet();
        final SkillBuild low = build("hatred", 1, owner);
        final SkillBuild high = build("hatred", 20, owner);
        assertEquals(35, low.reserve(), 1e-9);
        assertEquals(1.0, low.auraEffect(), 1e-9);
        assertEquals(18, low.auraModifiers().getFirst().value(), 1e-9);
        assertEquals(34, high.auraModifiers().getFirst().value(), 1e-9);
        assertEquals(0.16, high.auraModifiers().get(1).value(), 1e-9, "增加 16% 冰冷傷害（百分比換成小數）");
        assertEquals(Set.of(Tags.COLD), high.auraModifiers().get(1).tags());
        assertFalse(low.generosity());
        assertEquals(0, low.exerts());
        assertNull(low.deploy());
        assertFalse(low.echo());
    }

    @Test
    void auraEffectAndReservationScaleWithOwnerStats() {
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(StatKeys.AURA_EFFECT, ModType.INCREASED, 0.20, Set.of(), "領袖"));
        owner.add(new Modifier(StatKeys.AURA_RESERVATION, ModType.INCREASED, -0.10, Set.of(), "領袖"));
        final SkillBuild hatred = build("hatred", 20, owner);
        assertEquals(1.2, hatred.auraEffect(), 1e-9);
        assertEquals(34 * 1.2, hatred.auraModifiers().getFirst().value(), 1e-9, "每一條效果都乘光環效果");
        assertEquals(35 * 0.9, hatred.reserve(), 1e-9);

        final SkillBuild boosted = build("hatred", 20, owner, config.support("aura_effect"));
        assertEquals(1, boosted.supports().size());
        assertEquals(1.45, boosted.auraEffect(), 1e-9, "增加 20% + 25%（增加相加）");
        assertEquals(35 * (1 - 0.10 + 0.20), boosted.reserve(), 1e-9, "保留 -10% + 20%");

        final SkillBuild generous = build("hatred", 20, owner, config.support("generosity"));
        assertTrue(generous.generosity());
        assertFalse(config.support("aura_effect").supports(config.skill("fireball")), "強效光環只能掛光環");
        assertFalse(config.support("spell_echo").supports(config.skill("hatred")), "雙詠不能掛光環");
        assertFalse(config.support("spell_totem").supports(config.skill("hatred")));
        assertTrue(config.support("increased_area").supports(config.skill("hatred")), "擴散讓隊友的範圍更大");
    }

    @Test
    void heraldsPointAtHiddenSubSkillsThatTakeTheHeraldsSupports() {
        final SkillDefinition herald = config.skill("herald_of_ash");
        assertTrue(herald.aura().herald());
        assertEquals("herald_ash_burst", herald.aura().onKill());
        assertEquals(100, herald.aura().onKillChance(), 1e-9);
        assertFalse(herald.aura().allies(), "捷只給自己（與召喚物）");
        final SkillDefinition burst = config.skill("herald_ash_burst");
        assertNotNull(burst);
        assertTrue(burst.hidden());
        assertFalse(burst.isAttack());
        assertEquals(Mechanic.NOVA, burst.mechanic());
        assertNotNull(burst.damage());
        assertFalse(config.visible().contains(burst), "隱藏的不會出現在雕琢選單");
        // 捷的輔助用在它的效果上：擴散（範圍）、純化（元素）都掛得上隱藏的爆發；強效光環掛不上
        assertTrue(config.support("increased_area").supports(burst));
        assertTrue(config.support("elemental_focus").supports(burst));
        assertFalse(config.support("aura_effect").supports(burst));
        assertFalse(config.support("increased_area").supports(config.skill("default_melee")), "普攻還是不能掛輔助");
        final StatSheet owner = new StatSheet();
        final SkillBuild sub = SkillBuild.of(config, burst, 20, 100, false, List.of(config.support("increased_area")), Set.of(), owner);
        assertEquals(1, sub.supports().size());
        assertTrue(sub.radius() > burst.params().radius().at(20), "範圍變大");
        assertEquals(Mechanic.CHAIN, config.skill("herald_thunder_bolt").mechanic());
        assertEquals(Mechanic.NOVA, config.skill("herald_ice_shatter").mechanic());
    }

    @Test
    void warcriesParseWithBuffDebuffAndExerts() {
        final SkillDefinition rally = config.skill("rallying_cry");
        assertEquals(Mechanic.WARCRY, rally.mechanic());
        assertNotNull(rally.warcry());
        assertTrue(rally.tags().containsAll(Set.of(Tags.WARCRY, Tags.DURATION, Tags.AREA, Tags.COOLDOWN)));
        assertFalse(rally.tags().contains(Tags.SPELL), "戰吼不算法術（2026-10）");
        assertEquals(10, rally.cooldown(), 1e-9);
        assertEquals(3, rally.warcry().exerts());
        assertEquals(30, rally.warcry().exertMore().at(1), 1e-9);
        assertEquals(50, rally.warcry().exertMore().at(20), 1e-9);
        assertFalse(rally.warcry().taunt());
        assertEquals(0, rally.warcry().taken().last(), 1e-9);

        final StatSheet owner = new StatSheet();
        final SkillBuild build = build("rallying_cry", 20, owner);
        assertEquals(3, build.exerts());
        final List<Modifier> buff = build.warcryModifiers();
        assertEquals(2, buff.size());
        assertEquals(StatKeys.DAMAGE, buff.getFirst().stat());
        assertEquals(0.30, buff.getFirst().value(), 1e-9);
        assertEquals(8 * 20, build.durationTicks(rally.warcry().duration()));

        final SkillBuild strong = build("rallying_cry", 20, owner, config.support("warcry_power"), config.support("increased_duration"));
        assertEquals(5, strong.exerts(), "強勢戰吼 +2 次");
        assertTrue(strong.cooldownTicks() > build.cooldownTicks(), "冷卻變長");
        assertTrue(strong.durationTicks(rally.warcry().duration()) > build.durationTicks(rally.warcry().duration()), "持久撐更久");
        owner.add(new Modifier(StatKeys.WARCRY_EXERTS, ModType.FLAT, 1, Set.of(Tags.WARCRY), "戰場號令"));
        owner.add(new Modifier(StatKeys.SKILL_COOLDOWN, ModType.INCREASED, -0.15, Set.of(Tags.WARCRY), "戰場號令"));
        final SkillBuild tree = build("rallying_cry", 20, owner);
        assertEquals(4, tree.exerts());
        assertEquals(Math.round(10 * 20 * 0.85), tree.cooldownTicks());
        assertEquals(0, build("heavy_strike", 20, owner).cooldownTicks(), "戰吼的冷卻減少不影響別的技能");

        final SkillDefinition enduring = config.skill("enduring_cry");
        assertEquals(8, enduring.warcry().heal().at(1), 1e-9);
        assertEquals(15, enduring.warcry().heal().at(20), 1e-9);
        assertTrue(enduring.warcry().taunt());
        final SkillDefinition intimidating = config.skill("intimidating_cry");
        assertEquals(12, intimidating.warcry().taken().at(1), 1e-9);
        assertEquals(25, intimidating.warcry().taken().at(20), 1e-9);
        assertEquals(2, intimidating.warcry().exerts());
        assertFalse(config.support("warcry_power").supports(config.skill("fireball")));
        assertTrue(config.support("cooldown_recovery").supports(rally));
        // 戰吼不算法術（2026-10）：法術的輔助寶石掛不上去，原因講清楚
        assertFalse(config.support("faster_casting").supports(rally), "疾詠要法術");
        assertEquals("要法術技能", config.support("faster_casting").refusal(rally));
        assertFalse(config.support("added_fire").supports(rally), "引火（攻擊或法術）也不行");
        assertEquals("戰吼技能不行", config.support("power_on_hit").refusal(rally));
        assertNull(config.support("warcry_power").refusal(rally));
    }

    @Test
    void exertedAttacksMultiplyTheProfileAndCountDown() {
        final StatSheet owner = new StatSheet();
        final SkillBuild strike = build("heavy_strike", 20, owner);
        SkillRuntime.Exert exert = new SkillRuntime.Exert(2, 50, 100);
        assertTrue(exert.active(10));
        assertFalse(exert.active(100), "時間到了就沒了");
        final SkillBuild exerted = strike.withProfile(strike.profile().scaled(1 + exert.more() / 100.0, "戰吼強化"));
        assertEquals(strike.definition(), exerted.definition());
        assertEquals(1, exerted.profile().local().size() - strike.profile().local().size(), "多一條更多 50% 傷害");
        assertEquals(0.5, exerted.profile().local().getLast().value(), 1e-9);
        assertEquals(ModType.MORE, exerted.profile().local().getLast().type());
        exert = exert.consume();
        assertNotNull(exert);
        assertEquals(1, exert.left());
        assertNull(exert.consume(), "用完就沒了");
    }

    @Test
    void scaledModAndModifierText() {
        assertEquals(List.of("附加 4–22 火焰傷害", "增加 16% 冰冷傷害"), ModifierText.describeAll(List.of(
                new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 4, Set.of(Tags.FIRE), ""),
                new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 22, Set.of(Tags.FIRE), ""),
                new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.16, Set.of(Tags.COLD), ""))));
        assertEquals("增加 25% 光環效果", ModifierText.describe(new Modifier(StatKeys.AURA_EFFECT, ModType.INCREASED, 0.25, Set.of(), "")));
        assertEquals("減少 10% 光環的魔力保留", ModifierText.describe(new Modifier(StatKeys.AURA_RESERVATION, ModType.INCREASED, -0.10, Set.of(), "")));
        assertEquals("戰吼多強化 2 次攻擊", ModifierText.describe(new Modifier(StatKeys.WARCRY_EXERTS, ModType.FLAT, 2, Set.of(), "")));
        assertEquals("增加 45% 護甲", ModifierText.describe(new Modifier(StatKeys.ARMOUR, ModType.INCREASED, 0.45, Set.of(), "")));
        assertEquals("+16% 火焰抗性", ModifierText.describe(new Modifier(StatKeys.RESIST_FIRE, ModType.FLAT, 16, Set.of(), "")));
    }

    @Test
    void brokenAuraAndWarcryBlocksAreRejected() throws IOException {
        final Path dir = Files.createTempDirectory("exilecore-aura");
        Files.copy(DATA.resolve("supports.yml"), dir.resolve("supports.yml"));
        final String original = Files.readString(DATA.resolve("skills.yml"));
        assertRejected(dir, original.replace("on_kill: { skill: herald_ash_burst, chance: 100 }", "on_kill: { skill: nothing_here, chance: 100 }"), "on_kill");
        assertRejected(dir, original.replace("on_kill: { skill: herald_ash_burst, chance: 100 }", "on_kill: { skill: fireball, chance: 100 }"), "hidden");
        assertRejected(dir, original.replace("    cooldown: 10\n    mana: [7, 16]", "    mana: [7, 16]"), "cooldown");
        assertRejected(dir, original.replace("      reserve: 35\n      radius: 8\n      effects:\n        - { stat: damage.gain.cold",
                "      reserve: 135\n      radius: 8\n      effects:\n        - { stat: damage.gain.cold"), "reserve");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        assertFalse(content.equals(Files.readString(DATA.resolve("skills.yml"))), "測試要真的改到東西：" + mention);
        Files.writeString(dir.resolve("skills.yml"), content);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }
}
