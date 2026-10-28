package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：照 PoE2 0.5 校對的技能（第二批）：逐級冷卻、對敵人的常駐光環、只給自己的常駐增益、觸發技能自己的冷卻與傷害表。 */
class Poe2SkillSyncTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double levelValue(final String skill, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        throw new AssertionError(skill + " 沒有 " + stat);
    }

    @Test
    void cooldownsFollowTheGemLevel() {
        // Pounce 6 → 5.1（20 級）秒、Blink 4.5 → 3.5（20 級）秒
        final SkillBuild pounce1 = SkillBuild.of(config, config.skill("pounce"), 1, 100, false, List.of(), Set.of(Tags.TALISMAN), new StatSheet());
        final SkillBuild pounce20 = SkillBuild.of(config, config.skill("pounce"), 20, 100, false, List.of(), Set.of(Tags.TALISMAN), new StatSheet());
        assertEquals(120, pounce1.cooldownTicks());
        assertEquals(102, pounce20.cooldownTicks());
        final SkillBuild blink20 = SkillBuild.of(config, config.skill("blink"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertEquals(70, blink20.cooldownTicks());
        for (final String mark : List.of("voltaic_mark", "freezing_mark", "bloodhounds_mark")) {
            assertEquals(0.5, config.skill(mark).cooldown(), 1e-9, mark);
            assertEquals(79, config.skill(mark).mana().at(20), 1e-9, mark);
        }
    }

    @Test
    void enemyPresenceAuras() {
        final AuraSpec overwhelming = config.skill("overwhelming_presence").aura();
        assertFalse(overwhelming.allies(), "作用在敵人身上，不給隊友");
        assertNotNull(overwhelming.enemy());
        assertEquals(2, overwhelming.enemy().effects().size());
        assertEquals(StatKeys.STUN_THRESHOLD, overwhelming.enemy().effects().get(0).stat());
        assertEquals(-25, overwhelming.enemy().effects().get(0).value().at(20), 1e-9);
        assertEquals(-24, overwhelming.enemy().effects().get(1).value().at(20), 1e-9);
        final AuraSpec withering = config.skill("withering_presence").aura();
        assertNotNull(withering.enemy().witheredEvery());
        assertEquals(1.8, withering.enemy().witheredEvery().at(1), 1e-9);
        assertEquals(1.42, withering.enemy().witheredEvery().at(20), 1e-9);
        assertEquals(0, withering.enemy().taken().at(20), 1e-9, "受到的傷害增加改成凋零");
    }

    @Test
    void selfOnlyPersistentBuffs() {
        for (final String id : List.of("time_of_need", "ghost_dance", "barkskin", "mana_remnants", "alchemists_boon")) {
            assertFalse(config.skill(id).aura().allies(), id + " 只給自己");
        }
        assertEquals(8.1, levelValue("time_of_need", PersistentBuffs.BLESSING_INTERVAL, 20), 1e-9);
        assertEquals(628, config.skill("time_of_need").aura().effects().getFirst().value().at(20), 1e-9);
        assertEquals(10.1, levelValue("ghost_dance", PersistentBuffs.SHROUD_INTERVAL, 20), 1e-9);
        assertEquals(62, config.skill("barkskin").aura().effects().getFirst().value().at(20), 1e-9);
        final ScaledMod rage = config.skill("eternal_rage").aura().effects().getFirst();
        assertEquals(SkillResources.RAGE_REGEN, rage.stat());
        assertEquals(5, rage.value().at(20), 1e-9);
        assertEquals(StatKeys.FLASK_CHARGES_PER_MINUTE_LIFE, config.skill("alchemists_boon").aura().effects().getFirst().stat());
    }

    @Test
    void triggeredSkillsUsePoe2Values() {
        assertEquals(0.2, config.skill("proc_coursing_current").cooldown(), 1e-9);
        assertEquals(6, config.skill("proc_coursing_current").params().chains().at(1), 1e-9);
        assertTrue(config.skill("proc_coursing_current").damageByLevel());
        // 2026-10-28 照站長刪掉震符印（符文震波 proc_concussive_rune 一起拿掉）
        assertTrue(config.skill("proc_crystal_burst").isAttack(), "冰晶爆炸是攻擊");
        assertTrue(config.skill("proc_volcanic_eruption").isAttack());
        assertEquals(4, config.skill("proc_volcanic_eruption").params().projectiles().at(1), 1e-9);
    }

    @Test
    void strikesAreSingleTargetAndExtraAilmentsAreGone() {
        for (final String id : List.of("fangs_of_frost", "bear_maul", "wolf_shred", "primal_strikes")) {
            assertEquals(1, config.skill(id).params().targets(), id);
        }
        for (final String id : List.of("falling_thunder", "explosive_spear", "mountain_fury", "walking_calamity", "thunderstorm", "volcano")) {
            assertTrue(config.skill(id).modifiers().stream().noneMatch(m -> m.stat().endsWith(".chance") && m.value() > 0),
                    id + "：原作沒有的異常機率要拿掉");
        }
    }
}
