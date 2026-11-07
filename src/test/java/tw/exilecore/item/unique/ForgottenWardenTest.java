package tw.exilecore.item.unique;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SoulVessel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：遺忘看守（PoE2 Forgotten Warden）與靈魂容器（Spirit Vessel）。 */
class ForgottenWardenTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static SkillConfig skills;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
    }

    @Test
    void deflectionPerFiftyMissingEnergyShieldRoundsDown() {
        assertEquals(0, WardenKeys.deflectionFromMissingEs(100, 1000, 1000), 1e-9, "護盾是滿的");
        assertEquals(0, WardenKeys.deflectionFromMissingEs(100, 1000, 951), 1e-9, "少 49：不到一格");
        assertEquals(100, WardenKeys.deflectionFromMissingEs(100, 1000, 950), 1e-9, "少 50：一格");
        assertEquals(70 * 3, WardenKeys.deflectionFromMissingEs(70, 1000, 840), 1e-9, "少 160：三格");
        assertEquals(85 * 20, WardenKeys.deflectionFromMissingEs(85, 1000, 0), 1e-9, "護盾打光：20 格");
        assertEquals(85 * 20, WardenKeys.deflectionFromMissingEs(85, 1000, -5), 1e-9, "負的護盾當 0");
        assertEquals(0, WardenKeys.deflectionFromMissingEs(100, 0, 0), 1e-9, "沒有護盾上限");
        assertEquals(0, WardenKeys.deflectionFromMissingEs(0, 1000, 0), 1e-9, "沒有詞綴");
    }

    @Test
    void onlyDeflectedHitsAreSharedWithTheCompanion() {
        assertEquals(15, WardenKeys.deflectedShare(true, 100, 15), 1e-9);
        assertEquals(0, WardenKeys.deflectedShare(false, 100, 15), 1e-9, "沒被偏斜");
        assertEquals(0, WardenKeys.deflectedShare(true, 0, 15), 1e-9);
        assertEquals(0, WardenKeys.deflectedShare(true, 100, 0), 1e-9);
        assertEquals(100, WardenKeys.deflectedShare(true, 100, 250), 1e-9, "最多整下");
    }

    @Test
    void eachDifferentSocketedSkillGivesTwentyPercentMore() {
        assertEquals(0, SoulVessel.damageMore(SoulVessel.distinct(List.of())), 1e-9);
        assertEquals(20, SoulVessel.damageMore(SoulVessel.distinct(List.of("mountain_shake_claw"))), 1e-9);
        assertEquals(40, SoulVessel.damageMore(SoulVessel.distinct(List.of("mountain_shake_claw", "mountain_shake_claw", "pounce"))), 1e-9,
                "同一個技能兩顆只算一次");
        assertEquals(60, SoulVessel.damageMore(3), 1e-9);
        assertEquals(10, SoulVessel.attackTicks(14, 1.4));
        assertEquals(4, SoulVessel.attackTicks(4, 5), "最少 4 tick");
        assertEquals(20, SoulVessel.attackTicks(20, 0), "沒寫攻速倍率照 1");
    }

    @Test
    void vesselTakesBearWerewolfAndWyvernSkillsOnly() {
        assertNull(SoulVessel.problem(skills.skill("mountain_shake_claw")), "熊");
        assertNull(SoulVessel.problem(skills.skill("pounce")), "狼人");
        assertEquals("熊形態", SoulVessel.form(skills.skill("mountain_shake_claw").tags()));
        assertEquals("狼人形態", SoulVessel.form(skills.skill("pounce").tags()));
        int wyverns = 0;
        for (final SkillDefinition definition : skills.all()) {
            if (definition.tags().contains("wyvern") && definition.tags().contains("shapeshift") && !definition.hidden() && !definition.grantedOnly()) {
                assertNull(SoulVessel.problem(definition), definition.id());
                assertEquals("飛龍形態", SoulVessel.form(definition.tags()));
                wyverns++;
            }
        }
        assertTrue(wyverns > 0, "有飛龍技能");
        assertNotNull(SoulVessel.problem(skills.skill("molten_dive_crash")), "武器賦予的（沒有寶石）不行");
        assertNotNull(SoulVessel.problem(skills.skill("wild_beast_taming")), "不是變身技能");
        assertNotNull(SoulVessel.problem(skills.skill(SoulVessel.ID)), "主要寶石不能放");
    }

    @Test
    void soulVesselIsAGrantedCompanionMeta() {
        final SkillDefinition vessel = skills.skill(SoulVessel.ID);
        assertNotNull(vessel);
        assertTrue(vessel.grantedOnly(), "遺忘看守賦予，沒有寶石");
        assertTrue(vessel.tags().containsAll(Set.of("companion", "minion", "persistent", "meta")), vessel.tags().toString());
        assertNotNull(vessel.summon());
        assertTrue(vessel.summon().ally() && vessel.summon().fixedMax() && vessel.summon().duration() == 0);
    }

    @Test
    void wardenIsADelveBossUniqueOnAHiddenBase() {
        final UniqueItem warden = repository.unique("forgotten_warden");
        assertNotNull(warden);
        assertEquals(Set.of("delve_boss"), warden.sources(), "只在星脈深坑的 Boss 掉（不能用僥倖石、賭博做出來）");
        assertFalse(warden.sources().contains("chance") || warden.sources().contains("gamble"));
        final ItemBase base = repository.base(warden.base());
        assertNotNull(base);
        assertTrue(repository.retiredBase(base.id()), "野性標記是原作 hidden 的基底：傳奇專用");
        assertEquals(268, base.evasion(), 1e-9);
        assertEquals(82, base.energyShield(), 1e-9);
        assertEquals(70, base.requirements().level());
        assertEquals(67, base.requirements().dex());
        assertEquals(67, base.requirements().intelligence());
        assertEquals(6, warden.affixIds().size());
        boolean granted = false;
        for (final String affixId : warden.affixIds()) {
            for (final tw.exilecore.item.AffixMod mod : repository.affix(affixId).mods()) {
                if (mod.stat().equals(WardenKeys.GRANT_SOUL_VESSEL)) {
                    granted = true;
                }
            }
        }
        assertTrue(granted, "賦予靈魂容器");
    }
}
