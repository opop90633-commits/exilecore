package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2：噴炎柱、魔焰爆、播火爆、荊棘叢生、腐血嘯、扭曲契約、刺爆針的傷害基礎與做法（ProcBasis）。 */
class ProcBasisTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void flamePillarIsQuarterOfRemainingIgnite() {
        assertEquals(0.25 * 100 * 3, ProcBasis.flamePillarBase(100, 60), 1e-9, "每秒 100、剩 3 秒 → 75");
        assertEquals(0, ProcBasis.flamePillarBase(100, -5), 1e-9, "已經結束");
    }

    @Test
    void manaFlareIsQuarterOfCurrentMana() {
        assertEquals(50, ProcBasis.manaFlareBase(200), 1e-9);
        assertEquals(0, ProcBasis.manaFlareBase(-1), 1e-9);
    }

    @Test
    void flamePillarCapPerUse() {
        assertTrue(ProcBasis.pillarRoom(9));
        assertFalse(ProcBasis.pillarRoom(10), "每次使用最多 10 道");
    }

    @Test
    void delayedAndRearmedBrambles() {
        assertEquals(160, ProcBasis.brambleLifetime(160, false));
        assertEquals(48, ProcBasis.brambleLifetime(160, true), "遲爆更少 70%");
        final long[] times = ProcBasis.rearmTimes(1000, 900, 1060);
        assertEquals(1020, times[1], "過 1 秒才會被碰");
        assertEquals(1012, times[0]);
        assertEquals(1160, times[2], "剩下的時間照原本的長度");
    }

    @Test
    void freshBramblesDealLess() {
        assertEquals(0.2, ProcBasis.brambleDamageScale(8), 1e-9);
        assertEquals(1.0, ProcBasis.brambleDamageScale(10), 1e-9);
    }

    @Test
    void withBaseKeepsTypesAndScalesTotal() {
        final DamagePacket packet = new DamagePacket().set(DamageType.FIRE, 3).set(DamageType.COLD, 1);
        final SkillProfile base = new SkillProfile("x", Set.of(Tags.SPELL), 1, packet, packet, Map.of(), List.of());
        final SkillProfile out = ProcBasis.withBase(base, 100, DamageType.FIRE);
        assertEquals(75, out.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(25, out.spellMax().get(DamageType.COLD), 1e-9);
    }

    @Test
    void corruptedBloodProfileIsWarcryDot() {
        final DamagePacket packet = new DamagePacket().set(DamageType.PHYSICAL, 600);
        final SkillProfile base = new SkillProfile("proc_corrupting_cry", Set.of(Tags.SPELL, Tags.AREA, Tags.PHYSICAL), 1, packet, packet, Map.of(), List.of());
        final SkillProfile dot = ProcBasis.dotProfile(base);
        assertTrue(dot.tags().contains(Tags.WARCRY));
        assertFalse(dot.tags().contains(Tags.SPELL));
        assertFalse(dot.tags().contains(Tags.AREA));
        assertEquals(0, dot.effectiveness(), 1e-9, "不吃附加傷害");
        assertEquals(10, ProcBasis.perSecond(600), 1e-9);
    }

    @Test
    void brambleProfileIsAnAttackNotASpell() {
        final SkillProfile parent = new SkillProfile("slam", Set.of(Tags.ATTACK, Tags.MELEE, Tags.SLAM, Tags.MACE), 1, null, null, Map.of(), List.of());
        final SkillProfile out = ProcBasis.brambleProfile(parent, Set.of(Tags.SPELL, Tags.AREA, Tags.PHYSICAL, Tags.DURATION),
                List.of(Modifier.flat(StatKeys.MAIM_CHANCE, 100, "荊棘")));
        assertTrue(out.isAttack());
        assertFalse(out.isSpell());
        assertFalse(out.tags().contains(Tags.MELEE));
        assertFalse(out.tags().contains(Tags.SLAM));
        assertTrue(out.tags().contains(Tags.AREA));
        assertEquals("proc_bramble", out.id());
        assertEquals(1, out.local().size());
    }

    @Test
    void yamlMatchesPoe2() {
        final SkillDefinition fan = config.skill(ProcBasis.FAN_FLAMES);
        assertEquals(Mechanic.NOVA, fan.mechanic());
        assertEquals(3.5, fan.params().radius().at(1), 1e-9);
        assertEquals(1.63, fan.effectiveness().at(20), 1e-9);
        assertTrue(fan.isAttack(), "播火爆照 PoB2 是攻擊");
        assertTrue(fan.weaponless(), "不用武器的攻擊：用自己的傷害表");
        assertFalse(fan.tags().contains(tw.exilecore.stats.Tags.SPELL));
        assertEquals(0.35, config.parentDamage(ProcBasis.BRAMBLE), 1e-9);
        assertEquals(Mechanic.NOVA, config.skill(ProcBasis.BRAMBLE).mechanic());
        assertTrue(config.skill(ProcBasis.CORRUPTING_CRY).damageByLevel());
        assertTrue(config.skill(ProcBasis.TWISTED_PACT).damageByLevel());
        assertTrue(config.skill(ProcBasis.CORRUPTING_CRY).modifiers().stream().noneMatch(m -> m.stat().equals(StatKeys.BLEED_CHANCE)),
                "腐血嘯不再給流血機率");
        assertEquals(1.5, config.skill(ProcBasis.QUILL_BURST).params().radius().at(1), 1e-9, "刺爆針照原作 1.5 公尺");
        assertTrue(ProcBasis.handles(ProcBasis.FLAME_PILLAR));
        assertFalse(ProcBasis.handles(ProcBasis.QUILL_BURST), "刺爆針另外由 SupportExtras 呼叫");
    }
}
