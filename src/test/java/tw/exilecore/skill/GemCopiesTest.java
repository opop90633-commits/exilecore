package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2：同一個技能寶石可以放好幾顆（面板上一顆＋主要寶石孔裡一顆、同一個主要寶石放兩顆、面板上兩顆），
 * 每一顆自己的等級、品質、輔助；常駐技能、宿主一個角色只能一顆（{@link GemCopies}）。
 */
class GemCopiesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillLoadout.Entry comet(final int level, final String... supports) {
        return new SkillLoadout.Entry("comet", level, 3, List.of(supports));
    }

    @Test
    void whichSkillsAreSingleCopy() {
        assertFalse(GemCopies.singleCopy(config, "comet"), "一般的法術可以放好幾顆");
        assertFalse(GemCopies.singleCopy(config, "arc"));
        assertTrue(GemCopies.singleCopy(config, "herald_of_ice"), "常駐（保留精魂）一個角色一顆");
        assertTrue(GemCopies.singleCopy(config, "meta_ailment"), "主要寶石一個角色一顆");
        assertTrue(GemCopies.singleCopy(config, "meta_crit"));
        assertTrue(GemCopies.singleCopy(config, "totem_casting"), "能放技能寶石的宿主一個角色一顆");
        assertFalse(GemCopies.singleCopy(config, "沒有這個技能"));
    }

    /** 使用者的例子：元素異常時施放（自動）裡放一顆冰隕，面板上再一顆自己按、掛不同的傷害輔助。 */
    @Test
    void panelCopyAndMetaCopy() {
        final SkillLoadout loadout = new SkillLoadout();
        final int meta = loadout.add(new SkillLoadout.Entry("meta_ailment", 10, 4, List.of("", "boundless_energy", "", "")));
        final SkillLoadout.Entry inner = comet(12);
        assertNull(GemCopies.socketProblem(config, loadout, inner, meta, 0), "主要寶石孔裡放冰隕");
        loadout.set(meta, loadout.skill(meta).withSpell(0, inner));
        final SkillLoadout.Entry manual = comet(15, "added_cold", "elemental_focus");
        assertNull(GemCopies.placeProblem(config, loadout, manual, -1), "主要寶石裡有冰隕，面板上照樣能再放一顆");
        final int panel = loadout.add(manual);
        assertTrue(panel >= 0);
        assertEquals(List.of("added_cold", "elemental_focus"), loadout.skill(panel).supportIds(), "自己按的那顆用自己的輔助");
        assertTrue(loadout.skill(meta).spellAt(0).supportIds().isEmpty(), "孔裡那顆自己沒有輔助（照主要寶石其他孔的）");
        assertEquals(List.of("boundless_energy"), loadout.skill(meta).supportIds());
        assertEquals(12, loadout.skill(meta).spellAt(0).level(), "各自的等級");
        assertEquals(15, loadout.skill(panel).level());
        // 打中時找「是哪一顆」：主要寶石觸發的找宿主孔裡那顆、自己按的找面板上那顆
        assertEquals(meta, loadout.locate("comet", true));
        assertEquals(panel, loadout.locate("comet", false));
        // 反過來的順序：面板上先有，再放進主要寶石也可以
        final SkillLoadout other = new SkillLoadout();
        other.add(comet(15, "added_cold"));
        final int metaAfter = other.add(new SkillLoadout.Entry("meta_ailment", 10, 4, List.of()));
        assertNull(GemCopies.socketProblem(config, other, comet(12), metaAfter, 0), "面板上有冰隕，主要寶石孔裡照樣能放");
        // 帶著孔裡的冰隕整顆放回面板（面板上已經有一顆）也可以
        final SkillLoadout.Entry metaWithComet = new SkillLoadout.Entry("meta_ailment", 10, 4, List.of()).withSpell(0, comet(12));
        final SkillLoadout third = new SkillLoadout();
        third.add(comet(15));
        assertNull(GemCopies.placeProblem(config, third, metaWithComet, -1));
    }

    /** PoE2 0.4 雙冰隕暴擊時施放：同一個主要寶石裡放兩顆冰隕，一起觸發、能量上限加起來。 */
    @Test
    void twoCopiesInOneMeta() {
        final SkillLoadout loadout = new SkillLoadout();
        final int meta = loadout.add(new SkillLoadout.Entry("meta_crit", 10, 4, List.of()).withSpell(0, comet(12)));
        assertNull(GemCopies.socketProblem(config, loadout, comet(14), meta, 1), "同一個主要寶石別孔已經有冰隕，照樣能放");
        loadout.set(meta, loadout.skill(meta).withSpell(1, comet(14)));
        final SkillLoadout.Entry host = loadout.skill(meta);
        assertEquals(List.of("comet", "comet"), host.spells().stream().map(SkillLoadout.Entry::skillId).toList());
        assertEquals(List.of(12, 14), host.spells().stream().map(SkillLoadout.Entry::level).toList(), "各自的等級");
        final SkillLoadout.Entry back = SkillLoadout.Entry.decode("meta_crit", 10, host.encodeSupports());
        assertEquals(2, back.spells().size(), "存檔來回兩顆都在");
        final SkillDefinition comet = config.skill("comet");
        assertEquals(2 * MetaGems.totalMaxEnergy(List.of(comet)), MetaGems.totalMaxEnergy(List.of(comet, comet)), 1e-9,
                "照 PoE2：能量上限是每個插槽法術的加起來");
        // 換掉同一孔的冰隕也可以
        assertNull(GemCopies.socketProblem(config, loadout, comet(20), meta, 1));
    }

    @Test
    void twoCopiesOnPanel() {
        final SkillLoadout loadout = new SkillLoadout();
        final int first = loadout.add(comet(10, "added_cold"));
        assertNull(GemCopies.placeProblem(config, loadout, comet(11, "elemental_focus"), -1));
        final int second = loadout.add(comet(11, "elemental_focus"));
        assertTrue(second >= 0 && second != first);
        assertEquals(2, loadout.count("comet"));
        assertEquals(List.of("added_cold"), loadout.skill(first).supportIds());
        assertEquals(List.of("elemental_focus"), loadout.skill(second).supportIds());
        assertEquals(first, loadout.locate("comet", false), "只知道代號時照第一顆");
        assertEquals(first, loadout.locate("comet", true), "沒有放在宿主裡：觸發的也找面板上的");
    }

    @Test
    void persistentAndHostsStaySingle() {
        final SkillLoadout loadout = new SkillLoadout();
        final int herald = loadout.add(new SkillLoadout.Entry("herald_of_ice", 5));
        final String problem = GemCopies.placeProblem(config, loadout, new SkillLoadout.Entry("herald_of_ice", 8), -1);
        assertNotNull(problem, "常駐技能不能放第二顆");
        assertTrue(problem.contains("常駐"), problem);
        assertNull(GemCopies.placeProblem(config, loadout, new SkillLoadout.Entry("herald_of_ice", 8), herald), "放在同一格是換掉");
        loadout.add(new SkillLoadout.Entry("meta_crit", 10, 4, List.of()));
        assertNotNull(GemCopies.placeProblem(config, loadout, new SkillLoadout.Entry("meta_crit", 12, 4, List.of()), -1), "主要寶石不能放第二顆");
        loadout.add(new SkillLoadout.Entry("totem_casting", 10, 4, List.of()));
        assertNotNull(GemCopies.placeProblem(config, loadout, new SkillLoadout.Entry("totem_casting", 10, 4, List.of()), -1));
    }

    /** 2026-10-28：面板上有一顆寶石、又有一顆武器賦予的：同步賦予的等級改的是賦予的那一顆。 */
    @Test
    void grantedCopySyncsItself() {
        final SkillLoadout loadout = new SkillLoadout();
        final int gem = loadout.add(new SkillLoadout.Entry("arc", 5));
        final int granted = loadout.add(new SkillLoadout.Entry("arc", 3).withGranted(true));
        assertTrue(loadout.syncGranted(Map.of("arc", 9)));
        assertEquals(5, loadout.skill(gem).level(), "寶石那顆不動");
        assertEquals(9, loadout.skill(granted).level());
        // 只有寶石那顆：照舊不另外放賦予的
        final SkillLoadout plain = new SkillLoadout();
        plain.add(new SkillLoadout.Entry("arc", 5));
        assertFalse(plain.syncGranted(Map.of("arc", 9)));
        assertEquals(1, plain.size());
    }
}
