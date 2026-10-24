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
        for (final String id : new String[] {"vulnerability_curse", "hunters_mark", "raise_guard", "blink"}) {
            assertEquals(reason, support("added_fire").refusal(skill(id)), id);
            assertEquals(reason, support("critical_strikes").refusal(skill(id)), id);
            assertEquals(reason, support("deep_freeze").refusal(skill(id)), id);
            assertEquals(reason, support("deliberation").refusal(skill(id)), id);
        }
        // 2026-10：光環不算法術了（照 PoE2）——要攻擊或法術的輔助在標籤那一關就擋掉，不看標籤的照舊擋在「不會造成傷害」
        for (final String supportId : new String[] {"added_fire", "critical_strikes", "deep_freeze", "deliberation"}) {
            final String refusal = support(supportId).refusal(skill("frost_aegis"));
            assertTrue(reason.equals(refusal) || "要攻擊技能或法術技能".equals(refusal), supportId + "：" + refusal);
        }
        assertFalse(skill("frost_aegis").tags().contains(tw.exilecore.stats.Tags.SPELL), "光環不算法術");
        assertTrue(skill("herald_of_ash").tags().contains(tw.exilecore.stats.Tags.SPELL), "捷光環例外：輔助要傳給擊殺效果");
        assertEquals(reason, support("deep_freeze").refusal(skill("fortifying_cry")), "戰吼沒有 attack／spell 標籤：只有不看標籤的輔助會走到這條");
        assertEquals("召喚物技能不行", support("ruthless").refusal(skill("summon_skeletons")), "無情的傷害沒有 minion 標籤：召喚技能掛了只會落到主人身上");
        assertEquals("要投射物技能", support("pinning").refusal(skill("skeletal_archers")), "遠程召喚物標「遠程」不標投射物（2026-10）：投射物的輔助在標籤那一關就擋掉");
        // 真的會打到敵人的照舊
        assertNull(support("added_fire").refusal(skill("fireball")));
        assertNull(support("added_fire").refusal(skill("rolling_slam")));
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
        assertNull(support("warcry_power").refusal(skill("seismic_cry")), "震地戰吼會強化攻擊");
        // 2026-10：好處是傷害、代價是範圍／冷卻／持續／施法速度的輔助，掛在不會造成傷害的技能上只剩代價：不行
        final String reason = "不會造成傷害的技能不行";
        assertEquals(reason, support("burst").refusal(skill("hunters_mark")), "爆發：傷害吃不到，只剩冷卻變久");
        assertEquals(reason, support("concentrated_effect").refusal(skill("mana_storm")), "凝聚掛在魔湧上：只剩範圍變小");
        assertEquals(reason, support("concentrated_effect").refusal(skill("frost_aegis")));
        assertEquals(reason, support("intense_agony").refusal(skill("hunters_mark")));
        assertEquals(reason, support("hourglass").refusal(skill("vulnerability_curse")));
        assertEquals(reason, support("excise").refusal(skill("vulnerability_curse")));
        assertEquals(reason, support("considered_casting").refusal(skill("vulnerability_curse")));
        // 會造成傷害的技能照舊能掛；範圍變大的、只改冷卻／持續的也照舊
        assertNull(support("concentrated_effect").refusal(skill("fireball")));
        assertNull(support("burst").refusal(skill("storm_blink")));
        assertNull(support("impact_area").refusal(skill("frost_aegis")), "範圍變大那半邊有用");
        assertNull(support("swift_cooldown").refusal(skill("hunters_mark")), "冷卻變短有用");
        assertNull(support("spell_echo").refusal(skill("vulnerability_curse")), "代價是傷害的不擋");
        assertEquals("要冷卻技能", support("burst").refusal(skill("raise_zombie")), "喚屍沒有冷卻");
        assertEquals("不會造成傷害的技能不行", support("deliberation").refusal(skill("raise_zombie")), "純傷害的輔助掛召喚沒效果");
    }

    @Test
    void projectileMechanicsNeedRealProjectiles() {
        final String reason = "要真的射出投射物的技能";
        assertEquals(reason, support("pierce").refusal(skill("artillery_ballista")), "落釘弩台是 impact 機制");
        assertEquals(reason, support("multiple_projectiles").refusal(skill("artillery_ballista")));
        // 2026-10-24：只動投射物速度的輔助可以掛在落下的投射物（弩台）：速度決定落下的快慢
        assertNull(support("projectile_deceleration").refusal(skill("artillery_ballista")));
        assertNull(support("faster_projectiles").refusal(skill("artillery_ballista")));
        assertNull(support("faster_projectiles").refusal(skill("siege_ballista")));
        assertNull(support("point_blank").refusal(skill("artillery_ballista")), "只吃投射物傷害的照舊");
        assertNull(support("pierce").refusal(skill("fireball")));
        assertNull(support("chain").refusal(skill("arc")), "鏈雷多跳幾次");
        assertNull(support("multiple_projectiles").refusal(skill("command_volley")), "指令技能自己會射投射物");
    }

    @Test
    void heraldsAreNotCastSpells() {
        for (final String id : new String[] {"spell_echo", "unleash", "channel_focus", "ember_infusion", "faster_casting", "considered_casting"}) {
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

    /** 2026-10-17 標籤整理：掛得上卻沒作用的輔助擋掉、技能標籤補齊。 */
    @Test
    void tagAuditRules() {
        assertEquals("要會強化攻擊的戰吼", support("warcry_power").refusal(skill("fortifying_cry")));
        assertEquals("要會強化攻擊的戰吼", support("enraged_warcry").refusal(skill("war_banner")));
        assertEquals("要原本只打一個目標的打擊技能", support("additional_strike").refusal(skill("lunge")));
        assertEquals("要原本只打一個目標的打擊技能", support("additional_strike").refusal(skill("tempest_flurry")));
        assertNull(support("additional_strike").refusal(skill(firstSingleStrike())));
        assertEquals("要打擊技能（衝過去、扇形的不行）", support("shattering_strike").refusal(skill("disengage")));
        assertEquals("本身會蓄力的技能不行", support("unleash").refusal(skill("flame_stream")));
        assertEquals("本身會蓄力的技能不行", support("hit_and_run").refusal(skill("charged_shot")));
        assertEquals("遠程技能不行", support("danse_macabre").refusal(skill("skeletal_archers")));
        assertTrue(skill("meta_crit").tags().contains(tw.exilecore.stats.Tags.AURA), "引星維持算光環（精魂輔助掛得上）");
        assertFalse(skill("summon_mount").tags().contains(tw.exilecore.stats.Tags.TRAVEL), "坐騎不算突進");
        assertTrue(skill("lunge").tags().contains(tw.exilecore.stats.Tags.TRAVEL), "突刺還是突進");
        assertTrue(skill("bramble_arrow").tags().contains(tw.exilecore.stats.Tags.PHYSICAL), "剩 70% 物理");
        assertTrue(skill("lightning_arrow").tags().contains(tw.exilecore.stats.Tags.PHYSICAL), "剩 50% 物理");
        assertFalse(skill("prismatic_strike").tags().contains(tw.exilecore.stats.Tags.PHYSICAL), "隨機元素不算");
        assertTrue(skill("seismic_cry").tags().contains(tw.exilecore.stats.Tags.PHYSICAL));
        assertTrue(skill("ember_volley").tags().contains(tw.exilecore.stats.Tags.AREA));
        assertFalse(skill("essence_drain").tags().contains(tw.exilecore.stats.Tags.AREA));
        assertFalse(skill("flicker_strike").tags().contains(tw.exilecore.stats.Tags.AREA));
    }

    private static String firstSingleStrike() {
        for (final SkillDefinition s : config.all()) {
            if (!s.hidden() && s.isAttack() && s.mechanic() == Mechanic.STRIKE && s.params().targets() == 1
                    && s.tags().contains(tw.exilecore.stats.Tags.STRIKE) && !s.tags().contains(tw.exilecore.stats.Tags.TOTEM)) {
                return s.id();
            }
        }
        throw new AssertionError("沒有單體打擊技能");
    }
}
