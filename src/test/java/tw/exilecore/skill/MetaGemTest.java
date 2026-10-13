package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.charge.ChargeText;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主要寶石（2026-10，照 PoE2 的 Meta Gem）與新的戰星來源：設定讀得進來、插槽能插什麼、能量上限、說明文字。
 */
class MetaGemTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    @Test
    void metaGemsLoad() {
        final MetaSpec totem = config.meta("totem_casting");
        assertNotNull(totem);
        assertTrue(totem.totem());
        assertEquals(Mechanic.META, config.skill("totem_casting").mechanic());
        assertEquals(3, totem.charges());
        assertEquals(1, totem.totems().intAt(1));
        assertEquals(1, totem.totems().intAt(14), "14 級還是一根");
        assertEquals(2, totem.totems().intAt(15));
        for (final String id : List.of("meta_crit", "meta_ailment", "meta_dodge", "meta_damage_taken", "meta_minion_death", "meta_melee_kill")) {
            final MetaSpec spec = config.meta(id);
            assertNotNull(spec, id);
            assertEquals(Mechanic.AURA, config.skill(id).mechanic(), id + " 是常駐的（保留精魂）");
            assertTrue(config.skill(id).aura().reserve() > 0, id);
            assertTrue(spec.energy() > 0, id);
        }
        assertEquals(MetaSpec.Kind.CRIT, config.meta("meta_crit").kind());
        assertEquals(20, config.meta("meta_crit").lessDamage(), 1e-9);
        assertEquals(100, config.skill("meta_crit").aura().reserve(), 1e-9, "照 PoE2：暴擊時施放保留 100 精魂");
        assertEquals(30, config.skill("meta_minion_death").aura().reserve(), 1e-9);
        assertNull(config.meta("fireball"));
        // 以前的觸發輔助、圖騰施法拿掉了，身上的換成未切割寶石
        for (final String retired : List.of("spell_totem", "trigger_crit", "trigger_hit_taken", "trigger_kill", "trigger_ailment",
                "cast_on_dodge", "cast_on_minion_death")) {
            assertNull(config.support(retired), retired);
        }
        assertEquals(3, config.retiredSupportTier("trigger_ailment"));
        assertEquals(2, config.retiredSupportTier("spell_totem"));
    }

    @Test
    void maxEnergyFollowsCastTime() {
        assertEquals(120, MetaSpec.maxEnergy(config.skill("fireball")), 1e-9, "熾星彈 1.2 秒 = 120");
        assertTrue(MetaSpec.maxEnergy(config.skill("arc")) >= 10);
        assertEquals(1, MetaGems.power(MonsterRarity.NORMAL), 1e-9);
        assertEquals(10, MetaGems.power(MonsterRarity.UNIQUE), 1e-9);
    }

    @Test
    void onlyCastableSpellsFitSockets() {
        final MetaSpec crit = config.meta("meta_crit");
        final MetaSpec totem = config.meta("totem_casting");
        assertNull(MetaGems.problem(crit, config.skill("fireball")));
        assertNull(MetaGems.problem(totem, config.skill("fireball")));
        assertNotNull(MetaGems.problem(crit, config.skill("boneshatter")), "攻擊不行");
        assertNotNull(MetaGems.problem(crit, config.skill("raise_zombie")), "召喚不行");
        assertNotNull(MetaGems.problem(crit, config.skill("frost_aegis")), "光環不行");
        assertNotNull(MetaGems.problem(crit, config.skill("meta_ailment")), "主要寶石不能插主要寶石");
        assertNotNull(MetaGems.problem(totem, config.skill("dark_idol")), "本來就是圖騰的不行");
        for (final SkillDefinition skill : config.all()) {
            if (skill.cooldown() > 0 && skill.type() == SkillType.SPELL) {
                assertNotNull(MetaGems.problem(totem, skill), skill.id() + " 有冷卻：法術圖騰不行");
            }
        }
    }

    @Test
    void newChargeStatsAreDescribedAndValid() {
        assertEquals("重暈敵人時 50% 機率獲得一顆磐星", ChargeText.describe("charge.event.stun_applied.endurance", 50, ""));
        assertEquals("撲殺敵人時獲得一顆怒星", ChargeText.describe("charge.event.cull.frenzy", 100, ""));
        assertEquals("獲得一顆銳星時 15% 機率多獲得一顆", ChargeText.describe("charge.extra.power", 15, ""));
        assertEquals("獲得戰星時 25% 機率多獲得一顆同種的", ChargeText.describe("charge.extra", 25, ""));
        assertTrue(ChargeText.describe("charge.periodic", 10, "").startsWith("每分鐘獲得 10 顆戰星（每 6 秒一顆"));
        assertEquals("冰凍敵人時給的戰星每 4 秒最多一次", ChargeText.describe("charge.event_cooldown.freeze", 4, ""));
        assertNull(ChargeText.chargeOf("charge.event.nope.frenzy"), "不認得的事件");
        for (final String stat : List.of("charge.event.dodge.frenzy", "charge.extra.endurance", "charge.extra", "charge.periodic",
                "charge.event_cooldown.shock")) {
            assertTrue(StatLines.knows(stat), stat);
            assertNull(StatLines.problem(new Modifier(stat, ModType.FLAT, 10, Set.of(), "test")), stat);
        }
        assertNotNull(StatLines.problem(new Modifier("charge.extra", ModType.INCREASED, 0.1, Set.of(), "test")), "要 FLAT");
        assertNotNull(config.support("charge_profusion"));
        assertTrue(config.skill("combat_frenzy").aura().effects().stream().anyMatch(e -> e.stat().equals("charge.event.freeze.frenzy")),
                "戰意昂揚照 PoE2 改成冰凍、感電、重暈時給怒星");
        assertNotNull(config.skill("star_cadence").aura());
    }
}
