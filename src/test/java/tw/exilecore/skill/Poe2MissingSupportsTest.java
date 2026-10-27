package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照站長「PoE2 0.5 還沒有的一般輔助寶石（不含血脈）全部補上」：tools/apply_poe2_missing_supports.py 寫進 supports.yml 的 120 顆
 * （清單與狀態在 tools/poe2_supports/missing_0.5.tsv），與防呆（SupportGuards：掛了完全沒作用的技能不讓掛）。
 */
class Poe2MissingSupportsTest {

    static SkillConfig config;
    static List<String> added;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        added = new ArrayList<>();
        for (final String line : Files.readAllLines(Path.of("tools/poe2_supports/missing_0.5.tsv"), StandardCharsets.UTF_8)) {
            final String[] cells = line.split("\t", -1);
            if (!line.startsWith("#") && cells.length > 7 && (cells[7].equals("exact") || cells[7].equals("approx"))) {
                added.add(cells[4]);
            }
        }
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition skill = config.skill(id);
        assertNotNull(skill, id);
        return skill;
    }

    @Test
    void everyAddedSupportLoadsWithColourAndCanBeCut() {
        assertEquals(120 - 4, added.size(), "清單上加了 120 顆（2026-10-27 照站長刪掉箭幕、跛行、活雷 II、衝勢，tsv 標 removed：剩 116 顆）");
        final List<String> untexted = new ArrayList<>();
        for (final String id : added) {
            for (final Modifier modifier : support(id).modifiers()) {
                if (ModifierText.describe(modifier).startsWith("特殊效果")) {
                    untexted.add(id + ":" + modifier.stat());
                }
            }
        }
        assertEquals(List.of(), untexted, "每條詞綴都要有中文說明");
        for (final String id : added) {
            final SupportDefinition support = support(id);
            assertTrue(support.cuttable(), id + " 從未切割輔助寶石切得出來");
            assertTrue(support.tier() >= 1 && support.tier() <= 3, id);
            assertTrue(SupportColours.colour(id) != 0, id + " 要有顏色");
            assertTrue(support.manaMultiplier() >= 1.0, id);
            for (final String line : ModifierText.describeAll(support.modifiers())) {
                assertFalse(line.startsWith("特殊效果"), id + " 的說明：" + line);
            }
            // 至少有一個玩家學得到的技能掛得上去（不然切出來是廢物）
            assertTrue(config.visible().stream().anyMatch(support::supports), id + " 沒有任何技能掛得上去");
        }
    }

    @Test
    void guardsRefuseSkillsWhereTheSupportDoesNothing() {
        // 新星改在準心放：要新星
        assertNull(support("astral_projection").refusal(skill("frost_nova")));
        assertEquals("要繞身邊一圈的技能（新星）", support("astral_projection").refusal(skill("fireball")));
        // 連瀑：落在目標區域或身邊一圈的範圍法術
        assertNull(support("spell_cascade").refusal(skill("comet")));
        assertNotNull(support("spell_cascade").refusal(skill("fireball")));
        // 引導的增益：要引導或蓄力的技能
        assertEquals("要引導或蓄力的技能", support("steadfast_ii").refusal(skill("fireball")));
        // 弓的投射物攻擊
        assertNull(support("charged_shots_ii").refusal(skill("lightning_arrow")));
        assertNotNull(support("charged_shots_ii").refusal(skill("fireball")));
        // 牆圍成一圈：要一道牆
        assertNull(support("fortress_ii").refusal(skill("flame_wall")));
        assertEquals("要一道牆的技能", support("fortress_ii").refusal(skill("comet")));
        // 跳躍落地的聖化地面：要跳躍技能
        assertNull(support("holy_descent").refusal(skill("leap_slam")));
        assertEquals("要跳躍技能", support("holy_descent").refusal(skill("fireball")));
        // 冰晶多打兩下：要會留下冰晶的技能
        assertNull(support("durability").refusal(skill("ice_wall")));
        assertEquals("要會留下冰晶的技能", support("durability").refusal(skill("fireball")));
        // 冷卻次數加倍：要有冷卻
        assertNotNull(support("second_wind_iii").refusal(skill("fireball")));
        // 點燃時曝露：冰冷法術不會點燃
        assertEquals("要會點燃的技能（火焰技能或攻擊）", support("fire_exposure").refusal(skill("frost_nova")));
        assertNull(support("fire_exposure").refusal(skill("fireball")));
        // 印記吸取：詛咒不行
        assertNull(support("mark_of_siphoning_ii").refusal(skill("hunters_mark")));
        // 盾牌技能
        assertNull(support("vanguard_ii").refusal(skill("shield_rush")));
        assertEquals("要盾牌技能", support("vanguard_ii").refusal(skill("boneshatter")));
        // 有使用條件的技能（Delayed Gratification）
        assertEquals("要有使用條件的技能（要榮耀、連擊才能用的）", support("delayed_gratification").refusal(skill("fireball")));
        // 投射物的機制：要真的射出投射物
        assertNotNull(support("gambleshot").refusal(skill("frost_nova")));
        assertNull(support("gambleshot").refusal(skill("fireball")));
        // 先祖強化：要打擊
        assertNotNull(support("ancestral_aid").refusal(skill("fireball")));
    }

    @Test
    void textsAndStatics() {
        assertEquals("對致盲的敵人暴擊率與暴擊傷害加成更多 15%",
                ModifierText.describe(new Modifier(SupportExtraKeys.CRIT_VS_BLINDED, ModType.FLAT, 15, Set.of(), "")));
        assertEquals("放這個技能的那段時間，每 0.25 秒攻擊／施法時間：增加 25% 護甲",
                ModifierText.describe(new Modifier(SupportExtraKeys.USING_QUARTER_PREFIX + "armour", ModType.INCREASED, 0.25, Set.of(), "")));
        final StatSheet sheet = new StatSheet().overlay(support("delayed_gratification").modifiers());
        assertEquals(2.5, SupportExtras.conditionScale(sheet, Set.of()), 1e-9, "條件更難 150%");
    }
}
