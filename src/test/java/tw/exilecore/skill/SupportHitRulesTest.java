package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // 2026-10-26 照 PoE2：架盾是攻擊（放開時的盾擊會打人），拿掉；這次之前也列架盾
        for (final String id : new String[] {"wound_scar_curse", "blink"}) {
            assertEquals(reason, support("added_fire").refusal(skill(id)), id);
            assertEquals(reason, support("savage_flare").refusal(skill(id)), id);
            assertEquals(reason, support("frost_seal").refusal(skill(id)), id);
            assertEquals(reason, support("held_breath").refusal(skill(id)), id);
        }
        // 2026-10-26：印記不算法術了（照 PoE2）：在標籤那一關或「不會造成傷害」擋掉都可以
        for (final String supportId : new String[] {"added_fire", "savage_flare", "frost_seal", "held_breath"}) {
            final String refusal = support(supportId).refusal(skill("prey_kill_mark"));
            assertTrue(reason.equals(refusal) || "要攻擊技能或法術技能".equals(refusal), supportId + "：" + refusal);
        }
        // 2026-10：光環不算法術了（照 PoE2）——要攻擊或法術的輔助在標籤那一關就擋掉，不看標籤的照舊擋在「不會造成傷害」
        for (final String supportId : new String[] {"added_fire", "savage_flare", "frost_seal", "held_breath"}) {
            final String refusal = support(supportId).refusal(skill("winter_shell"));
            assertTrue(reason.equals(refusal) || "要攻擊技能或法術技能".equals(refusal), supportId + "：" + refusal);
        }
        assertFalse(skill("winter_shell").tags().contains(tw.exilecore.stats.Tags.SPELL), "光環不算法術");
        assertTrue(skill("ember_envoy").tags().contains(tw.exilecore.stats.Tags.SPELL), "捷光環例外：輔助要傳給擊殺效果");
        assertEquals(reason, support("frost_seal").refusal(skill("stalwart_war_shout")), "戰吼沒有 attack／spell 標籤：只有不看標籤的輔助會走到這條");
        // 2026-10-29 照 PoE2 的 Minion Types：無情、分光照召喚物的技能標籤（攻擊、投射物）可以掛，效果給召喚物（MinionSupportTypesTest）；
        // 這次之前無情「召喚物技能不行」、分光掛骸骨射手「要投射物技能」。主人自己的機制（怒火）照舊不行，骸骨戰士是近戰照舊掛不上分光
        assertNull(support("merciless_heart").refusal(skill("summon_skeletons")));
        assertEquals("召喚物技能不行", support("rage").refusal(skill("summon_skeletons")), "怒火給的是主人的怒氣");
        assertNull(support("light_split").refusal(skill("summon_bone_bowmen")));
        assertEquals("要投射物技能", support("light_split").refusal(skill("summon_skeletons")), "近戰的骸骨戰士");
        // 真的會打到敵人的照舊
        assertNull(support("added_fire").refusal(skill("blazing_star_shell")));
        assertNull(support("added_fire").refusal(skill("field_quake_hammers")));
        assertNull(support("added_fire").refusal(skill("thunder_escape")), "會打到敵人的位移");
        assertNull(support("added_fire").refusal(skill("ember_envoy")), "捷的輔助會傳給擊殺效果");
        // 2026-10-26 照 PoE2：架盾（Raise Shield）是攻擊、近戰、範圍、物理，盾擊會打人（原作能掛的輔助很多：暈眩、反擊…）
        assertNull(support("added_fire").refusal(skill("shield_brace")), "架盾的盾擊");
        assertTrue(skill("shield_brace").isAttack());
        assertTrue(skill("shield_brace").tags().containsAll(java.util.Set.of(tw.exilecore.stats.Tags.ATTACK, tw.exilecore.stats.Tags.MELEE,
                tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.PHYSICAL)), skill("shield_brace").tags().toString());
    }

    @Test
    void utilitySupportsStillFitUtilitySkills() {
        assertNull(support("zone_expand").refusal(skill("campaign_flag")), "範圍：戰旗有 area"); // 2026-10-26：凜冬之殼、魔湧照 PoE2 都沒有範圍了，改用征戰之旗
        assertNull(support("long_stay").refusal(skill("wound_scar_curse"))); // 2026-10-27：持久併入長留、速冷併入迅返（只留最高階）
        assertNull(support("quick_return").refusal(skill("stalwart_war_shout")));
        assertNull(support("minion_damage").refusal(skill("corpse_summon")), "minion 標籤的給召喚物");
        assertNull(support("spring_mana").refusal(skill("winter_shell")), "精魂輔助");
        assertNull(support("stern_hex").refusal(skill("wound_scar_curse")));
        assertNull(support("endless_howl").refusal(skill("ground_shake_shout")), "震地戰吼會強化攻擊");
        // 2026-10：好處是傷害、代價是範圍／冷卻／持續／施法速度的輔助，掛在不會造成傷害的技能上只剩代價：不行
        final String reason = "不會造成傷害的技能不行";
        assertEquals(reason, support("burst").refusal(skill("prey_kill_mark")), "爆發：傷害吃不到，只剩冷卻變久");
        // 2026-10-26 照 PoE2：魔湧（Mana Tempest）沒有 AoE 標籤（風暴不吃範圍加成），凝聚在標籤那一關就擋掉；這次之前是光環，擋在「不會造成傷害」
        assertEquals("要範圍技能", support("condense_power").refusal(skill("magic_surge")), "凝聚掛在魔湧上：風暴不吃範圍");
        assertNotNull(support("condense_power").refusal(skill("winter_shell")), "2026-10-26：凜冬之殼照 PoE2 沒有範圍，先被「要範圍技能」擋下");
        assertEquals(reason, support("fast_decay").refusal(skill("prey_kill_mark")));
        assertEquals(reason, support("stored_might").refusal(skill("wound_scar_curse")));
        assertEquals(reason, support("stored_edge").refusal(skill("wound_scar_curse")));
        assertEquals(reason, support("charged_chant").refusal(skill("wound_scar_curse")));
        // 會造成傷害的技能照舊能掛；範圍變大的、只改冷卻／持續的也照舊
        assertNull(support("condense_power").refusal(skill("blazing_star_shell")));
        assertNull(support("burst").refusal(skill("sky_column_thunder")), "2026-10-26：雷遁照原作沒有冷卻了；霜綻照站長拿掉了，改用天柱雷");
        assertNull(support("quake_zone").refusal(skill("campaign_flag")), "範圍變大那半邊有用"); // 2026-10-26：凜冬之殼、魔湧照 PoE2 都沒有範圍了，改用征戰之旗
        assertNull(support("quick_return").refusal(skill("prey_kill_mark")), "冷卻變短有用");
        assertNull(support("twin_chant").refusal(skill("wound_scar_curse")), "代價是傷害的不擋");
        assertEquals("要冷卻技能", support("burst").refusal(skill("corpse_summon")), "喚屍沒有冷卻");
        // 2026-10-29 照 PoE2 的 Minion Types：凝息照殭屍的攻擊（Attack, Melee）可以掛、效果給殭屍；這次之前「不會造成傷害的技能不行」。
        // 不打人的骸骨祭司照舊不行
        assertNull(support("held_breath").refusal(skill("corpse_summon")));
        assertEquals("召喚物不會造成傷害", support("held_breath").refusal(skill("summon_bone_priest")), "純傷害的輔助掛不打人的召喚物沒效果");
    }

    @Test
    void projectileMechanicsNeedRealProjectiles() {
        final String reason = "要真的射出投射物的技能";
        assertEquals(reason, support("pierce").refusal(skill("nailfall_crossbow_turret")), "落釘弩台是 impact 機制");
        // 2026-10-27：落下型的投射物技能多的投射物各落一道（SkillExecutor.impact）：多射的輔助可以掛（照 PoE2）
        assertNull(support("light_split").refusal(skill("nailfall_crossbow_turret")));
        assertNull(support("star_panorama").refusal(skill("thunder_call_shaft")), "星辰萬象可以掛召霆矢");
        // 2026-10-24：只動投射物速度的輔助可以掛在落下的投射物（弩台）：速度決定落下的快慢
        assertNull(support("slow_shell").refusal(skill("nailfall_crossbow_turret")));
        assertNull(support("swift_bolt_iii").refusal(skill("nailfall_crossbow_turret")));
        assertNull(support("swift_bolt_iii").refusal(skill("skyward_crossbow_turret")));
        assertNull(support("hug_range").refusal(skill("nailfall_crossbow_turret")), "只吃投射物傷害的照舊");
        assertNull(support("pierce").refusal(skill("blazing_star_shell")));
        assertNull(support("chain").refusal(skill("chain_thunder")), "鏈雷多跳幾次");
        // 2026-10-27：指令技能（骸弓齊射）照 PoE2 拿掉，這一條跟著拿掉
    }

    @Test
    void heraldsAreNotCastSpells() {
        for (final String id : new String[] {"twin_chant", "sealed_chant", "build_poise", "quick_chant", "charged_chant"}) {
            assertFalse(support(id).supports(skill("ember_envoy")), id);
            assertFalse(support(id).supports(skill("herald_ash_burst")), id + "：擊殺效果也帶 herald 標籤");
        }
        assertFalse(support("quick_chant").supports(skill("winter_shell")), "光環不施放");
        assertTrue(support("quick_chant").supports(skill("wound_scar_curse")), "詛咒要施放");
        assertTrue(support("stern_proclaim").supports(skill("ember_envoy")));
    }

    @Test
    void scattershotPenalisesSpellsToo() {
        // 2026-10-27：分矢（Multishot I）併入分光（Multishot II），改測分光
        assertTrue(support("light_split").modifiers().stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.CAST_SPEED) && m.value() < 0));
    }

    @Test
    void supportsTextMentionsHitOnly() {
        assertTrue(support("added_fire").supportsText(config.all()).contains("不會造成傷害的技能除外"));
        assertFalse(support("life_leech").supportsText(config.all()).contains("不會造成傷害"), "只要攻擊的不用寫");
        assertFalse(support("zone_expand").supportsText(config.all()).contains("不會造成傷害"));
    }

    /** 2026-10-17 標籤整理：掛得上卻沒作用的輔助擋掉、技能標籤補齊。 */
    @Test
    void tagAuditRules() {
        // 2026-10-26 照 PoE2：堅壁戰吼會強化盾的攻擊、戰旗不是戰吼（戰吼的輔助在標籤那一關就擋掉）、怒嘯要能用磐星跳過冷卻的戰吼
        assertEquals("要會強化攻擊的戰吼", support("endless_howl").refusal(skill("silver_moon_shelter")));
        // 2026-10-26：怒嘯 I 併入怒嘯 II
        assertEquals("要戰吼技能", support("angry_roar_ii").refusal(skill("campaign_flag")));
        assertEquals("要可以花磐星跳過冷卻的戰吼", support("angry_roar_ii").refusal(skill("silver_moon_shelter")));
        assertNull(support("angry_roar_ii").refusal(skill("hellfire_war_shout")));
        assertEquals("要戰吼技能", support("quake_howl").refusal(skill("bloodthirst_rite")), "祭品不是戰吼");
        assertEquals("本來就要榮耀才能用的技能不行", support("quake_howl").refusal(skill("molten_soul_shriek")));
        assertEquals("要原本只打一個目標的打擊技能", support("sweep_strike").refusal(skill("blood_tear_sweep")));
        assertNull(support("sweep_strike").refusal(skill("gale_combo")), "2026-10-26 照 PoE2：風暴連擊改成打單一目標");
        assertNull(support("sweep_strike").refusal(skill(firstSingleStrike())));
        assertEquals("要打擊技能（衝過去、扇形的不行）", support("realm_break_spread").refusal(skill("spear_recoil_leap")));
        assertEquals("本身會蓄力的技能不行", support("sealed_chant").refusal(skill("burning_current")));
        assertEquals("本身會蓄力的技能不行", support("swift_edge").refusal(skill("burning_current")), "2026-10-26：蓄力射擊照 PoE2 改成爆破矢（放開型引導，不是 channel.stages），改用焚流");
        assertEquals("要祭品技能", support("bone_dance").refusal(skill("summon_bone_bowmen")), "2026-10-26 照 PoE2：骸舞只能掛祭品");
        assertNull(support("bone_dance").refusal(skill("bone_rite")));
        // 2026-10-29 照 PoE2：引星是常駐增益、不是光環；精魂輔助改看 persistent_buff，照樣掛得上
        assertFalse(skill("critical_guide_star").tags().contains(tw.exilecore.stats.Tags.AURA), "引星不算光環");
        assertTrue(SupportDefinition.persistentBuff(skill("critical_guide_star")));
        assertTrue(support("spring_mana").supports(skill("critical_guide_star")), "泉魔照樣掛得上引星");
        assertFalse(skill("steed_call").tags().contains(tw.exilecore.stats.Tags.TRAVEL), "坐騎不算突進");
        assertTrue(skill("blood_tear_sweep").tags().contains(tw.exilecore.stats.Tags.TRAVEL), "裂血掠還是突進");
        assertTrue(skill("thorn_shaft").tags().contains(tw.exilecore.stats.Tags.PHYSICAL), "剩 70% 物理");
        assertFalse(skill("thunderclap_arrow").tags().contains(tw.exilecore.stats.Tags.PHYSICAL), "2026-10-26 照 PoE2：80% 轉閃電，只剩 20% 物理");
        assertFalse(skill("facet_light_rift").tags().contains(tw.exilecore.stats.Tags.PHYSICAL), "隨機元素不算（2026-10-26：幻元擊拿掉了，改用稜光裂）");
        assertTrue(skill("ground_shake_shout").tags().contains(tw.exilecore.stats.Tags.PHYSICAL));
        assertTrue(skill("starfire_bead_chain").tags().contains(tw.exilecore.stats.Tags.AREA));
        assertFalse(skill("soul_draw_shot").tags().contains(tw.exilecore.stats.Tags.AREA));
        assertTrue(skill("instant_shadow_strike").tags().contains(tw.exilecore.stats.Tags.AREA), "2026-10-28 照 PoE2：瞬影擊有範圍標籤（還是單體打擊）");
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
