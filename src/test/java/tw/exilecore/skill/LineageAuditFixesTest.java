package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 稽核修正：血契保留生命付不起要拒絕、星索鉤不能交易、雙兆／三兆照真的生效的輔助數、舊存檔多掛的血脈輔助拆下來、
 * 增加偏斜也乘閃避轉偏斜、共鳴石不亮血脈輔助。
 */
class LineageAuditFixesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(LineageSupportTest.DATA);
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    @Test
    void communionReservesLifeAndRefusesWhenItCannotPay() {
        final SkillBuild aegis = SkillBuild.of(config, config.skill("winter_shell"), 10, 90, false, List.of(support("blood_pact")),
                Set.of(), new StatSheet());
        final double percent = LineageRules.communionLife(aegis);
        assertEquals(aegis.definition().aura().reserve() * 0.66, percent, 1e-9, "每 1 點基礎精魂 0.66% 最大生命");
        assertEquals(0, AuraService.fit(aegis, 0).reserve(), 1e-9, "不花精魂");
        assertTrue(AuraService.lifeFits(aegis, 1000, false, 0));
        // 一直疊血契：加起來到 100% 之前就開不了（這次之前夾在 95%，第 6 顆起免費）
        double reserved = 0;
        int opened = 0;
        while (AuraService.lifeFits(aegis, 1000, false, reserved) && opened < 50) {
            reserved += percent;
            opened++;
        }
        assertTrue(reserved < 100, "保留的生命不超過 100%");
        assertEquals((int) Math.ceil(100 / percent) - 1, opened);
        assertFalse(AuraService.lifeFits(aegis, 1000, true, 0), "無垢之軀（最大生命鎖成 1）不能保留生命");
        assertFalse(AuraService.lifeFits(aegis, 1, false, 0), "最大生命只有 1 也不行");
        final SkillBuild plain = SkillBuild.of(config, config.skill("winter_shell"), 10, 90, false, List.of(), Set.of(), new StatSheet());
        assertTrue(AuraService.lifeFits(plain, 1, true, 99), "沒掛血契的光環不看生命");
        final StatSheet locked = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.FLAG_CHAOS_IMMUNE, 1, "無垢之軀")));
        assertTrue(AuraService.lifeLocked(locked));
        final StatSheet life = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.LIFE_MAX, 1000, "測試"),
                Modifier.more(StatKeys.LIFE_MAX, -0.2, "血契保留")));
        assertEquals(1000, AuraService.baseLife(life, 20), 1e-6, "換回保留之前的最大生命");
    }

    @Test
    void hookCannotBeTraded() throws IOException {
        final String trade = Files.readString(Path.of("src/main/java/tw/exilecore/trade/TradeService.java"));
        final int offer = trade.indexOf("void offerItem(");
        final int next = trade.indexOf("void offerPouch(");
        assertTrue(offer > 0 && next > offer);
        assertTrue(trade.substring(offer, next).contains("LifeService.isHook(stack)"), "交易放物品時要擋星索鉤（綁定帳號，不然可以複製）");
    }

    @Test
    void omenCountsOnlyEffectiveRealSupports() {
        final SkillDefinition fireball = config.skill("blazing_star_shell");
        final StatSheet sheet = new StatSheet();
        final int plain = SkillBuild.of(config, fireball, 10, 90, false, List.of(), Set.of(), sheet).level();
        final SupportDefinition omen = support("twin_portent");
        final SupportDefinition other = support("quick_chant");
        // 合成的輔助（引星的 meta.、宿主的 host.）不算其他輔助
        final SupportDefinition carrier = new SupportDefinition("meta.test", "引星", "", "PAPER", 1, Set.of(), Set.of(), 1.0, List.of(), List.of());
        assertTrue(carrier.supports(fireball));
        assertEquals(plain + 2, SkillBuild.of(config, fireball, 10, 90, false, List.of(carrier, omen, other), Set.of(), sheet).level(),
                "雙兆：合成的不算，剛好一顆其他輔助");
        assertEquals(plain, SkillBuild.of(config, fireball, 10, 90, false, List.of(carrier, omen), Set.of(), sheet).level(),
                "雙兆：只有合成的不算一顆");
        // 被名額擋掉的專屬輔助不算
        final List<SupportDefinition> exclusives = config.supports().stream()
                .filter(s -> !s.cuttable() && !s.lineage() && s.supports(fireball) && s.modifiers().stream().noneMatch(m -> m.stat().equals(StatKeys.SKILL_LEVEL)))
                .limit(2).toList();
        assertEquals(2, exclusives.size(), "要兩顆能掛火球的專屬輔助");
        assertEquals(plain + 2, SkillBuild.of(config, fireball, 10, 90, false, List.of(omen, exclusives.get(0), exclusives.get(1)), Set.of(), sheet)
                .level(), "雙兆：第二顆專屬輔助沒生效，只算一顆");
        assertEquals(2, LineageRules.conditionalLevels(List.of(omen, other, other, other), fireball), "同一顆重複也只算一次：其他 1 顆 → +2");
    }

    @Test
    void extraLineageSupportsComeOffOnLogin() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("blazing_star_shell", 5).withSockets(5)
                .withSupport(0, "quick_chant").withSupport(1, "counter_current").withSupport(2, "pierce")
                .withSupport(3, "ember_corrode").withSupport(4, "twin_portent");
        assertEquals(List.of(3, 4), LineageRules.extraLineageSockets(entry, id -> config.support(id) != null && config.support(id).lineage()));
        assertEquals(List.of(), LineageRules.extraLineageSockets(entry.withSupport(3, null).withSupport(4, null),
                id -> config.support(id) != null && config.support(id).lineage()), "只有一顆的不動");
    }

    @Test
    void increasedDeflectionAlsoScalesEvasionConversion() {
        final StatSheet sheet = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.EVASION, 1000, "測試"),
                Modifier.flat(StatKeys.DEFLECTION_FROM_EVASION, 10, "閃避轉偏斜"),
                Modifier.flat(StatKeys.DEFLECTION, 50, "固定偏斜"),
                Modifier.increased(StatKeys.DEFLECTION, 0.5, "增加偏斜"),
                Modifier.more(StatKeys.DEFLECTION, 0.2, "更多偏斜")));
        // (50 + 1000 × 10%) × 1.5 × 1.2 = 270（這次之前 50 × 1.5 × 1.2 + 100 = 190）
        assertEquals(270, DamageCalculator.deflectionRating(sheet), 1e-9);
        assertEquals(0, DamageCalculator.deflectionRating(new StatSheet()), 1e-9);
    }

    @Test
    void resonanceOrbDoesNotHighlightLineageSupports() {
        assertNotNull(support("counter_current").resonanceRefusal(false), "血脈輔助不能共鳴，也不亮");
        assertNotNull(support("quick_chant").resonanceRefusal(false), "一般輔助不行");
        final SupportDefinition exclusive = config.supports().stream().filter(s -> !s.cuttable() && !s.lineage()).findFirst().orElseThrow();
        assertNull(exclusive.resonanceRefusal(false), "專屬掉落的輔助可以");
        assertNotNull(exclusive.resonanceRefusal(true), "已經共鳴的不行");
    }
}
