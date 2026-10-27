package tw.exilecore.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.web.PlannerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：網頁規劃器的名詞表（虛線底線、滑上去看解釋）。 */
class GlossaryTest {

    private static final Pattern BRAND = Pattern.compile("PoE|Path of Exile|流亡黯道");

    @Test
    void includesKeywordTextAndCommonTerms() {
        final Map<String, String> terms = Glossary.terms();
        KeywordText.TERMS.forEach((k, v) -> assertEquals(v, terms.get(k), "KeywordText 的 " + k + " 要一樣"));
        for (final String term : List.of("火焰傷害", "混沌傷害", "易燃", "點燃幅度", "點燃", "冰緩", "凍結", "感電", "電擊", "流血", "中毒",
                "蓄勢待凍", "重度暈眩", "目眩", "阻礙", "致盲", "凋零", "能量護盾", "護甲", "閃避值", "偏斜", "格擋", "精魂", "怒氣",
                "銳星", "怒星", "磐星", "灌注", "殘骸", "榮耀", "法術", "攻擊", "近戰", "投射物", "引導", "持續時間", "召喚物", "圖騰",
                "戰吼", "印記", "詛咒", "光環", "引星", "變身")) {
            assertTrue(terms.containsKey(term), "少了 " + term);
        }
        assertTrue(terms.size() >= 90, "名詞數量 " + terms.size());
    }

    @Test
    void explanationsAreShortAndPlayerSafe() {
        for (final Map.Entry<String, String> e : Glossary.terms().entrySet()) {
            assertFalse(e.getValue().isBlank(), e.getKey());
            assertTrue(e.getValue().length() <= 120, e.getKey() + " 的說明太長");
            assertFalse(BRAND.matcher(e.getKey() + e.getValue()).find(), e.getKey() + " 提到別的遊戲");
        }
    }

    /** 2026-10-27 照站長：天賦樹說明裡的機制名詞（例如「風暴統治者」的元素統治者）要有說明。 */
    @Test
    void treeMechanicTermsAreExplained() {
        final Map<String, String> terms = Glossary.terms();
        for (final String term : List.of("元素統治者", "不死統治者", "統治者增益", "統治者恢復期", "易爆之力", "切割", "傀儡大師", "先祖強化",
                "強化攻擊", "連擊", "餘震", "碎地", "危害", "荊棘", "補償", "超越機率", "招架", "猛攻", "暴擊弱點", "開放弱點", "穿刺",
                "被包圍", "觸電", "暈眩門檻", "異常狀態門檻", "指令技能", "捷光環", "煉獄之炎", "命匣", "元素湧動", "黑暗", "熱度")) {
            assertTrue(terms.containsKey(term), "少了 " + term);
        }
        // 「你最近的圖騰」是距離最近，不是過去 4 秒（網頁照最長的詞先比對）
        assertTrue(terms.get("最近的圖騰").contains("離你最近"));
    }

    /** 2026-10-27：說明是給玩家看的，不能有開發筆記的用語。 */
    @Test
    void explanationsHaveNoDevNotes() {
        final Pattern devNote = Pattern.compile("本伺服器|這次之前|照 ?PoE|平衡等效|公尺");
        for (final Map.Entry<String, String> e : Glossary.terms().entrySet()) {
            assertFalse(devNote.matcher(e.getKey() + e.getValue()).find(), e.getKey() + " 的說明有開發筆記用語：" + e.getValue());
        }
    }

    @Test
    void plannerDataCarriesGlossary() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final byte[] bytes = PlannerData.json(tree, List.of(new PlannerData.ClassInfo("witch", "混沌術士", "#aa55ff")), 100, 1, 24);
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        final JsonObject glossary = root.getAsJsonObject("glossary");
        assertEquals(Glossary.terms().size(), glossary.size());
        assertEquals(Glossary.terms().get("易燃"), glossary.get("易燃").getAsString());
    }
}
