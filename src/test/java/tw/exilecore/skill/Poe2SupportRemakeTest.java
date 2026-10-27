package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportKeys;
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

/** 2026-10-26：照 PoE2 0.5 重做的輔助寶石（I／II／III 拆開、數值、條件效果）。 */
class Poe2SupportRemakeTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    private static double flat(final SupportDefinition support, final String stat) {
        double sum = 0;
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().equals(stat)) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    @Test
    void romanNumeralsKeepOnlyTheHighestTier() {
        // 2026-10-26 照站長「輔助寶石有分 I II III 的一律保留最高的」：較低階的定義刪掉，併入同系列最高階（supports.yml 的 merged）；
        //   這次之前 I／II／III 是分開的寶石（I 用原本的代號）
        final java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        merged.put("searing", "searing_ii");
        merged.put("lacerate", "lacerate_iii");
        merged.put("lacerate_ii", "lacerate_iii");
        merged.put("envenom", "envenom_iii");
        merged.put("envenom_ii", "envenom_iii");
        merged.put("culling_strike", "culling_strike_ii");
        merged.put("enraged_warcry", "enraged_warcry_ii");
        merged.put("shatter_armour", "shatter_armour_iii");
        merged.put("shatter_armour_ii", "shatter_armour_iii");
        merged.put("pinning", "pinning_iii");
        merged.put("pinning_ii", "pinning_iii");
        merged.put("swift_affliction", "swift_affliction_iii");
        merged.put("swift_affliction_ii", "swift_affliction_iii");
        merged.put("mark_for_death", "mark_for_death_ii");
        merged.put("infernal_legion", "infernal_legion_ii");
        merged.put("urgent_totems", "urgent_totems_iii");
        merged.put("urgent_totems_ii", "urgent_totems_iii");
        merged.put("longshot", "longshot_ii");
        merged.put("ice_bite", "ice_bite_ii");
        // 2026-10-27：上次漏掉的三組（Prolonged Duration I、Cooldown Recovery I、Multishot I）
        merged.put("increased_duration", "prolonged_duration");
        merged.put("cooldown_recovery", "swift_cooldown");
        merged.put("scattershot", "multiple_projectiles");
        // 2026-10-27：Bleed 只做了 I，改成照最高階 Bleed II（PoE2 第 3 階 → 2、消耗 100%、流血機率 75%）；這次之前初裂 tier 1、50%
        assertEquals("初裂 II", support("first_blood").name());
        assertEquals(2, support("first_blood").tier());
        assertEquals(75, flat(support("first_blood"), StatKeys.BLEED_CHANCE));
        merged.forEach((low, top) -> {
            assertNull(config.support(low), low + " 刪掉了");
            assertEquals(top, config.supportMergedInto(low), low);
            assertNotNull(config.supportMergeNotice(low), low);
        });
        assertEquals("割裂 II已經併入割裂 III", config.supportMergeNotice("lacerate_ii"));
        // 留下來的最高階：名稱照舊（帶 II／III），數值照 PoE2 那一階
        assertEquals("割裂 III", support("lacerate_iii").name());
        assertEquals(100, flat(support("lacerate_iii"), StatKeys.BLEED_CHANCE));
        assertEquals(3, support("lacerate_iii").tier());
        assertEquals(60, flat(support("envenom_iii"), StatKeys.POISON_CHANCE));
        assertEquals(20, flat(support("shatter_armour_iii"), StatKeys.ARMOUR_BREAK));
        for (final String id : List.of("searing_ii", "culling_strike_ii", "pinning_iii", "swift_affliction_iii",
                "mark_for_death_ii", "infernal_legion_ii", "urgent_totems_iii", "longshot_ii", "ice_bite_ii", "enraged_warcry_ii",
                "tireless")) {
            assertTrue(support(id).cuttable(), id + " 照輔助的掉落規則（未切割輔助寶石切得出來）");
        }
        // 2026-10-27 照 PoE2：血脈輔助切不出來，只有怪物、Boss 會掉（supports_lineage.yml）；這次之前這 4 顆也切得出來
        for (final String id : List.of("bhatairs_vengeance", "paquates_pact", "zerphis_infamy", "vruuns_aftermath")) {
            assertTrue(support(id).lineage() && !support(id).cuttable(), id + " 是血脈輔助（切不出來）");
        }
        assertEquals("怒嘯 II", support("enraged_warcry_ii").name());
        assertFalse(support("raging_cry").name().equals(support("enraged_warcry_ii").name()), "怒嘯不再撞名");
    }

    @Test
    void mergedSupportsMoveOverOnSkillGems() {
        // 2026-10-26：技能寶石、面板上掛著的低階輔助原孔換成最高階（共鳴保留）；同一個技能已經掛著最高階那顆的拆下來
        final tw.exilecore.character.SkillLoadout.Entry entry = new tw.exilecore.character.SkillLoadout.Entry("fireball", 12, 4,
                List.of("searing", "envenom_ii", "envenom_iii", "faster_casting"), List.of(true, false, false, false));
        final List<GemData> detached = new java.util.ArrayList<>();
        final tw.exilecore.character.SkillLoadout.Entry moved = config.mergeSupports(entry, detached);
        assertEquals("searing_ii", moved.support(0));
        assertTrue(moved.resonant(0), "共鳴保留");
        assertEquals("", moved.support(1), "已經掛著淬毒 III：淬毒 II 拿下來");
        assertEquals("envenom_iii", moved.support(2));
        assertEquals("faster_casting", moved.support(3));
        assertEquals(12, moved.level());
        assertEquals(4, moved.sockets());
        assertEquals(List.of(GemData.support("envenom_iii")), detached);
        // 沒有要換的：同一個物件
        final tw.exilecore.character.SkillLoadout.Entry clean = new tw.exilecore.character.SkillLoadout.Entry("fireball", 1, 2,
                List.of("searing_ii", ""));
        assertTrue(clean == config.mergeSupports(clean, new java.util.ArrayList<>()));
        // 舊存檔讀回來的輔助寶石照樣讀得到代號（換成哪顆由 GemService.migrateRemoved 決定）
        assertEquals("lacerate", GemData.decode("support;lacerate;0;resonant").orElseThrow().id());
    }

    @Test
    void searingFlameOnlyReducesTheHit() {
        // 2026-10-26：燎原 I 併入燎原 II（擊中更少 30%、點燃更多 100%）；這次之前測燎原 I（25%、75%）
        final SupportDefinition searing = support("searing_ii");
        final StatSheet sheet = new StatSheet().overlay(searing.modifiers());
        assertEquals(0.70, SupportMechanics.hitOnlyFactor(sheet, Set.of(Tags.ATTACK)), 1e-9);
        assertEquals(2.0, sheet.more(StatKeys.IGNITE_MAGNITUDE, Set.of()), 1e-9);
        assertEquals(0, flat(searing, StatKeys.IGNITE_CHANCE), "燎原不加點燃機率（那是引燃）");
        final DamagePacket dealt = new DamagePacket();
        dealt.add(DamageType.FIRE, 100);
        final HitResult raw = new HitResult(true, false, false, new DamagePacket(dealt), dealt, 0, 100);
        final HitResult hit = SupportMechanics.scaled(raw, 0.70);
        assertEquals(70, hit.total(), 1e-9, "擊中更少 30%");
        assertEquals(100, raw.dealt().get(DamageType.FIRE), 1e-9, "異常照原本的那份算");
    }

    @Test
    void heftAndEmbitterChangeTheDamageCalculation() {
        final StatSheet sheet = new StatSheet()
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "斧"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 20, Set.of(Tags.PHYSICAL), "斧"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1.0, "斧"));
        final SkillDefinition strike = config.skill("boneshatter");
        final SkillBuild base = SkillBuild.of(config, strike, 1, 1, false, List.of(), Set.of(Tags.MACE), sheet);
        final SkillBuild heft = SkillBuild.of(config, strike, 1, 1, false, List.of(support("heft")), Set.of(Tags.MACE), sheet);
        final double before = DamageCalculator.expectedOutgoing(sheet, base.profile()).get(DamageType.PHYSICAL);
        final double after = DamageCalculator.expectedOutgoing(sheet, heft.profile()).get(DamageType.PHYSICAL);
        assertEquals(before * (10 + 26) / 30.0, after, 1e-6, "沉重：最大 20 → 26，最小不變");
        final SkillBuild cold = SkillBuild.of(config, strike, 1, 1, false, List.of(support("added_fire"), support("embitter")), Set.of(Tags.MACE), sheet);
        final DamagePacket packet = DamageCalculator.expectedOutgoing(sheet, cold.profile());
        assertEquals(0, packet.get(DamageType.FIRE), 1e-9, "凝霜刃：額外的火焰改成冰冷");
        assertTrue(packet.get(DamageType.COLD) > 0);
    }

    @Test
    void supportsAttachWhereTheOriginalAllows() {
        // 冰嚙照 PoE2：攻擊、法術、戰吼都能掛
        // 2026-10-26：低階併入最高階（冰嚙 II、斷命 II、怒嘯 II、釘地 III）
        assertNull(support("ice_bite_ii").refusal(config.skill("boneshatter")));
        assertNull(support("ice_bite_ii").refusal(config.skill("arctic_howl")));
        // 斷命照 PoE2 只能掛攻擊
        assertEquals("要攻擊技能", support("culling_strike_ii").refusal(config.skill("fireball")));
        // 骰運掛在主要寶石上
        assertNull(support("fluke").refusal(config.skill("totem_casting")), "骰運能掛主要寶石");
        assertEquals("要主要寶石技能", support("fluke").refusal(config.skill("fireball")));
        // 捐命、骸舞只有祭品
        assertNull(support("sacrificial_offering").refusal(config.skill("blood_offering")));
        assertEquals("要祭品技能", support("sacrificial_offering").refusal(config.skill("infernal_cry")));
        // 戰吼輔助不能掛在戰旗
        for (final String id : List.of("battershout", "tireless", "paquates_pact", "vruuns_aftermath", "echoing_cry", "enraged_warcry_ii")) {
            assertEquals("要戰吼技能", support(id).refusal(config.skill("war_banner")), id);
        }
        // 不竭嘯、決嘯、碎晶嘯要會強化攻擊的戰吼
        assertEquals("要會強化攻擊的戰吼", support("tireless").refusal(config.skill("lunar_blessing")));
        assertNull(support("tireless").refusal(config.skill("infernal_cry")));
        // 釘地不再限投射物
        assertNull(support("pinning_iii").refusal(config.skill("boneshatter")));
        assertEquals(1, flat(support("pinning_iii"), SupportKeys.FLAG_PIN_BUILDUP));
    }

    @Test
    void zoneDotMultiplierReadsOnlyTheSkillsOwnModifiers() {
        final SkillDefinition zone = config.skill("shield_wall");
        final SkillBuild build = SkillBuild.of(config, zone, 1, 1, false, List.of(support("swift_affliction_iii")), Set.of(Tags.MACE), new StatSheet());
        // 2026-10-26：急蝕 I 併入急蝕 III（不到 1 秒的非異常持續傷害更多 50%）；這次之前測急蝕 I（非異常持續傷害更多 30%）
        assertEquals(1.0, SkillZones.localMore(build.profile(), SupportKeys.DAMAGE_SKILL_DOT), 1e-9);
        assertEquals(1.5, SkillZones.localMore(build.profile(), SupportKeys.DAMAGE_SKILL_DOT_SHORT), 1e-9);
        assertEquals(1.0, SkillZones.localMore(build.profile(), SupportKeys.DOT_VS_FULL_LIFE), 1e-9);
    }
}
