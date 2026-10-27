package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.KeywordText;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照站長「有些敘述兩遍的改成一遍；有些沒敘述的補上敘述」「本伺服器那四個字不用顯示」：
 * 昇華天賦、天賦樹玩家看得到的說明不重複、不含開發註記，只寫了關鍵字的有補說明，賦予的技能有說明。
 */
class AscendancyLoreTest {

    private static final Path DATA = Path.of("src/main/resources/data");
    /** 給開發者看的註記，不該出現在玩家看的說明。 */
    private static final Pattern DEV = Pattern.compile("本伺服器|這次之前|照 ?PoE|平衡等效");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    static AscendancyConfig config;
    static SkillConfig skills;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
    }

    private static List<String> plain(final List<String> lore) {
        final List<String> out = new ArrayList<>();
        for (final String line : lore) {
            out.add(TAG.matcher(line).replaceAll("").replace("\\<", "<").strip());
        }
        return out;
    }

    private static List<String> lore(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node) {
        return plain(AscendancyLayout.describe(ascendancy, node, true, skills::skill));
    }

    @Test
    void ascendancyLoreHasNoDevNotesAndNoRepeatedLines() {
        final List<String> problems = new ArrayList<>();
        int nodes = 0;
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                nodes++;
                final Set<String> seen = new HashSet<>();
                for (final String line : lore(ascendancy, node)) {
                    if (DEV.matcher(line).find()) {
                        problems.add(ascendancy.id() + "." + node.id() + " 有開發註記：" + line);
                    }
                    if (!line.isBlank() && !seen.add(line)) {
                        problems.add(ascendancy.id() + "." + node.id() + " 重複：" + line);
                    }
                }
            }
        }
        assertTrue(nodes > 100, "昇華天賦太少：" + nodes);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void keywordOnlyLinesGetExplainedOnce() {
        final AscendancyConfig.Ascendancy witchhunter = config.ascendancy("witchhunter");
        final List<String> judge = lore(witchhunter, witchhunter.node("judge_jury"));
        assertEquals(1, judge.stream().filter(line -> line.contains("毀滅打擊：")).count(), judge.toString());
        final List<String> pitiless = lore(witchhunter, witchhunter.node("pitiless_killer"));
        assertTrue(pitiless.stream().anyMatch(line -> line.startsWith("撲殺：")), pitiless.toString());
        // 2026-10-27 照 PoE2：普通怪撲殺門檻 30% → 35%（CullingStrikeNormalThreshold）
        assertTrue(String.join("", pitiless).contains("普通 35%"), pitiless.toString());
        // 每個只寫了關鍵字（或「提供／你擁有＋關鍵字」）的說明行都有補說明
        final List<String> missing = new ArrayList<>();
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                for (final String line : node.display()) {
                    for (final String term : KeywordText.TERMS.keySet()) {
                        if ((line.equals(term) || line.equals("提供" + term) || line.equals("你擁有" + term))
                                && String.join("", lore(ascendancy, node)).indexOf(term + "：") < 0) {
                            missing.add(ascendancy.id() + "." + node.id() + "：" + line);
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    @Test
    void grantedSkillsShowTheirDescription() {
        final List<String> problems = new ArrayList<>();
        int granted = 0;
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                for (final String id : node.grantedSkills()) {
                    granted++;
                    final SkillDefinition skill = skills.skill(id);
                    if (skill == null) {
                        problems.add(ascendancy.id() + "." + node.id() + " 賦予的技能不存在：" + id);
                        continue;
                    }
                    final List<String> lore = lore(ascendancy, node);
                    if (lore.stream().noneMatch(line -> line.contains("Shift＋點擊"))) {
                        problems.add(ascendancy.id() + "." + node.id() + " 沒有技能詳細的提示");
                    }
                    if (!skill.description().isBlank() && lore.stream().noneMatch(line -> line.contains(skill.description().substring(0, 6)))) {
                        problems.add(ascendancy.id() + "." + node.id() + " 沒有 " + skill.name() + " 的說明");
                    }
                    if (DEV.matcher(skill.description()).find()) {
                        problems.add(id + " 的說明有開發註記：" + skill.description());
                    }
                }
            }
        }
        assertTrue(granted > 30, "賦予的技能太少：" + granted);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void notesAreShownOnce() {
        final AscendancyConfig.Ascendancy forgesmith = config.ascendancy("forgesmith");
        final List<String> lore = lore(forgesmith, forgesmith.node("kitavan_imprint"));
        assertEquals(1, lore.stream().filter(line -> line.contains("配了就有（不看穿什麼胸甲）")).count(), lore.toString());
    }

    @Test
    void passiveTreeDescriptionsHaveNoDevNotesOrRepeats() throws IOException {
        final PassiveTree tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
        final List<String> problems = new ArrayList<>();
        for (final PassiveNode node : tree.ordered()) {
            final Set<String> seen = new HashSet<>();
            final List<String> shown = new ArrayList<>(node.description());
            shown.addAll(KeywordText.explain(node.description()));
            for (final String line : shown) {
                if (DEV.matcher(line).find()) {
                    problems.add(node.id() + " 有開發註記：" + line);
                }
                if (!line.isBlank() && !seen.add(line)) {
                    problems.add(node.id() + " 重複：" + line);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        // 天賦樹的「具有撲殺」也帶出撲殺的說明
        assertTrue(KeywordText.explain(List.of("弓攻擊具有撲殺")).getFirst().startsWith("撲殺："));
    }

    @Test
    void keywordTextExplainsEachTermOnce() {
        assertEquals(List.of(), KeywordText.explain(List.of("毀滅打擊", "毀滅打擊：自己寫了說明")));
        assertEquals(1, KeywordText.explain(List.of("撲殺", "增加 20% 撲殺門檻")).size());
        assertEquals(List.of("a", "b", "", ""), KeywordText.distinct(List.of("a", "b", "a", "", "")));
        for (final String line : KeywordText.wrap("一二三四五六七八九十，一二三四五六七八九十一二三四五", 12)) {
            assertTrue(line.length() <= 12, line);
        }
    }
}
