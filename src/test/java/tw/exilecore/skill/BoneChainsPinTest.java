package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentText;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.PinKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeNewMechanicKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2 Bone Chains（骸骨連鎖，physical_spell_damage_can_pin_on_critical_hit；PoB2 ModParser：CanPin、ModFlag.Spell、
 * KeywordFlag.Physical、Condition CriticalStrike）：帶物理標籤的法術暴擊時累積定身，所有傷害類型都算；骨裂（cannot_pin）蓋過。
 * 站長回報：縛根（物理法術）點了骸骨連鎖不會定身、面板也沒有定身這一行。
 */
class BoneChainsPinTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static final Set<String> PHYSICAL_SPELL = Set.of(Tags.SPELL, Tags.PHYSICAL, Tags.AREA);

    @Test
    void onlyPhysicalSpellCritsBuildPin() {
        final PinKeys.Source source = PinKeys.source(false, true, 0, PHYSICAL_SPELL, false);
        assertEquals(PinKeys.Source.SPELL_CRIT, source);
        assertTrue(source.builds(true), "暴擊才累積");
        assertFalse(source.builds(false), "沒暴擊不累積");
        assertTrue(source.allDamage(), "CanPin 沒限定傷害類型：轉成元素的也算");
        assertEquals(PinKeys.Source.NONE, PinKeys.source(false, true, 0, Set.of(Tags.SPELL, Tags.COLD), false), "沒有物理標籤的法術不算");
        assertEquals(PinKeys.Source.NONE, PinKeys.source(false, true, 0, Set.of(Tags.ATTACK, Tags.PHYSICAL, Tags.MELEE), false), "攻擊不算");
        assertEquals(PinKeys.Source.NONE, PinKeys.source(false, false, 0, PHYSICAL_SPELL, false), "沒點天賦");
        assertEquals(PinKeys.Source.NONE, PinKeys.source(false, true, 0, PHYSICAL_SPELL, true), "骨裂：不能定身蓋過");
        assertEquals(PinKeys.Source.NONE, PinKeys.source(true, true, 30, Set.of(Tags.ATTACK, Tags.PROJECTILE), true), "不能定身蓋過所有來源");
    }

    @Test
    void sourcesDoNotStack() {
        assertEquals(PinKeys.Source.EVERY_HIT, PinKeys.source(true, true, 0, PHYSICAL_SPELL, false), "擊中必定定身＋骸骨連鎖：每一下都累積（一次，不疊）");
        final PinKeys.Source projectile = PinKeys.source(false, false, 30, Set.of(Tags.ATTACK, Tags.PROJECTILE, Tags.PHYSICAL), false);
        assertEquals(PinKeys.Source.PROJECTILE_ATTACK, projectile);
        assertTrue(projectile.builds(false));
        assertFalse(projectile.allDamage(), "投射物攻擊照舊只算物理");
        assertEquals(PinKeys.Source.NONE, PinKeys.source(false, false, 30, Set.of(Tags.SPELL, Tags.PROJECTILE, Tags.PHYSICAL), false),
                "pin.chance 只給投射物攻擊");
        final DamagePacket packet = new DamagePacket();
        packet.set(DamageType.PHYSICAL, 30);
        packet.set(DamageType.COLD, 70);
        assertEquals(100, PinKeys.pinDamage(PinKeys.Source.SPELL_CRIT.allDamage(), packet), 1e-9, "縛根部分轉冰：冰冷也算進累積");
    }

    @Test
    void entangleFissureAndVinesArePhysicalSpells() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(TreeNewMechanicKeys.FLAG_SPELL_CRIT_PIN, 1, "骸骨連鎖"));
        final SkillBuild entangle = SkillBuild.of(config, config.skill(EntangleRoots.ID), 20, 90, false, List.of(), Set.of(), sheet);
        // SkillExecutor.zone：裂隙、藤蔓都是 build.profile().scaled(...)（只多一條傷害更多，標籤不變）
        for (final SkillProfile hit : List.of(entangle.profile().scaled(1.0, "裂隙"),
                entangle.profile().scaled(EntangleRoots.vineRatio(20), "藤蔓"))) {
            final StatSheet effective = hit.effective(sheet);
            final PinKeys.Source source = PinKeys.source(StatKeys.flag(effective, PinKeys.FLAG_HITS_PIN),
                    StatKeys.flag(effective, TreeNewMechanicKeys.FLAG_SPELL_CRIT_PIN), effective.flat(StatKeys.PIN_CHANCE, hit.tags()), hit.tags(),
                    StatKeys.flag(effective, tw.exilecore.stats.SupportExtraKeys.FLAG_CANNOT_PIN));
            assertEquals(PinKeys.Source.SPELL_CRIT, source, hit.tags().toString());
        }
        final List<String> lines = AilmentText.pinLines(entangle.profile().effective(sheet), entangle.profile().tags());
        assertEquals(List.of("<#c9a27e>定身<gray> 暴擊時累積（物理法術；所有傷害類型都算）"), lines);
        assertEquals(List.of(), AilmentText.pinLines(entangle.profile().effective(new StatSheet()), entangle.profile().tags()), "沒點天賦不列");
    }

    @Test
    void tooltipShowsPinScaling() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(TreeNewMechanicKeys.FLAG_SPELL_CRIT_PIN, 1, "骸骨連鎖"));
        sheet.add(Modifier.increased(tw.exilecore.stats.TreeKeys.PIN_BUILDUP, 0.3, "釘身累積"));
        sheet.add(Modifier.increased(tw.exilecore.stats.SupportKeys.PIN_DURATION, 0.2, "釘身持續時間"));
        assertEquals(List.of("<#c9a27e>定身<gray> 暴擊時累積（物理法術；所有傷害類型都算）、累積 ×1.30、持續 增加 20%"),
                AilmentText.pinLines(sheet, PHYSICAL_SPELL));
        final StatSheet shrapnel = sheet.overlay(List.of(Modifier.flat(tw.exilecore.stats.SupportExtraKeys.FLAG_CANNOT_PIN, 1, "骨裂")));
        assertEquals(List.of(), AilmentText.pinLines(shrapnel, PHYSICAL_SPELL), "骨裂：不會定身，不列");
    }
}
