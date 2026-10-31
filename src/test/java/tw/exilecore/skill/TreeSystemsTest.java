package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeMechanicKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26：PoE2 0.5 天賦樹的「系統」類天賦（夥伴、強化攻擊、戰旗、招架、引導、觸發、危害、植物、引爆、弩台、祭品、
 * 存在範圍內的友軍、戰星補滿、殘影、灌注）真的生效：標籤接得上、程式有讀。
 */
class TreeSystemsTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static MonsterConfig monsters;
    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
        tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
    }

    private static MinionSheets.Base base(final int level) {
        final double[] damage = monsters.damageAt(level);
        return new MinionSheets.Base(monsters.lifeAt(level), monsters.accuracyAt(level), monsters.evasionAt(level),
                monsters.armourAt(level), damage[0], damage[1], monsters.critChance());
    }

    private static SkillBuild build(final String skill, final StatSheet sheet, final Set<String> weapons) {
        return SkillBuild.of(config, config.skill(skill), 10, 100, false, List.of(), weapons, sheet);
    }

    /** 樹上所有修飾器的標籤組合。 */
    private static Set<Set<String>> treeTags(final String stat) {
        final Set<Set<String>> all = new HashSet<>();
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : node.modifiers()) {
                if (modifier.stat().equals(stat)) {
                    all.add(modifier.tags());
                }
            }
        }
        return all;
    }

    private static boolean treeHas(final String stat) {
        return tree.nodes().stream().anyMatch(n -> n.modifiers().stream().anyMatch(m -> m.stat().equals(stat)));
    }

    @Test
    void companionModifiersOnlyReachCompanions() {
        final Set<String> companion = Set.of(Tags.MINION, TreeMechanicKeys.COMPANION);
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.50, companion, "夥伴傷害"));
        owner.add(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 0.20, companion, "夥伴生命"));
        owner.add(new Modifier(StatKeys.RESIST_FIRE, ModType.FLAT, 12, companion, "夥伴抗性"));
        owner.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.30, Set.of(Tags.MINION), "召喚物傷害"));
        final List<Modifier> copied = MinionSheets.ownerModifiers(owner);
        assertEquals(4, copied.size());

        final SkillDefinition wolf = config.skill("wild_beast_taming");
        final SkillDefinition zombie = config.skill("corpse_summon");
        assertTrue(wolf.tags().contains(TreeMechanicKeys.COMPANION), "荒原狼伴是夥伴");
        assertTrue(config.skill("grey_wolf_escort").tags().contains(TreeMechanicKeys.COMPANION), "PoE2 的狼群也是夥伴");
        assertFalse(zombie.tags().contains(TreeMechanicKeys.COMPANION));

        final MinionSheets.Base base = base(40);
        final StatSheet wolfSheet = MinionSheets.build(wolf.summon(), base,
                MinionSheets.forMinion(copied, MinionSheets.conditions(wolf, false)));
        assertEquals(0.80, wolfSheet.increased(StatKeys.DAMAGE, MinionSheets.CONTEXT), 1e-9, "夥伴：召喚物 30% ＋ 夥伴 50%");
        assertEquals(12, wolfSheet.flat(StatKeys.RESIST_FIRE, Set.of()), 1e-9, "夥伴的抗性用空的標籤讀得到");
        assertEquals(base.life() * wolf.summon().life() * 1.2, MinionSheets.life(wolfSheet), 1e-6);

        final StatSheet zombieSheet = MinionSheets.build(zombie.summon(), base,
                MinionSheets.forMinion(copied, MinionSheets.conditions(zombie, true)));
        assertEquals(0.30, zombieSheet.increased(StatKeys.DAMAGE, MinionSheets.CONTEXT), 1e-9, "不是夥伴的召喚物只吃召喚物的");
        assertEquals(0, zombieSheet.flat(StatKeys.RESIST_FIRE, Set.of()), 1e-9);
        // 沒挑過的（還帶著 companion 標籤）一律當條件不成立
        assertEquals(0.30, MinionSheets.build(wolf.summon(), base, copied).increased(StatKeys.DAMAGE, MinionSheets.CONTEXT), 1e-9);

        // 樹上的「夥伴…」天賦帶 [minion, companion]
        assertTrue(treeTags(StatKeys.DAMAGE).contains(companion));
        assertTrue(treeTags(StatKeys.LIFE_MAX).contains(companion));
        assertTrue(treeTags(StatKeys.RESIST_COLD).contains(companion));
        assertTrue(treeTags(StatKeys.AURA_RESERVATION).contains(Set.of(TreeMechanicKeys.COMPANION)));
    }

    @Test
    void alliesInPresenceOnlyWhileInRange() {
        final Set<String> presence = Set.of(Tags.MINION, TreeMechanicKeys.IN_PRESENCE);
        final List<Modifier> mods = List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.08, presence, "存在範圍"));
        final SkillDefinition zombie = config.skill("corpse_summon");
        final List<Modifier> near = MinionSheets.forMinion(mods, MinionSheets.conditions(zombie, true));
        assertEquals(1, near.size());
        assertEquals(Set.of(Tags.MINION), near.getFirst().tags(), "範圍內：拿掉 in_presence");
        assertTrue(MinionSheets.forMinion(mods, MinionSheets.conditions(zombie, false)).isEmpty(), "範圍外不給");
        assertTrue(treeTags(StatKeys.DAMAGE).contains(presence));
        final StatSheet sheet = new StatSheet();
        assertEquals(tw.exilecore.combat.Presence.BASE_RADIUS, tw.exilecore.combat.Presence.radius(sheet), 1e-9);
        sheet.add(Modifier.increased(StatKeys.PRESENCE_AREA, 0.44, "測試"));
        assertEquals(tw.exilecore.combat.Presence.BASE_RADIUS * 1.2, tw.exilecore.combat.Presence.radius(sheet), 1e-9);
    }

    @Test
    void skillsCarryTheSystemTags() {
        for (final String banner : List.of("bedrock_flag", "campaign_flag", "foe_daunt_flag")) {
            assertTrue(config.skill(banner).tags().containsAll(Set.of(TreeMechanicKeys.BANNER, Tags.AREA, Tags.DURATION)), banner);
        }
        for (final String offering : List.of("bloodthirst_rite", "bone_rite", "soulfire_rite")) {
            assertTrue(config.skill(offering).tags().containsAll(Set.of(TreeMechanicKeys.OFFERING, Tags.AREA, Tags.DURATION)), offering);
        }
        assertTrue(config.skill("skyward_crossbow_turret").tags().containsAll(Set.of(TreeMechanicKeys.BALLISTA, Tags.TOTEM)));
        assertTrue(config.skill("nailfall_crossbow_turret").tags().containsAll(Set.of(TreeMechanicKeys.BALLISTA, Tags.TOTEM)));
        assertTrue(config.skill("proc_parry_sweep").tags().contains(TreeMechanicKeys.PARRY));
        assertTrue(config.skill("thornburr_steps").tags().contains(TreeMechanicKeys.HAZARD));
        assertTrue(config.skill("proc_caltrops").tags().contains(TreeMechanicKeys.HAZARD));
        assertTrue(config.skill("spear_formation").tags().contains(TreeMechanicKeys.HAZARD));
        assertTrue(config.skill("thorn_shaft").tags().contains(TreeMechanicKeys.PLANT));
        assertTrue(config.skill("corpse_burst").tags().contains(TreeMechanicKeys.DETONATOR));
        assertTrue(config.skill("burning_current").tags().contains(TreeMechanicKeys.CHANNELLING));
        assertTrue(config.skill("cloud_pierce_arrow").tags().contains(TreeMechanicKeys.CHANNELLING));
        assertEquals("夥伴", ModifierText.tagName(TreeMechanicKeys.COMPANION));
    }

    @Test
    void bannerAreaDurationAndEffectFromTheTree() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.SKILL_AREA, ModType.INCREASED, 0.44, Set.of(TreeMechanicKeys.BANNER), "戰旗範圍"));
        sheet.add(new Modifier(StatKeys.SKILL_DURATION, ModType.INCREASED, 0.20, Set.of(TreeMechanicKeys.BANNER), "戰旗持續"));
        sheet.add(new Modifier(StatKeys.WARCRY_EFFECT, ModType.INCREASED, 0.12, Set.of(TreeMechanicKeys.BANNER), "戰旗效果"));
        final int plainCooldown = build("campaign_flag", new StatSheet(), Set.of(Tags.MACE)).cooldownTicks();
        sheet.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.INCREASED, 0.25, Set.of(TreeMechanicKeys.BANNER), "榮耀獲得（當作冷卻回復）"));
        final SkillBuild banner = build("campaign_flag", sheet, Set.of(Tags.MACE));
        assertEquals(Math.round(plainCooldown / 1.25), banner.cooldownTicks(), 1);
        assertTrue(treeTags(StatKeys.COOLDOWN_RECOVERY).contains(Set.of(TreeMechanicKeys.BANNER)));
        assertEquals(1.2, banner.areaScale(), 1e-9);
        // 2026-10-28 照 PoE2：戰旗持續 6 → 9.8 秒寫成逐級的「增加持續時間」（10 級 +30%），跟樹上的 +20% 加在一起；這次之前 1.2
        assertEquals(1.5, banner.durationScale(), 1e-9);
        assertEquals(1.12, banner.auraEffect(), 1e-9);
        final SkillBuild cry = build("hellfire_war_shout", sheet, Set.of(Tags.MACE));
        assertEquals(1.0, cry.durationScale(), 1e-9, "一般的戰吼不吃戰旗的");
        assertTrue(treeTags(StatKeys.SKILL_AREA).contains(Set.of(TreeMechanicKeys.BANNER)));
        assertTrue(treeTags(StatKeys.WARCRY_EFFECT).contains(Set.of(TreeMechanicKeys.BANNER)));
    }

    @Test
    void ballistaTotemLimitAndTotemSkillSpeed() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.SKILL_TOTEMS, ModType.FLAT, 1, Set.of(Tags.ATTACK, TreeMechanicKeys.BALLISTA), "弩台上限"));
        final SkillBuild ballista = build("skyward_crossbow_turret", sheet, Set.of(Tags.CROSSBOW, Tags.RANGED, Tags.TWO_HAND));
        final SkillBuild plain = build("skyward_crossbow_turret", new StatSheet(), Set.of(Tags.CROSSBOW, Tags.RANGED, Tags.TWO_HAND));
        assertEquals(plain.totems() + 1, ballista.totems(), "+1 弩台");
        assertTrue(treeTags(StatKeys.DAMAGE).contains(Set.of(Tags.TOTEM, TreeMechanicKeys.BALLISTA)));
        assertTrue(treeHas(StatKeys.SKILL_TOTEMS));
    }

    @Test
    void empoweredTriggeredAndInvocationTags() {
        assertEquals(Set.of(TreeMechanicKeys.TRIGGERED), SkillService.triggeredTags(Set.of(Tags.META, Tags.PERSISTENT)));
        assertEquals(Set.of(TreeMechanicKeys.TRIGGERED, TreeMechanicKeys.INVOCATION),
                SkillService.triggeredTags(config.skill("element_prayer_star").tags())); // 2026-10-26：照 PoE2 異變引星不是 Invocation，改用元祈引星
        assertTrue(treeTags(StatKeys.DAMAGE).contains(Set.of(Tags.ATTACK, TreeMechanicKeys.EMPOWERED)));
        assertTrue(treeTags(StatKeys.DAMAGE).contains(Set.of(Tags.SPELL, TreeMechanicKeys.TRIGGERED)));
        assertTrue(treeTags(StatKeys.DAMAGE).contains(Set.of(TreeMechanicKeys.INVOCATION)));
        assertTrue(treeTags(StatKeys.DAMAGE).contains(Set.of(TreeMechanicKeys.CHANNELLING)));
        // 強化攻擊的那一下（戰吼強化補上 empowered）才吃
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.08, Set.of(Tags.ATTACK, TreeMechanicKeys.EMPOWERED), "強化攻擊"));
        assertEquals(0, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.MELEE)), 1e-9);
        assertEquals(0.08, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.MELEE, TreeMechanicKeys.EMPOWERED)), 1e-9);
    }

    @Test
    void blindHinderAndExposureFromTheTree() {
        assertEquals(100, tw.exilecore.combat.HitEffects.scaledTicks(80, 0.25), "致盲 4 秒增加 25%");
        assertEquals(96, tw.exilecore.combat.HitEffects.scaledTicks(tw.exilecore.combat.HitEffects.HINDER_TICKS, 0.2));
        assertEquals(2, tw.exilecore.combat.HitEffects.blindModifiers(0).size());
        final List<Modifier> blind = tw.exilecore.combat.HitEffects.blindModifiers(-0.15);
        assertEquals(3, blind.size());
        final StatSheet enemy = new StatSheet().addAll(blind);
        assertEquals(-0.15, enemy.increased(StatKeys.CRIT_CHANCE, Set.of()), 1e-9, "被致盲的敵人暴擊率降低");
        for (final String stat : List.of(TreeMechanicKeys.BLIND_ENEMY_CRIT, TreeMechanicKeys.EXPOSURE_EXTRA, TreeMechanicKeys.HINDER_DURATION,
                TreeMechanicKeys.SEAL_FREQUENCY, StatKeys.FLAG_MAIM_IMMUNE)) {
            assertTrue(treeHas(stat), "樹上要有 " + stat);
        }
    }

    @Test
    void metaEnergyReadsThePlayersSheet() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.META_ENERGY_MAX, ModType.INCREASED, 0.30, Set.of(TreeMechanicKeys.INVOCATION), "Invocation 最大能量"));
        sheet.add(Modifier.increased(StatKeys.META_ENERGY_GAIN, 0.15, "主要寶石能量"));
        sheet.add(Modifier.more(StatKeys.META_ENERGY_GAIN, 0.35, "更多能量"));
        sheet.add(Modifier.flat(StatKeys.META_ENERGY_REFUND, 25, "退還"));
        final double[] invocation = MetaGems.sheetEnergy(sheet, config.skill("element_prayer_star").tags()); // 2026-10-26：照 PoE2 改用元祈引星（異變引星不是 Invocation）
        assertEquals(1.15 * 1.35, invocation[0], 1e-9);
        assertEquals(1.30, invocation[1], 1e-9);
        assertEquals(25, invocation[2], 1e-9);
        assertEquals(1.0, MetaGems.sheetEnergy(sheet, config.skill("critical_guide_star").tags())[1], 1e-9, "暴擊引星不是 Invocation");
    }

    @Test
    void parryRemnantInfusionAndCharges() {
        final StatSheet sheet = new StatSheet();
        assertEquals(0.5, ChargeEffects.parriedTaken(sheet), 1e-9);
        assertEquals(40, ChargeEffects.parriedTicks(sheet));
        sheet.add(Modifier.increased(TreeMechanicKeys.PARRIED_EFFECT, 0.25, "被招架效果"));
        sheet.add(Modifier.increased(TreeMechanicKeys.PARRIED_DURATION, 0.5, "被招架持續"));
        assertEquals(0.625, ChargeEffects.parriedTaken(sheet), 1e-9);
        assertEquals(60, ChargeEffects.parriedTicks(sheet));

        assertEquals(0.2, ChargeEffects.jaggedSlow(0), 1e-9);
        assertEquals(0.23, ChargeEffects.jaggedSlow(0.15), 1e-9, "碎地效果增加 15%");
        assertTrue(treeHas(TreeMechanicKeys.JAGGED_EFFECT));

        sheet.add(Modifier.increased(TreeMechanicKeys.REMNANT_RANGE, 0.5, "殘影距離"));
        assertEquals(ChargeEffects.REMNANT_PICKUP * 1.5, ChargeEffects.remnantPickup(sheet), 1e-9);

        assertEquals(SkillResources.INFUSION_DURATION, SkillResources.infusionTicks(new StatSheet()));
        sheet.add(Modifier.increased(TreeMechanicKeys.INFUSION_DURATION, 0.1, "灌注持續"));
        assertEquals(Math.round(SkillResources.INFUSION_DURATION * 1.1), SkillResources.infusionTicks(sheet));
        sheet.add(Modifier.flat(StatKeys.INFUSION_MAX, 1, "灌注上限"));
        sheet.add(Modifier.flat(TreeMechanicKeys.INFUSION_MAX_ELEMENT + "fire", 1, "火焰灌注上限"));
        assertEquals(SkillResources.INFUSION_BASE_MAX + 2, SkillResources.infusionMax(sheet, 0));
        assertEquals(SkillResources.INFUSION_BASE_MAX + 1, SkillResources.infusionMax(sheet, 1));

        assertFalse(ChargeService.fills(sheet, "power", 0.0), "沒有補滿的機率");
        sheet.add(Modifier.flat(TreeMechanicKeys.CHARGE_FILL + "power", 2, "補滿"));
        assertTrue(ChargeService.fills(sheet, "power", 0.019));
        assertFalse(ChargeService.fills(sheet, "power", 0.021));
        assertFalse(ChargeService.fills(sheet, "frenzy", 0.0));

        for (final String stat : List.of(TreeMechanicKeys.PARRIED_EFFECT, TreeMechanicKeys.REMNANT_RANGE, TreeMechanicKeys.INFUSION_DURATION,
                "charge.extra.power", "ailment.self_duration.poison")) {
            assertTrue(treeHas(stat), "樹上要有 " + stat);
        }
    }
}
