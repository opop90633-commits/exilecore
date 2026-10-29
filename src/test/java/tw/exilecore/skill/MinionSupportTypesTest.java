package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
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

/**
 * 2026-10-29 照 PoE2 的 Minion Types：召喚技能有「召喚物的技能」標籤（summon.minion_tags），輔助照那些標籤也能掛、效果給召喚物
 * （貫穿、連環、分光掛骸骨射手，沉錘掛骸骨戰士…）；主人自己的機制（怒氣、雙詠）照舊不行。
 */
class MinionSupportTypesTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, "沒有輔助 " + id);
        return support;
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition skill = config.skill(id);
        assertNotNull(skill, "沒有技能 " + id);
        return skill;
    }

    private static SkillBuild build(final String skill, final SupportDefinition... supports) {
        return SkillBuild.of(config, config.skill(skill), 20, 100, false, List.of(supports), Set.of(Tags.WAND), new StatSheet());
    }

    /** 召喚物自己的屬性表（還沒疊輔助）。 */
    private static StatSheet minionSheet(final SkillBuild build) {
        final double[] damage = monsters.damageAt(90);
        final MinionSheets.Base base = new MinionSheets.Base(monsters.lifeAt(90), monsters.accuracyAt(90), monsters.evasionAt(90),
                monsters.armourAt(90), damage[0], damage[1], monsters.critChance());
        return MinionSheets.build(build.definition().summon(), base, List.of());
    }

    /** 跟 MinionService 一樣：召喚物的屬性表疊上輔助（拿掉 minion 標籤）。 */
    private static StatSheet minionEffective(final SkillBuild build) {
        return minionSheet(build).overlay(MinionSheets.stripMinion(MinionSheets.profile(build).local()));
    }

    @Test
    void minionTagsLoadedFromPoe2() {
        assertEquals(Set.of(Tags.ATTACK, Tags.PROJECTILE, Tags.AREA, Tags.DURATION), skill("skeletal_archers").summon().minionTags(), "Skeletal Sniper");
        assertEquals(Set.of(Tags.ATTACK, Tags.MELEE), skill("summon_skeletons").summon().minionTags(), "Skeletal Warrior（範圍沒接上，不列）");
        assertEquals(Set.of(Tags.SPELL, Tags.COOLDOWN), skill("skeletal_cleric").summon().minionTags(), "Skeletal Cleric");
        assertTrue(skill("skeletal_stormcaller").summon().minionTags().contains("chain"), "Skeletal Storm Mage 有 Chains");
        assertTrue(skill("skeletal_frostcaller").summon().minionTags().containsAll(Set.of(Tags.SPELL, Tags.PROJECTILE)));
        // 掘骸本身不是召喚技能：照骨構體的
        assertEquals(skill("unearth_construct").summon().minionTags(), MinionTypes.of(skill(Unearth.ID)));
        // 每一個玩家拿得到、會一直打人的召喚技能都有寫（巨靈只聽指令，原作的指令是另外的寶石，不寫）
        for (final SkillDefinition each : config.all()) {
            if (each.summon() != null && !each.hidden() && !MinionAbilities.commandOnly(each.id())) {
                assertFalse(each.summon().minionTags().isEmpty(), each.id() + " 沒寫 minion_tags");
            }
        }
        assertTrue(MinionTypes.line(skill("skeletal_archers")).contains("攻擊、投射物、範圍、持續"));
        assertNull(MinionTypes.line(skill("fireball")));
    }

    @Test
    void projectileSupportsFitRangedMinionsOnly() {
        for (final String id : new String[] {"pierce", "chain", "fork", "multiple_projectiles"}) {
            assertNull(support(id).refusal(skill("skeletal_archers")), id + " 照 PoE2 可以輔助骸骨射手");
            assertNull(support(id).refusal(skill("skeletal_frostcaller")), id + " 照 PoE2 可以輔助骸骨霜術士");
            assertEquals("要投射物技能" + ("chain".equals(id) ? "或連鎖技能" : ""), support(id).refusal(skill("summon_skeletons")),
                    id + "：骸骨戰士是近戰");
            assertTrue(MinionTypes.throughMinions(support(id), skill("skeletal_archers")));
        }
        assertNull(support("chain").refusal(skill("skeletal_stormcaller")), "電弧多連鎖");
        assertEquals("要投射物技能", support("pierce").refusal(skill("skeletal_stormcaller")));
        // 砲手的箭雨（原作 ProjectileNoCollision、CannotChain）：投射物的機制吃不到
        assertEquals("召喚物沒有真的射出投射物（穿透、連鎖、分裂、多射吃不到）", support("pierce").refusal(skill("supporting_fire")));
        assertNull(support("point_blank").refusal(skill("skeletal_archers")), "投射物傷害的照樣可以");
        // 2026-10-29 照 PoE2：召喚物的投射物真的會飛（MinionProjectiles），只動投射物速度的也可以掛；這次之前「召喚物的技能吃不到這顆輔助的效果」
        assertNull(support("faster_projectiles").refusal(skill("skeletal_archers")), "疾矢：射手的箭飛得更快");
    }

    /** 2026-10-29 照 PoE2：投射物速度的輔助（疾矢、緩彈、重矢）從召喚物這邊掛，改變召喚物的投射物速度。 */
    @Test
    void projectileSpeedSupportsReachRangedMinions() {
        for (final String skill : new String[] {"skeletal_archers", "skeletal_pyromancer", "skeletal_frostcaller"}) {
            assertNull(support("faster_projectiles").refusal(skill(skill)), "疾矢可以輔助 " + skill);
            assertNull(support("projectile_deceleration").refusal(skill(skill)), "緩彈可以輔助 " + skill + "（poe2db 推薦 Projectile Deceleration）");
            assertTrue(MinionTypes.throughMinions(support("faster_projectiles"), skill(skill)));
        }
        // 不射投射物的：照舊不行
        assertEquals("要投射物技能", support("faster_projectiles").refusal(skill("summon_skeletons")));
        assertEquals("要投射物技能", support("faster_projectiles").refusal(skill("skeletal_stormcaller")), "雷術士的電弧是連鎖，不是投射物");
        assertEquals("召喚物的技能吃不到這顆輔助的效果", support("faster_projectiles").refusal(skill("supporting_fire")), "砲手沒有真的射投射物");

        final SkillBuild plain = build("skeletal_archers");
        final Set<String> tags = MinionSheets.profile(plain).tags();
        final MinionTypes.Projectiles base = MinionTypes.projectiles(minionEffective(plain), tags);
        assertEquals(1.0, base.speed(), 1e-9);
        assertEquals(MinionAbilities.SNIPER_ARROW_SPEED, base.speedOf(MinionAbilities.projectileSpeed("skeletal_archers")), 1e-9);
        final SkillBuild fast = build("skeletal_archers", support("faster_projectiles"));
        final MinionTypes.Projectiles quick = MinionTypes.projectiles(minionEffective(fast), MinionSheets.profile(fast).tags());
        assertEquals(1.4, quick.speed(), 1e-9, "疾矢 III：投射物速度更多 40%");
        assertEquals(MinionAbilities.SNIPER_ARROW_SPEED * 1.4, quick.speedOf(MinionAbilities.SNIPER_ARROW_SPEED), 1e-9);
        assertTrue(quick.any());
        // 疾矢 III：速度的增加也套用在投射物傷害（只有增加的部分；更多 40% 不算）
        assertEquals(0, MinionTypes.speedAsDamage(minionEffective(fast), MinionSheets.profile(fast).tags()), 1e-9);
        final SkillBuild heavyFast = build("skeletal_archers", support("faster_projectiles"), support("heavy_bolt"));
        assertNull(support("heavy_bolt").refusal(skill("skeletal_archers")));
        assertEquals(0.3, MinionTypes.speedAsDamage(minionEffective(heavyFast), MinionSheets.profile(heavyFast).tags()), 1e-9,
                "重矢的投射物速度增加 30% 也變成投射物傷害增加 30%");
        final SkillBuild slow = build("skeletal_pyromancer", support("projectile_deceleration"));
        assertEquals(0.75, MinionTypes.projectiles(minionEffective(slow), MinionSheets.profile(slow).tags()).speed(), 1e-9, "緩彈：更少 25%");
        // 說明寫每秒幾格
        assertTrue(MinionTypes.projectileLine(base, MinionAbilities.SNIPER_ARROW_SPEED).contains("每秒 <white>60</white> 格"));
        assertTrue(MinionTypes.projectileLine(quick, MinionAbilities.SNIPER_ARROW_SPEED).contains("每秒 <white>84</white> 格（速度 ×1.4）"));
        assertNull(MinionTypes.projectileLine(base), "沒有基礎速度、沒有輔助：照舊不寫");
        assertEquals(0, MinionAbilities.projectileSpeed("skeletal_stormcaller"), 1e-9);
        assertEquals(0, MinionAbilities.projectileSpeed("summon_skeletons"), 1e-9);
    }

    /** 2026-10-29 照 PoE2：多射的一律散開射出（照玩家的扇形），不管附近有沒有別的敵人。 */
    @Test
    void extraProjectilesAlwaysFireInASpread() {
        assertEquals(1, MinionProjectiles.spread(1, 0, 0, 1).size());
        final List<double[]> three = MinionProjectiles.spread(1, 0, 0, 3);
        assertEquals(3, three.size(), "1 ＋ 多射 2 發，全部射出");
        // 跟玩家一樣：本來一發的多了投射物，每多一發多 12°（3 發 → 24° 扇形），中間那發對準
        assertEquals(24, SkillMath.projectileSpread(3, 0), 1e-9);
        assertEquals(60, SkillMath.projectileSpread(20, 0), 1e-9, "最多 60°");
        assertEquals(30, SkillMath.projectileSpread(3, 30), 1e-9, "有寫扇形的照寫的");
        assertEquals(1, three.get(1)[0], 1e-9);
        assertEquals(0, three.get(1)[2], 1e-9);
        assertEquals(Math.cos(Math.toRadians(12)), three.get(0)[0], 1e-9);
        assertEquals(-Math.sin(Math.toRadians(12)), three.get(0)[2], 1e-9);
        assertEquals(Math.sin(Math.toRadians(12)), three.get(2)[2], 1e-9);
        for (final double[] d : three) {
            assertEquals(1, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-9, "單位向量");
        }
        // 飛的時間：射程 ÷ 速度（無條件進位、至少 1 tick）
        assertEquals(10, MinionProjectiles.ticks(28, 3.0));
        assertEquals(1, MinionProjectiles.ticks(0.1, 3.0));
        assertEquals(0.8, MinionProjectiles.FORK_DAMAGE, 1e-9, "分裂出來的跟玩家的一樣更少 20%");
    }

    /** 2026-10-29 照 PoE2：召喚物的偷取跟玩家的一樣：一份最多最大生命 10%、1 秒回完、每秒最多 20%，滿血結束。 */
    @Test
    void minionLeechRecoversOverTime() {
        final tw.exilecore.character.Leech leech = new tw.exilecore.character.Leech();
        final StatSheet sheet = new StatSheet();
        assertEquals(100, MinionTypes.addLeech(leech, 500, 1000), 1e-9, "一份最多最大生命的 10%");
        assertEquals(0, MinionTypes.addLeech(leech, 0, 1000), 1e-9);
        // 半秒回一半（不是一下回完）
        assertEquals(50, MinionTypes.recoverLeech(leech, 0.5, 1000, 100, sheet), 1e-6);
        assertEquals(50, leech.pending(), 1e-6);
        assertEquals(50, MinionTypes.recoverLeech(leech, 0.5, 1000, 150, sheet), 1e-6);
        assertEquals(0, leech.pending(), 1e-6);
        // 同時好幾份：每秒最多 20%（5 份各 100 → 1 秒只回 200，剩下的放慢、不會不見）
        for (int i = 0; i < 5; i++) {
            MinionTypes.addLeech(leech, 100, 1000);
        }
        assertEquals(200, MinionTypes.recoverLeech(leech, 1.0, 1000, 100, sheet), 1e-6);
        assertEquals(300, leech.pending(), 1e-6);
        // 最大偷取速度增加 50%：每秒最多 30%
        final StatSheet faster = new StatSheet().addAll(List.of(Modifier.increased(StatKeys.LEECH_RATE, 0.5, "測試")));
        assertEquals(300, MinionTypes.recoverLeech(leech, 1.0, 1000, 100, faster), 1e-6);
        // 快滿血：只回到滿，剩下的偷取結束
        MinionTypes.addLeech(leech, 100, 1000);
        MinionTypes.addLeech(leech, 100, 1000);
        assertEquals(30, MinionTypes.recoverLeech(leech, 1.0, 1000, 970, sheet), 1e-6);
        assertEquals(0, leech.pending(), 1e-9, "滿血：剩下的偷取結束");
        MinionTypes.addLeech(leech, 100, 1000);
        assertEquals(0, MinionTypes.recoverLeech(leech, 1.0, 1000, 1000, sheet), 1e-9, "本來就滿血不回");
        assertEquals(0, leech.pending(), 1e-9);
    }

    @Test
    void meleeSupportsFitMeleeMinionsOnly() {
        for (final String id : new String[] {"heavy_swing", "bludgeon", "bloodlust", "knockback"}) {
            assertNull(support(id).refusal(skill("summon_skeletons")), id + " 照 PoE2 可以輔助骸骨戰士");
            assertNull(support(id).refusal(skill("skeletal_reaver")), id);
            assertEquals("要近戰技能", support(id).refusal(skill("skeletal_archers")), id + "：射手不是近戰");
        }
        // 攻擊的（Close Combat、Brutality、Rapid Attacks、Life Leech）近戰、遠程都行
        for (final String id : new String[] {"close_quarters", "brutality", "faster_attacks", "life_leech", "execute", "ruthless"}) {
            assertNull(support(id).refusal(skill("summon_skeletons")), id);
            assertNull(support(id).refusal(skill("skeletal_archers")), id);
        }
        // 主人自己的機制照舊不行
        assertEquals("召喚物技能不行", support("rage").refusal(skill("summon_skeletons")), "怒火給的是你的怒氣");
        assertEquals("召喚物技能不行", support("spell_echo").refusal(skill("skeletal_stormcaller")));
        assertEquals("召喚物技能不行", support("cadence").refusal(skill("summon_skeletons")), "連拍是主人用技能的節奏");
        // 祭司不打人：只有施法速度、冷卻的吃得到
        assertNull(support("faster_casting").refusal(skill("skeletal_cleric")));
        assertNull(support("swift_cooldown").refusal(skill("skeletal_cleric")));
        assertEquals("召喚物不會造成傷害", support("considered_casting").refusal(skill("skeletal_cleric")));
        // 範圍：只有範圍真的有接上的召喚物（重卒的碎骨、狼的躍擊、射手的毒雲）
        assertNull(support("increased_area").refusal(skill("skeletal_brute")));
        assertNull(support("increased_area").refusal(skill("wolf_pack")));
        assertEquals("要範圍技能", support("increased_area").refusal(skill("summon_skeletons")));
        // 只聽指令的砲手：攻擊速度沒有作用
        assertEquals("召喚物的技能吃不到這顆輔助的效果", support("faster_attacks").refusal(skill("supporting_fire")));
    }

    @Test
    void supportsReachTheMinions() {
        // 投射物：射手掛貫穿、分光之後彈道多穿透 1 個、多射 2 發
        final SkillBuild plain = build("skeletal_archers");
        final SkillBuild boosted = build("skeletal_archers", support("pierce"), support("multiple_projectiles"), support("fork"));
        assertEquals(3, boosted.supports().size(), "三顆都生效");
        final Set<String> tags = MinionSheets.profile(boosted).tags();
        assertTrue(tags.contains(Tags.PROJECTILE));
        assertEquals(MinionTypes.Projectiles.NONE, MinionTypes.projectiles(minionEffective(plain), tags));
        final MinionTypes.Projectiles shot = MinionTypes.projectiles(minionEffective(boosted), tags);
        assertEquals(1, shot.pierce());
        assertEquals(2, shot.extra());
        assertTrue(shot.fork());
        assertTrue(MinionTypes.projectileLine(shot).contains("穿透"));
        // 電弧：連環多連鎖 1 次
        final SkillBuild storm = build("skeletal_stormcaller", support("chain"));
        assertEquals(1, MinionTypes.projectiles(minionEffective(storm), MinionSheets.profile(storm).tags()).chains());

        // 攻擊速度：迅擊掛骸骨戰士，召喚物每秒攻擊增加 15%
        final SkillBuild warriors = build("summon_skeletons");
        final SkillBuild fast = build("summon_skeletons", support("faster_attacks"));
        final double before = MinionSheets.attacksPerSecond(minionEffective(warriors), MinionSheets.profile(warriors));
        final double after = MinionSheets.attacksPerSecond(minionEffective(fast), MinionSheets.profile(fast));
        assertEquals(before * 1.15, after, 1e-9);
        // 沉錘：骸骨戰士的物理傷害更多 35%（攻擊更慢 10%）
        final SkillBuild heavy = build("summon_skeletons", support("heavy_swing"));
        final double plainHit = tw.exilecore.stats.DamageCalculator.expectedOutgoing(minionSheet(warriors), MinionSheets.profile(warriors)).total();
        final double heavyHit = tw.exilecore.stats.DamageCalculator.expectedOutgoing(minionSheet(heavy), MinionSheets.profile(heavy)).total();
        assertEquals(plainHit * 1.35, heavyHit, plainHit * 1e-6);
        // 長留：毒雲的持續時間（射手有 Duration）
        final SkillBuild lasting = build("skeletal_archers", support("prolonged_duration"));
        assertEquals(1.35, minionEffective(lasting).apply(StatKeys.SKILL_DURATION, 1.0, MinionSheets.profile(lasting).tags()), 1e-9);
    }

    @Test
    void unearthSupportsGoToTheConstructsOnly() {
        final SupportDefinition leech = support("life_leech");
        assertNull(leech.refusal(skill(Unearth.ID)), "飲血照 PoE2 可以輔助掘骸（骨構體的攻擊）");
        assertTrue(MinionTypes.throughMinions(leech, skill(Unearth.ID)));
        final SkillBuild spikes = build(Unearth.ID, leech);
        final SkillProfile profile = spikes.profile();
        assertEquals(0, profile.effective(new StatSheet()).flat(StatKeys.LEECH_LIFE, profile.tags()), 1e-9, "骨刺本身吃不到（只給召喚物）");
        final SkillBuild constructs = SkillBuild.of(config, skill(Unearth.CONSTRUCT), 20, 100, false, spikes.supports(), Set.of(Tags.WAND), new StatSheet());
        assertEquals(12, MinionSheets.profile(constructs).effective(new StatSheet()).flat(StatKeys.LEECH_LIFE,
                MinionSheets.profile(constructs).tags()), 1e-9, "骨構體偷取 12%");
    }

    @Test
    void conditionalDamageAndLeechForMinionHits() {
        final StatSheet sheet = new StatSheet();
        sheet.addAll(support("execute").modifiers());
        sheet.addAll(support("ambush").modifiers());
        final Set<String> tags = Set.of(Tags.ATTACK, Tags.MELEE, Tags.MINION, Tags.PHYSICAL);
        final MinionTypes.Target healthy = new MinionTypes.Target(false, false, false, false, false, false, 10, Set.of(), false);
        assertTrue(MinionTypes.conditional(sheet, tags, healthy).isEmpty());
        final MinionTypes.Target low = new MinionTypes.Target(true, false, false, false, false, false, 10, Set.of(), false);
        final List<Modifier> vsLow = MinionTypes.conditional(sheet, tags, low);
        assertEquals(1, vsLow.size());
        assertEquals(StatKeys.DAMAGE, vsLow.getFirst().stat());
        assertEquals(0.4, vsLow.getFirst().value(), 1e-9, "處刑：對低血的敵人增加 40%");
        final MinionTypes.Target full = new MinionTypes.Target(false, true, false, false, false, false, 10, Set.of("ignited"), false);
        assertTrue(MinionTypes.conditional(sheet, tags, full).stream().anyMatch(m -> m.stat().equals(StatKeys.CRIT_CHANCE)), "潛鋒：對滿血的敵人暴擊率");

        final StatSheet leech = new StatSheet().addAll(support("life_leech").modifiers());
        final SkillProfile profile = new SkillProfile("t.minion", tags, 1.0, null);
        final DamagePacket dealt = new DamagePacket().set(DamageType.PHYSICAL, 100).set(DamageType.FIRE, 50);
        assertEquals(12, MinionTypes.leech(leech, profile, dealt), 1e-9, "物理傷害的 12%（火焰不算）");
    }
}
