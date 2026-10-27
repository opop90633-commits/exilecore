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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals("還沒有選昇華職業", ascension.allocateProblem(config.ascendancy("ironwarden").node("armour")));
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
        // 2026-10：每個昇華天賦 1 點，一個試煉 2 點。2026-10-26 換成 PoE2 原作的天賦（泰坦）
        final Map<String, String> progress = new HashMap<>();
        progress.put(Ascension.trialKey("ember"), "100");
        progress.put(Ascension.CHOSEN, "ironwarden");
        final AscendancyConfig.Ascendancy warden = config.ascendancy("ironwarden");
        final AscendancyConfig.Node armour = warden.node("armour");
        final AscendancyConfig.Node stoneSkin = warden.node("stone_skin");
        final AscendancyConfig.Node strength = warden.node("strength");
        final AscendancyConfig.Node capacity = warden.node("colossal_capacity");
        final AscendancyConfig.Node lineage = warden.node("mysterious_lineage");
        Ascension ascension = marauder(progress, 40);
        assertEquals(2, ascension.available());
        assertTrue(ascension.canAllocateAny());
        assertEquals("先配「護甲」", ascension.allocateProblem(stoneSkin));
        assertNull(ascension.allocateProblem(armour));
        progress.put(Ascension.NODES, ascension.withNode(armour));
        assertEquals("armour", progress.get(Ascension.NODES));
        ascension = marauder(progress, 40);
        assertEquals(1, ascension.available());
        assertEquals("已經配過了", ascension.allocateProblem(armour));
        assertNull(ascension.allocateProblem(stoneSkin));
        progress.put(Ascension.NODES, ascension.withNode(stoneSkin));
        ascension = marauder(progress, 40);
        assertEquals(0, ascension.available());
        assertFalse(ascension.canAllocateAny());
        assertEquals("昇華點不夠：要 1 點，你有 0 點（第一次完成試煉會給）", ascension.allocateProblem(strength));
        assertEquals("這不是鐵衛的昇華天賦", ascension.allocateProblem(config.ascendancy("berserker").node("armour_break")));
        // 第二個試煉：再 2 點
        progress.put(Ascension.trialKey("venom"), "200");
        ascension = marauder(progress, 50);
        assertNull(ascension.allocateProblem(strength));
        assertEquals("先配「生命回復」", ascension.allocateProblem(lineage));
        progress.put(Ascension.NODES, ascension.withNode(strength));
        ascension = marauder(progress, 50);
        assertEquals(List.of(armour, stoneSkin, strength), ascension.allocated());
        assertEquals(3, ascension.spent());
        // 修飾器：三個天賦的屬性
        final List<Modifier> modifiers = ascension.modifiers();
        assertEquals(3, modifiers.size());
        assertEquals(new Modifier(StatKeys.ARMOUR, ModType.INCREASED, 0.2, java.util.Set.of(), "昇華「護甲」"), modifiers.getFirst());
        // 退點：退掉之後剩下的要都還接得到起點
        assertEquals("先退後面的「頑石鋼膚」", ascension.refundProblem(armour));
        assertNull(ascension.refundProblem(stoneSkin));
        assertNull(ascension.refundProblem(strength));
        assertEquals("這個昇華天賦沒有配", ascension.refundProblem(capacity));
        assertEquals("armour,strength", ascension.withoutNode(stoneSkin));
        progress.put(Ascension.NODES, "armour");
        assertNull(marauder(progress, 50).withoutNode(armour), "最後一個退掉：刪掉這一筆");
    }

    /** 2026-10-26 照 PoE2：跟已配的天賦相連就能配（連線不分方向）——祈願者的環可以從另一邊繞過去；退點時剩下的要都還接得到起點。 */
    @Test
    void connectedInEitherDirection() {
        final Map<String, String> progress = new HashMap<>();
        progress.put(Ascension.trialKey("ember"), "1");
        progress.put(Ascension.trialKey("venom"), "1");
        progress.put(Ascension.trialKey("frost"), "1");
        progress.put(Ascension.CHOSEN, "invoker");
        final AscendancyConfig.Ascendancy invoker = config.ascendancy("invoker");
        for (final String id : List.of("chill_effect", "blizzard", "elemental_damage_2", "shall_rage")) {
            final Ascension ascension = new Ascension(config, progress, "monk", 70);
            assertNull(ascension.allocateProblem(invoker.node(id)), id);
            progress.put(Ascension.NODES, ascension.withNode(invoker.node(id)));
        }
        Ascension ascension = new Ascension(config, progress, "monk", 70);
        assertEquals("先配「感電效果」", ascension.allocateProblem(invoker.node("thunder")));
        assertNull(ascension.allocateProblem(invoker.node("elemental_damage")), "從怒號那一邊接過去");
        progress.put(Ascension.NODES, ascension.withNode(invoker.node("elemental_damage")));
        ascension = new Ascension(config, progress, "monk", 70);
        assertNull(ascension.allocateProblem(invoker.node("thunder")), "再往起點的方向接回去");
        assertEquals("先退後面的「元素傷害」", ascension.refundProblem(invoker.node("shall_rage")));
        assertNull(ascension.refundProblem(invoker.node("elemental_damage")));
        // 儀祭者：展露之指接在三個減少的天賦上，任一個配了就能配；另一個也配了之後，先配的那個可以退
        final Map<String, String> ritual = new HashMap<>();
        ritual.put(Ascension.trialKey("ember"), "1");
        ritual.put(Ascension.trialKey("venom"), "1");
        ritual.put(Ascension.CHOSEN, "ritualist");
        ritual.put(Ascension.NODES, "reduced_mana,unfurled_finger");
        final AscendancyConfig.Ascendancy ritualist = config.ascendancy("ritualist");
        Ascension huntress = new Ascension(config, ritual, "huntress", 50);
        assertEquals(2, huntress.allocated().size());
        assertEquals("先退後面的「展露之指」", huntress.refundProblem(ritualist.node("reduced_mana")));
        ritual.put(Ascension.NODES, "reduced_mana,unfurled_finger,reduced_spirit");
        huntress = new Ascension(config, ritual, "huntress", 50);
        assertNull(huntress.refundProblem(ritualist.node("reduced_mana")), "展露之指還接在減少精魂上");
        // 2026-10-27：退掉之後記錄變成「展露之指,減少精魂」（後面的先出現），兩個都要還在
        ritual.put(Ascension.NODES, huntress.withoutNode(ritualist.node("reduced_mana")));
        huntress = new Ascension(config, ritual, "huntress", 50);
        assertEquals(List.of(ritualist.node("unfurled_finger"), ritualist.node("reduced_spirit")), huntress.allocated());
    }

    /** 2026-10-26 照 PoE2：「選一個」的天賦花 1 點，配了之後從選項挑一個（不花點數、同一組只能一個）。 */
    @Test
    void pickOneOptionOfAChoice() {
        final Map<String, String> progress = new HashMap<>();
        progress.put(Ascension.trialKey("ember"), "1");
        progress.put(Ascension.CHOSEN, "stararrow");
        final AscendancyConfig.Ascendancy stararrow = config.ascendancy("stararrow");
        final AscendancyConfig.Node choice = stararrow.node("projectile_proximity");
        final AscendancyConfig.Node pointBlank = stararrow.node("point_blank");
        final AscendancyConfig.Node farShot = stararrow.node("far_shot");
        Ascension ranger = new Ascension(config, progress, "ranger", 40);
        assertEquals("先配「投射物近接專精」", ranger.allocateProblem(pointBlank));
        progress.put(Ascension.NODES, "projectile_speed,projectile_proximity");
        ranger = new Ascension(config, progress, "ranger", 40);
        assertEquals(0, ranger.available());
        assertTrue(ranger.canAllocateAny(), "點數用完了還能選選項");
        assertNull(ranger.allocateProblem(pointBlank), "選項不花點數");
        progress.put(Ascension.NODES, ranger.withNode(pointBlank));
        ranger = new Ascension(config, progress, "ranger", 40);
        assertEquals(3, ranger.allocated().size());
        assertEquals(2, ranger.spent());
        assertEquals("「投射物近接專精」只能選一個：已經選了「零點射擊」（先退掉才能換）", ranger.allocateProblem(farShot));
        assertEquals("先退後面的「零點射擊」", ranger.refundProblem(choice));
        assertNull(ranger.refundProblem(pointBlank));
        assertEquals(List.of(new Modifier(AscendancyMechanics.POINT_BLANK, ModType.FLAT, 20, java.util.Set.of(), "昇華「零點射擊」")),
                ranger.modifiers().subList(1, 2));
        // 存檔裡兩個選項都在（舊資料、手動改的）：只算第一個
        progress.put(Ascension.NODES, "projectile_speed,projectile_proximity,far_shot,point_blank");
        assertEquals(List.of("projectile_speed", "projectile_proximity", "far_shot"),
                new Ascension(config, progress, "ranger", 40).allocated().stream().map(AscendancyConfig.Node::id).toList());
    }

    /** 2026-10-26 昇華天賦改版（比照天賦樹）：角色記的版本跟設定檔不一樣就要退點；沒記過的算第 1 版。 */
    @Test
    void versionsDecideTheRefund() {
        assertTrue(Ascension.outdated(2, null), "改版以前配的（沒記過）");
        assertTrue(Ascension.outdated(2, "1"));
        assertFalse(Ascension.outdated(2, "2"));
        assertFalse(Ascension.outdated(1, null), "第 1 版不用退");
        assertFalse(Ascension.outdated(1, " "));
        assertEquals("asc.version", Ascension.VERSION);
        assertFalse(Ascension.VERSION.startsWith(Ascension.TRIAL_PREFIX));
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
        progress.put(Ascension.NODES, "armour,stone_skin,strength");
        assertEquals(List.of(warden.node("armour"), warden.node("stone_skin")), marauder(progress, 40).allocated());
        // 不認得的（2026-10-26 以前的代號）、重複的、接不上的、別的昇華職業的略過
        progress.put(Ascension.trialKey("venom"), "200");
        progress.put(Ascension.NODES, "t_armour,stone_skin,armour,armour,armour_break");
        // 2026-10-27：頑石鋼膚記在護甲前面也算（一直重掃到沒有新的接得上，跟退點的判斷一致）；這次之前只掃一遍，頑石鋼膚會被略過
        final Ascension ascension = marauder(progress, 50);
        assertEquals(List.of(warden.node("stone_skin"), warden.node("armour")), ascension.allocated());
        assertEquals("stone_skin", ascension.withNode(warden.node("strength")).split(",")[0], "寫回去的是核對過的");
        assertEquals("stone_skin,armour,strength", ascension.withNode(warden.node("strength")));
        progress.put(Ascension.NODES, "t_armour,stone_skin,armour_break");
        assertEquals(List.of(), marauder(progress, 50).allocated(), "真的接不上的還是略過");
        // 選的昇華職業已經從設定檔拿掉了：當作沒選（可以重選）
        progress.put(Ascension.CHOSEN, "removed");
        assertNull(marauder(progress, 50).chosen());
        assertTrue(marauder(progress, 50).canChoose());
        assertEquals(List.of(), Ascension.parse(" , "));
    }

    /** 2026-10-27 照站長：血術士的占血術自帶（選了就配好、不花點數、不能退）。 */
    @Test
    void sanguimancyIsGrantedForFree() {
        final Map<String, String> progress = new java.util.HashMap<>();
        progress.put(Ascension.CHOSEN, "bloodmage");
        final Ascension ascension = new Ascension(config, progress, "witch", 40);
        final AscendancyConfig.Node node = ascension.chosen().node("sanguimancy");
        assertTrue(node.granted());
        assertTrue(ascension.isAllocated(node), "沒有點數也已經配好");
        assertEquals(0, ascension.spent());
        assertNotNull(ascension.refundProblem(node), "不能退");
    }
}
