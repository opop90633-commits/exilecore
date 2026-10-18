package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.CurrencyGroups;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共鳴石（2026-10-18）：用在一顆專屬掉落的輔助寶石上，那顆就不佔「一個技能只能掛一顆專屬輔助」的名額。
 * 存檔：{@link SkillLoadout.Entry} 的輔助代號後面接 ~；寶石物品：{@link GemData} 的第四欄寫 resonant。
 */
class ResonanceTest {

    @Test
    void entryEncodesResonantSupportsWithTilde() {
        final SkillLoadout.Entry plain = new SkillLoadout.Entry("fireball", 7, 4, List.of("pierce", "", "rift_echo"));
        assertEquals("4:pierce,,rift_echo,", plain.encodeSupports(), "沒有共鳴的照舊");
        assertFalse(plain.resonant(2));

        final SkillLoadout.Entry marked = plain.withSupport(2, "rift_echo", true);
        assertEquals("rift_echo", marked.support(2), "support(i) 回傳乾淨的代號");
        assertTrue(marked.resonant(2));
        assertFalse(marked.resonant(0));
        assertEquals("4:pierce,,rift_echo~,", marked.encodeSupports());
        assertEquals(List.of("pierce", "rift_echo"), marked.supportIds(), "supportIds 也是乾淨的");
        assertTrue(marked.hasSupport("rift_echo"));

        // 讀回來：~ 剝掉、旗標留著；再寫出去一樣
        final SkillLoadout.Entry decoded = SkillLoadout.Entry.decode("fireball", 7, marked.encodeSupports());
        assertEquals(marked, decoded);
        assertTrue(decoded.resonant(2));
        assertEquals("rift_echo", decoded.support(2));
        assertEquals(marked.encodeSupports(), decoded.encodeSupports());

        // 舊資料（沒有 ~）全是 false；換等級、開孔不會掉旗標；拿掉輔助旗標也跟著清掉
        final SkillLoadout.Entry old = SkillLoadout.Entry.decode("fireball", 7, "3:pierce,,rift_echo");
        for (int i = 0; i < SkillLoadout.MAX_SOCKETS; i++) {
            assertFalse(old.resonant(i));
        }
        assertTrue(marked.withLevel(9).resonant(2));
        assertTrue(marked.withSockets(5).resonant(2));
        assertFalse(marked.withSupport(2, "").resonant(2));
        assertEquals("4:pierce,,,", marked.withSupport(2, null).encodeSupports());
        assertFalse(new SkillLoadout.Entry("fireball", 1, 3, List.of("", "~", "")).resonant(1), "空孔不會共鳴");
        assertTrue(marked.withResonant(2, false).encodeSupports().equals("4:pierce,,rift_echo,"));

        // 技能寶石（整個 Entry 存進物品）也走同一條路
        final GemData gem = GemData.skill(marked);
        final GemData back = GemData.decode(gem.encode()).orElseThrow();
        assertEquals(marked, back.entry());
        assertTrue(back.entry().resonant(2));
    }

    @Test
    void supportGemCarriesTheMark() {
        final GemData plain = GemData.support("rift_echo");
        final GemData marked = GemData.support("rift_echo", true);
        assertFalse(plain.resonant());
        assertTrue(marked.resonant());
        assertEquals("support;rift_echo;0;", plain.encode(), "沒共鳴的寫法跟以前一樣（舊物品讀得回來）");
        assertEquals("support;rift_echo;0;resonant", marked.encode());
        assertNotEquals(plain.encode(), marked.encode(), "PDC 不同：共鳴的跟沒共鳴的不會疊在一起");
        assertEquals(marked, GemData.decode(marked.encode()).orElseThrow());
        assertEquals(plain, GemData.decode(plain.encode()).orElseThrow());
        assertEquals(plain, GemData.decode("support;rift_echo;0").orElseThrow(), "舊的三欄格式");
        assertEquals(marked, plain.withResonant(true));
        assertFalse(GemData.uncutSupport(2).resonant());
    }

    @Test
    void resonantSupportsDoNotCountAsExclusive() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final List<SupportDefinition> exclusive = new ArrayList<>();
        for (final SupportDefinition support : config.supports()) {
            if (!support.cuttable()) {
                exclusive.add(support);
            }
        }
        assertTrue(exclusive.size() >= 3, "資料檔至少有三顆專屬掉落的輔助");
        final SupportDefinition first = exclusive.get(0);
        final SupportDefinition second = exclusive.get(1);
        final SupportDefinition third = exclusive.get(2);
        final SkillLoadout.Entry empty = new SkillLoadout.Entry("fireball", 5).withSockets(5);

        // 已掛一顆沒共鳴的專屬：再掛一顆沒共鳴的不行，掛一顆共鳴的可以
        final SkillLoadout.Entry one = empty.withSupport(0, first.id());
        assertNotNull(SupportDefinition.exclusiveConflict(one, -1, second, config::support));
        assertNotNull(SupportDefinition.exclusiveConflict(one, -1, second, false, config::support), "沒共鳴的第二顆不行");
        assertNull(SupportDefinition.exclusiveConflict(one, -1, second, true, config::support), "共鳴的第二顆可以");

        // 已掛的那顆共鳴了：再掛一顆沒共鳴的也可以（名額還空著）
        final SkillLoadout.Entry oneResonant = empty.withSupport(0, first.id(), true);
        assertNull(SupportDefinition.exclusiveConflict(oneResonant, -1, second, false, config::support));
        assertNull(SupportDefinition.exclusiveConflict(oneResonant, -1, second, config::support), "舊的四參數版也看孔裡的旗標");

        // 共鳴的＋沒共鳴的各一顆：第三顆沒共鳴的不行、共鳴的可以
        final SkillLoadout.Entry two = oneResonant.withSupport(1, second.id(), false);
        final String problem = SupportDefinition.exclusiveConflict(two, -1, third, false, config::support);
        assertNotNull(problem);
        assertTrue(problem.contains(second.name()), problem);
        assertFalse(problem.contains(first.name()), "共鳴的那顆不會被怪到");
        assertNull(SupportDefinition.exclusiveConflict(two, -1, third, true, config::support));
        assertNull(SupportDefinition.exclusiveConflict(two, 1, third, false, config::support), "換掉沒共鳴的那顆可以");
    }

    @Test
    void skillBuildCountsOnlyUnresonantExclusives() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SkillDefinition fireball = config.skill("fireball");
        final SupportDefinition heart = config.support("permafrost_heart");
        final SupportDefinition fury = config.support("skyburn_fury");
        assertNotNull(heart);
        assertNotNull(fury);
        assertFalse(heart.cuttable());
        assertFalse(fury.cuttable());
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet();
        final SkillBuild both = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart, fury), Set.of(), sheet);
        final SkillBuild onlyHeart = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart), Set.of(), sheet);
        assertEquals(onlyHeart.supports().size(), both.supports().size(), "沒共鳴：第二顆專屬的不算");
        final SkillBuild heartResonant = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart, fury), Set.of(), sheet, Set.of(heart.id()));
        assertEquals(onlyHeart.supports().size() + 1, heartResonant.supports().size(), "第一顆共鳴了：兩顆都生效");
        assertTrue(heartResonant.supports().contains(heart));
        assertTrue(heartResonant.supports().contains(fury));
        final SkillBuild furyResonant = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart, fury), Set.of(), sheet, Set.of(fury.id()));
        assertTrue(furyResonant.supports().contains(fury), "第二顆共鳴了：也都生效");
        assertTrue(furyResonant.supports().contains(heart));
        // 兩顆都沒共鳴但旗標給了別的代號：照舊只有一顆
        final SkillBuild other = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart, fury), Set.of(), sheet, Set.of("pierce"));
        assertEquals(onlyHeart.supports().size(), other.supports().size());
        // SkillService.resonantOf：從 Entry 的旗標取出代號
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 5, 3, List.of(heart.id() + "~", fury.id(), ""));
        assertEquals(Set.of(heart.id()), SkillService.resonantOf(entry));
    }

    @Test
    void resonanceOrbIsShippedAsAKey() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(Path.of("src/main/resources/data"));
        final CurrencyType orb = currencies.get("resonance_orb");
        assertNotNull(orb, "currencies.yml 有共鳴石");
        assertEquals(CurrencyOperation.RESONANCE, orb.operation());
        assertEquals(0, orb.dropWeight(), "掉落表不掉（另外擲，跟映星鏡一樣）");
        assertEquals(10, orb.stack());
        assertEquals(CurrencyGroups.Group.KEYS, CurrencyGroups.of(orb));
        assertEquals(orb, currencies.firstWith(CurrencyOperation.RESONANCE).orElseThrow());
        assertFalse(orb.isSkillCurrency(), "不是開孔的通貨（技能面板另外認 RESONANCE）");
        assertFalse(CurrencyOperation.RESONANCE.canApply(null, null), "不能直接用在裝備上");
    }

    @Test
    void resonanceRatesAreOneAndAHalfTimesRarerThanChase() throws IOException {
        final tw.exilecore.map.MapConfig maps = new tw.exilecore.map.MapConfig();
        maps.load(Path.of("src/main/resources/data"));
        assertEquals(maps.drops().chaseChance() / 5, maps.resonanceChance(), maps.drops().chaseChance() * 0.01, "Boss：追逐級的 1/5（2026-10-18）");
        assertEquals(0.0000002, maps.resonanceMobChance(), 1e-12, "一般怪物：約 500 萬隻一顆");
        assertTrue(maps.resonanceMobChance() < maps.chaseMobChance(), "比一般怪物掉追逐級（100 萬隻一件）更難");
        assertEquals(75, tw.exilecore.map.MapConfig.RESONANCE_MIN_LEVEL);
    }
}
