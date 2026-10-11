package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一個角色的昇華：試煉解鎖、昇華點、選昇華職業、配點與退點的規則，設定檔改過時怎麼核對。 */
class AscensionTest {

    static AscendancyConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    static Ascension marauder(final Map<String, String> progress, final int level) {
        return new Ascension(config, progress, "marauder", level);
    }

    static String bossName(final String zone) {
        return "「" + zone + "」的 Boss";
    }

    @Test
    void trialsUnlockOneAfterAnother() {
        final Map<String, String> progress = new HashMap<>();
        final AscendancyConfig.Trial ember = config.trial("ember");
        final AscendancyConfig.Trial venom = config.trial("venom");
        final AscendancyConfig.Trial star = config.trial("star");
        assertEquals("先用這個角色打倒「ruins」的 Boss", marauder(progress, 30).lockReason(ember, AscensionTest::bossName));
        progress.put("boss.ruins", "1");
        assertNull(marauder(progress, 30).lockReason(ember, AscensionTest::bossName));
        progress.put("boss.swamp", "1");
        assertEquals("先完成「第一試煉・餘燼」", marauder(progress, 50).lockReason(venom, AscensionTest::bossName));
        progress.put(Ascension.trialKey("ember"), "100");
        assertNull(marauder(progress, 50).lockReason(venom, AscensionTest::bossName));
        progress.put(Ascension.trialKey("venom"), "100");
        progress.put(Ascension.trialKey("frost"), "100");
        for (int i = 0; i < 5; i++) {
            progress.put("atlas.map" + i, "1");
        }
        assertEquals("先完成 6 張不同的地圖（現在 5 張）", marauder(progress, 60).lockReason(star, AscensionTest::bossName));
        progress.put("atlas.map5", "1");
        assertNull(marauder(progress, 60).lockReason(star, AscensionTest::bossName));
        assertEquals(6, marauder(progress, 60).points(), "完成三個");
        assertEquals(3, marauder(progress, 60).completedCount());
    }

    @Test
    void chooseOnceAfterTheFirstTrial() {
        final Map<String, String> progress = new HashMap<>();
        Ascension ascension = marauder(progress, 40);
        assertFalse(ascension.canChoose(), "還沒完成試煉");
        assertEquals("先完成一個試煉", ascension.chooseProblem(config.ascendancy("ironwarden")));
        assertEquals("還沒有選昇華職業", ascension.allocateProblem(config.ascendancy("ironwarden").node("t_armour")));
        progress.put(Ascension.trialKey("ember"), "100");
        ascension = marauder(progress, 40);
        assertTrue(ascension.canChoose());
        assertEquals(List.of(config.ascendancy("ironwarden"), config.ascendancy("berserker"), config.ascendancy("forgesmith")),
                ascension.options(), "2026-10：重甲鬥士有三個昇華職業");
        assertEquals("這不是你的職業能選的昇華職業", ascension.chooseProblem(config.ascendancy("stararrow")));
        assertNull(ascension.chooseProblem(config.ascendancy("berserker")));
        progress.put(Ascension.CHOSEN, "ironwarden");
        ascension = marauder(progress, 40);
        assertEquals(config.ascendancy("ironwarden"), ascension.chosen());
        assertFalse(ascension.canChoose());
        assertEquals("已經昇華為鐵衛了（選了不能換）", ascension.chooseProblem(config.ascendancy("berserker")));
        // 別的職業的角色讀到同一筆（不會發生，但資料亂掉也不能套用）
        assertNull(new Ascension(config, progress, "witch", 40).chosen());
        assertTrue(new Ascension(config, progress, "witch", 40).allocated().isEmpty());
    }

    @Test
    void allocateAlongTheBranchesWithinThePoints() {
        // 2026-10：每個昇華天賦 1 點，一個試煉 2 點
        final Map<String, String> progress = new HashMap<>();
        progress.put(Ascension.trialKey("ember"), "100");
        progress.put(Ascension.CHOSEN, "ironwarden");
        final AscendancyConfig.Ascendancy warden = config.ascendancy("ironwarden");
        final AscendancyConfig.Node armour = warden.node("t_armour");
        final AscendancyConfig.Node stoneSkin = warden.node("t_stone_skin");
        final AscendancyConfig.Node armour2 = warden.node("t_armour2");
        final AscendancyConfig.Node str = warden.node("t_str");
        final AscendancyConfig.Node lineage = warden.node("t_lineage");
        Ascension ascension = marauder(progress, 40);
        assertEquals(2, ascension.available());
        assertTrue(ascension.canAllocateAny());
        assertEquals("先配「鐵壁」", ascension.allocateProblem(stoneSkin));
        assertNull(ascension.allocateProblem(armour));
        progress.put(Ascension.NODES, ascension.withNode(armour));
        assertEquals("t_armour", progress.get(Ascension.NODES));
        ascension = marauder(progress, 40);
        assertEquals(1, ascension.available());
        assertEquals("已經配過了", ascension.allocateProblem(armour));
        assertNull(ascension.allocateProblem(stoneSkin));
        progress.put(Ascension.NODES, ascension.withNode(stoneSkin));
        ascension = marauder(progress, 40);
        assertEquals(0, ascension.available());
        assertFalse(ascension.canAllocateAny());
        assertEquals("昇華點不夠：要 1 點，你有 0 點（第一次完成試煉會給）", ascension.allocateProblem(armour2));
        assertEquals("這不是鐵衛的昇華天賦", ascension.allocateProblem(config.ascendancy("berserker").node("w_break")));
        // 第二個試煉：再 2 點
        progress.put(Ascension.trialKey("venom"), "200");
        ascension = marauder(progress, 50);
        assertNull(ascension.allocateProblem(armour2));
        assertEquals("先配「巨力」", ascension.allocateProblem(lineage));
        progress.put(Ascension.NODES, ascension.withNode(armour2));
        ascension = marauder(progress, 50);
        assertEquals(List.of(armour, stoneSkin, armour2), ascension.allocated());
        assertEquals(3, ascension.spent());
        // 修飾器：三個天賦的屬性
        final List<Modifier> modifiers = ascension.modifiers();
        assertEquals(3, modifiers.size());
        assertEquals(new Modifier(StatKeys.ARMOUR, ModType.INCREASED, 0.2, java.util.Set.of(), "昇華「鐵壁」"), modifiers.getFirst());
        // 退點：要從外面退
        assertEquals("先退後面的「岩膚」", ascension.refundProblem(armour));
        assertNull(ascension.refundProblem(armour2));
        assertEquals("這個昇華天賦沒有配", ascension.refundProblem(str));
        assertEquals("t_armour,t_stone_skin", ascension.withoutNode(armour2));
        progress.put(Ascension.NODES, "t_armour");
        assertNull(marauder(progress, 50).withoutNode(armour), "最後一個退掉：刪掉這一筆");
    }

    @Test
    void opensAreCountedPerWeek() {
        final Map<String, String> progress = new HashMap<>();
        assertEquals(0, Ascension.opensThisWeek(progress, "2026-W40"));
        progress.put(Ascension.WEEK, Ascension.opensValue("2026-W40", 3));
        assertEquals("2026-W40|3", progress.get(Ascension.WEEK));
        assertEquals(3, Ascension.opensThisWeek(progress, "2026-W40"));
        assertEquals(0, Ascension.opensThisWeek(progress, "2026-W41"), "新的一週重新算");
        progress.put(Ascension.WEEK, "壞掉");
        assertEquals(0, Ascension.opensThisWeek(progress, "2026-W40"));
        progress.put(Ascension.WEEK, "2026-W40|-5");
        assertEquals(0, Ascension.opensThisWeek(progress, "2026-W40"));
        // 次數那一筆不會被當成完成了一個叫 week 的試煉（鍵不一樣）
        assertFalse(Ascension.WEEK.startsWith(Ascension.TRIAL_PREFIX));
    }

    @Test
    void storedNodesAreCheckedEveryTime() {
        final Map<String, String> progress = new HashMap<>();
        progress.put(Ascension.trialKey("ember"), "100");
        progress.put(Ascension.CHOSEN, "ironwarden");
        final AscendancyConfig.Ascendancy warden = config.ascendancy("ironwarden");
        // 點數不夠（試煉的點數改少了）：照配的順序，放不下的略過
        progress.put(Ascension.NODES, "t_armour,t_stone_skin,t_armour2");
        assertEquals(List.of(warden.node("t_armour"), warden.node("t_stone_skin")), marauder(progress, 40).allocated());
        // 不認得的、重複的、前一個還沒配的、別的昇華職業的略過
        progress.put(Ascension.trialKey("venom"), "200");
        progress.put(Ascension.NODES, "removed,t_stone_skin,t_armour,t_armour,w_break");
        final Ascension ascension = marauder(progress, 50);
        assertEquals(List.of(warden.node("t_armour")), ascension.allocated());
        assertEquals("t_armour", ascension.withNode(warden.node("t_str")).split(",")[0], "寫回去的是核對過的");
        assertEquals("t_armour,t_str", ascension.withNode(warden.node("t_str")));
        // 選的昇華職業已經從設定檔拿掉了：當作沒選（可以重選）
        progress.put(Ascension.CHOSEN, "removed");
        assertNull(marauder(progress, 50).chosen());
        assertTrue(marauder(progress, 50).canChoose());
        assertEquals(List.of(), Ascension.parse(" , "));
    }
}
