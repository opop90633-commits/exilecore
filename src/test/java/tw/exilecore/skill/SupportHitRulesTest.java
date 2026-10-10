package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：純傷害的輔助不能掛在不會造成傷害的技能上；投射物的機制只給真的射出投射物的技能；捷不算「要施放」的法術。 */
class SupportHitRulesTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertTrue(support != null, "沒有輔助 " + id);
        return support;
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition skill = config.skill(id);
        assertTrue(skill != null, "沒有技能 " + id);
        return skill;
    }

    @Test
    void hitOnlySupportsRefuseSkillsThatNeverHit() {
        final String reason = "不會造成傷害的技能不行";
        // 光環、詛咒、印記、護體、戰吼、純位移、召喚本身都不會打到敵人
        for (final String id : new String[] {"frost_aegis", "vulnerability_curse", "hunters_mark", "raise_guard", "fortifying_cry", "blink", "raise_zombie"}) {
            assertEquals(reason, support("added_fire").refusal(skill(id)), id);
            assertEquals(reason, support("critical_strikes").refusal(skill(id)), id);
            assertEquals(reason, support("deep_freeze").refusal(skill(id)), id);
            assertEquals(reason, support("deliberation").refusal(skill(id)), id);
        }
        assertEquals(reason, support("ruthless").refusal(skill("summon_skeletons")), "無情的傷害沒有 minion 標籤：召喚技能掛了只會落到主人身上");
        assertEquals(reason, support("pinning").refusal(skill("skeletal_archers")), "遠程召喚物只是畫一道彈道，沒有真的投射物");
        // 真的會打到敵人的照舊
        assertNull(support("added_fire").refusal(skill("fireball")));
        assertNull(support("added_fire").refusal(skill("heavy_strike")));
        assertNull(support("added_fire").refusal(skill("storm_blink")), "會打到敵人的位移");
        assertNull(support("added_fire").refusal(skill("herald_of_ash")), "捷的輔助會傳給擊殺效果");
    }

    @Test
    void utilitySupportsStillFitUtilitySkills() {
        assertNull(support("increased_area").refusal(skill("frost_aegis")), "範圍：光環有 area");
        assertNull(support("increased_duration").refusal(skill("vulnerability_curse")));
        assertNull(support("cooldown_recovery").refusal(skill("fortifying_cry")));
        assertNull(support("minion_damage").refusal(skill("raise_zombie")), "minion 標籤的給召喚物");
        assertNull(support("clarity").refusal(skill("frost_aegis")), "精魂輔助");
        assertNull(support("heightened_curse").refusal(skill("vulnerability_curse")));
        assertNull(support("trigger_hit_taken").refusal(skill("vulnerability_curse")), "受擊時放詛咒");
        assertNull(support("warcry_power").refusal(skill("fortifying_cry")));
        assertNull(support("burst").refusal(skill("hunters_mark")), "冷卻那半邊對印記有用");
        assertEquals("不會造成傷害的技能不行", support("burst").refusal(skill("raise_zombie")), "喚屍沒有冷卻：傷害那半邊又用不到");
    }

    @Test
    void projectileMechanicsNeedRealProjectiles() {
        final String reason = "要真的射出投射物的技能";
        assertEquals(reason, support("pierce").refusal(skill("artillery_ballista")), "落釘弩台是 impact 機制");
        assertEquals(reason, support("multiple_projectiles").refusal(skill("artillery_ballista")));
        assertEquals(reason, support("projectile_deceleration").refusal(skill("artillery_ballista")));
        assertNull(support("point_blank").refusal(skill("artillery_ballista")), "只吃投射物傷害的照舊");
        assertNull(support("pierce").refusal(skill("split_arrow")));
        assertNull(support("chain").refusal(skill("arc")), "鏈雷多跳幾次");
        assertNull(support("multiple_projectiles").refusal(skill("command_volley")), "指令技能自己會射投射物");
    }

    @Test
    void heraldsAreNotCastSpells() {
        for (final String id : new String[] {"spell_echo", "trigger_crit", "unleash", "channel_focus", "ember_infusion", "faster_casting", "considered_casting"}) {
            assertFalse(support(id).supports(skill("herald_of_ash")), id);
            assertFalse(support(id).supports(skill("herald_ash_burst")), id + "：擊殺效果也帶 herald 標籤");
        }
        assertFalse(support("faster_casting").supports(skill("frost_aegis")), "光環不施放");
        assertTrue(support("faster_casting").supports(skill("vulnerability_curse")), "詛咒要施放");
        assertTrue(support("deadly_herald").supports(skill("herald_of_ash")));
    }

    @Test
    void scattershotPenalisesSpellsToo() {
        assertTrue(support("scattershot").modifiers().stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.CAST_SPEED) && m.value() < 0));
    }

    @Test
    void supportsTextMentionsHitOnly() {
        assertTrue(support("added_fire").supportsText(config.all()).contains("不會造成傷害的技能除外"));
        assertFalse(support("life_leech").supportsText(config.all()).contains("不會造成傷害"), "只要攻擊的不用寫");
        assertFalse(support("increased_area").supportsText(config.all()).contains("不會造成傷害"));
    }
}
