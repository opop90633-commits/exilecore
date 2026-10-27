package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeMechanicKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照 PoE2「圖騰是你的友軍」：「你存在範圍內的友軍…」也給圖騰；「召喚物…」、「夥伴…」不給。 */
class TotemAlliesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static Modifier mod(final String stat, final ModType type, final double value, final String... tags) {
        return new Modifier(stat, type, value, Set.of(tags), "測試");
    }

    private static StatSheet owner() {
        return new StatSheet()
                .add(mod(StatKeys.DAMAGE, ModType.INCREASED, 0.20, Tags.MINION, TreeMechanicKeys.IN_PRESENCE))      // 友軍傷害
                .add(mod(StatKeys.ATTACK_SPEED, ModType.INCREASED, 0.50, Tags.MINION, TreeMechanicKeys.IN_PRESENCE)) // 友軍攻擊速度
                .add(mod(StatKeys.RESIST_FIRE, ModType.FLAT, 30, Tags.MINION, TreeMechanicKeys.IN_PRESENCE))          // 友軍抗性
                .add(mod(StatKeys.LIFE_REGEN, ModType.FLAT, 10, Tags.MINION, TreeMechanicKeys.IN_PRESENCE))
                .add(mod(StatKeys.LIFE_REGEN_PERCENT, ModType.FLAT, 2, Tags.MINION, TreeMechanicKeys.IN_PRESENCE))  // 你最大生命的 2%
                .add(mod(StatKeys.DAMAGE, ModType.INCREASED, 1.00, Tags.MINION))                                     // 召喚物傷害：不給圖騰
                .add(mod(StatKeys.DAMAGE, ModType.INCREASED, 1.00, Tags.MINION, TreeMechanicKeys.COMPANION, TreeMechanicKeys.IN_PRESENCE)) // 夥伴
                .add(mod(StatKeys.DAMAGE, ModType.INCREASED, 0.10));                                                 // 你自己的
    }

    @Test
    void onlyAlliesInPresenceReachTotems() {
        final List<Modifier> allies = TotemAllies.fromOwner(owner());
        assertEquals(5, allies.size(), "只有「存在範圍內的友軍」五條；召喚物、夥伴、你自己的不算");
        for (final Modifier modifier : allies) {
            assertTrue(modifier.tags().isEmpty(), "拿掉 minion、in_presence 標籤");
        }
        final List<Modifier> offence = TotemAllies.offence(allies);
        assertEquals(2, offence.size(), "傷害、攻擊速度疊在技能上");
        final double[] resist = TotemAllies.resists(allies);
        assertEquals(30, resist[0], 1e-9);
        assertEquals(0, resist[3], 1e-9);
        assertEquals(10 + 1000 * 0.02, TotemAllies.regen(allies, 1000), 1e-9, "固定 10 + 你最大生命 1000 的 2%");
    }

    @Test
    void totemSkillGetsAllyOffenceOnTopOfYourSheet() {
        final StatSheet sheet = owner();
        final SkillBuild ballista = SkillBuild.of(config, config.skill("siege_ballista"), 20, 60, false, List.of(),
                Set.of(Tags.CROSSBOW), sheet);
        final SkillBuild boosted = TotemAllies.withAllies(ballista, TotemAllies.offence(TotemAllies.fromOwner(sheet)));
        final Set<String> tags = ballista.profile().tags();
        final double before = ballista.profile().effective(sheet).increased(StatKeys.DAMAGE, tags);
        final double after = boosted.profile().effective(sheet).increased(StatKeys.DAMAGE, tags);
        assertEquals(0.20, after - before, 1e-9, "多了友軍的 20%（你自己的 10% 本來就有，不重複；召喚物的 100% 不給）");
        assertTrue(SkillDeployables.totemSpeed(boosted, sheet, false) > SkillDeployables.totemSpeed(ballista, sheet, false),
                "友軍攻擊速度讓圖騰射得比較快");
        assertTrue(TotemAllies.withAllies(ballista, List.of()) == ballista, "不在範圍內：照原本的");
    }

    @Test
    void allyResistAddsToTotemResistUpToItsCap() {
        final DamagePacket fire = new DamagePacket();
        fire.set(DamageType.FIRE, 100);
        assertEquals(60, SkillDeployables.totemTaken(40, 0, 0, 0, null, 75, fire), 1e-9, "沒有友軍抗性：40%");
        assertEquals(30, SkillDeployables.totemTaken(40, 0, 0, 0, new double[] {30, 0, 0, 0}, 75, fire), 1e-9, "加 30% = 70%");
        assertEquals(25, SkillDeployables.totemTaken(60, 0, 0, 0, new double[] {30, 0, 0, 0}, 75, fire), 1e-9, "夾在上限 75%");
        assertEquals(20, SkillDeployables.totemTaken(60, 0, 0, 0, new double[] {30, 0, 0, 0}, 80, fire), 1e-9, "上限 80%");
        final DamagePacket cold = new DamagePacket();
        cold.set(DamageType.COLD, 100);
        assertEquals(60, SkillDeployables.totemTaken(40, 0, 0, 0, new double[] {30, 0, 0, 0}, 75, cold), 1e-9, "火焰抗性不擋冰冷");
        final DamagePacket chaos = new DamagePacket();
        chaos.set(DamageType.CHAOS, 100);
        assertEquals(25, SkillDeployables.totemTaken(40, 70, 0, 0, new double[] {0, 0, 0, 30}, 75, chaos), 1e-9, "混沌夾 75%");
    }

    @Test
    void allyLinesInDataAreTaggedInPresence() throws IOException {
        final String tree = Files.readString(Path.of("src/main/resources/data/passives/tree.json"));
        // 巨量恢復、放棄你的精髓：「你存在範圍內的友軍每秒再生你最大生命的 x%」
        assertTrue(tree.contains("\"life.regen_percent\",\"type\":\"FLAT\",\"value\":1.0,\"tags\":[\"minion\",\"in_presence\"]"));
        assertTrue(tree.contains("\"life.regen_percent\",\"type\":\"FLAT\",\"value\":2.0,\"tags\":[\"minion\",\"in_presence\"]"));
        final tw.exilecore.item.ItemRepository items = new tw.exilecore.item.ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        for (final String id : List.of("minion_inc", "minion_added_cold", "minion_crit", "minion_crit_bonus", "minion_speed",
                "minion_resist_all", "minion_regen", "corrupt_sceptre_minion_damage")) {
            final tw.exilecore.item.Affix affix = items.affix(id);
            assertTrue(affix.text().contains("你存在範圍內的友軍"), id);
            affix.mods().forEach(mod -> assertTrue(mod.tags().contains(TreeMechanicKeys.IN_PRESENCE), id + " " + mod.stat()));
        }
    }

    @Test
    void describeSaysAlliesInPresence() {
        assertEquals("你存在範圍內的友軍：增加 8% 傷害",
                ModifierText.describe(mod(StatKeys.DAMAGE, ModType.INCREASED, 0.08, Tags.MINION, TreeMechanicKeys.IN_PRESENCE)));
        assertFalse(ModifierText.describe(mod(StatKeys.DAMAGE, ModType.INCREASED, 0.08, Tags.MINION)).contains("友軍"));
    }
}
