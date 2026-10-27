package tw.exilecore.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：物品說明的長行自動換行（站長：「有些敘述有點長」）。 */
class TextWrapTest {

    private static final Path DATA = Path.of("src/main/resources/data");

    /** 2026-10-26 精簡前的燼息說明（站長截圖那一行）。 */
    private static final String FLAME_BREATH_OLD = "引導：飛龍按住一直往前噴火（每一次攻擊時間噴一下），最短 3 秒、引導中移動更少 40%；前 4 秒不花怒氣，"
            + "之後每秒花 6 點怒氣撐著（沒怒氣就停），引導中不會獲得怒氣。冷卻 10 秒（開始噴就開始算）。開始時有銳星就用掉一顆強化："
            + "額外獲得 50% 閃電傷害、怒氣花得少一半。點燃機率更多 200%、點燃傷害更少 60%。物理全部轉換為火焰。數字鍵：按一下開始、再按一下結束。（飛龍形態）";

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String joined(final List<Component> lines) {
        final StringBuilder out = new StringBuilder();
        for (final Component line : lines) {
            if (TextWrap.spaceBefore(line)) {
                out.append(' ');
            }
            out.append(plain(line));
        }
        return out.toString();
    }

    @Test
    void widthCountsChineseAsTwo() {
        assertEquals(2, TextWrap.width("中"));
        assertEquals(1, TextWrap.width("a"));
        assertEquals(4 + 1 + 3 + 2, TextWrap.width("燼息 40%，"));
        assertEquals(4, TextWrap.width("（）"));
    }

    @Test
    void longGemDescriptionIsWrappedAndNothingIsLost() {
        final List<Component> lines = TextWrap.lines(List.of("<gray>" + ItemRenderer.escape(FLAME_BREATH_OLD)));
        assertTrue(lines.size() >= 4, "截圖那一行（約 190 字）要切成好幾行：" + lines.size());
        for (final Component line : lines) {
            assertTrue(TextWrap.width(line) <= TextWrap.MAX_WIDTH, plain(line) + " 寬 " + TextWrap.width(line));
        }
        assertEquals(FLAME_BREATH_OLD, joined(lines), "接回去跟原本一樣（不吃字）");
        assertFalse(TextWrap.continuation(lines.get(0)));
        for (int i = 1; i < lines.size(); i++) {
            assertTrue(TextWrap.continuation(lines.get(i)), "接續行有標記，網頁照這個接回去");
            final String text = plain(lines.get(i));
            assertFalse("，。；、：！？）".indexOf(text.charAt(0)) >= 0, "標點不放行首：" + text);
        }
        // 盡量在標點後面斷：大部分行尾是標點
        int punct = 0;
        for (int i = 0; i < lines.size() - 1; i++) {
            final String text = plain(lines.get(i));
            if ("，。；、：！？".indexOf(text.charAt(text.length() - 1)) >= 0) {
                punct++;
            }
        }
        assertTrue(punct * 2 >= lines.size() - 1, "大部分在標點後面斷：" + lines.stream().map(TextWrapTest::plain).toList());
    }

    @Test
    void shortLinesStayTheSameObject() {
        final List<Component> lore = List.of(ItemRenderer.line("<gray>短的說明"), Component.empty());
        assertTrue(lore == TextWrap.lore(lore), "沒有長行就不動（同一個清單）");
    }

    @Test
    void coloursCarryOverToTheNextLine() {
        final String red = "紅色的字".repeat(15); // 120 寬
        final List<Component> lines = TextWrap.lines(List.of("<gray>前面 <red><bold>" + red + "</bold></red> 後面"));
        assertTrue(lines.size() >= 2);
        // 第二行開頭還是紅色粗體（標籤跨行重開）、不是斜體
        final Component first = lines.get(1).children().get(0);
        assertEquals(NamedTextColor.RED, TextColor.color(first.color()));
        assertEquals(TextDecoration.State.TRUE, first.decoration(TextDecoration.BOLD));
        assertEquals(TextDecoration.State.FALSE, first.decoration(TextDecoration.ITALIC));
        final Component last = lines.get(lines.size() - 1);
        final Component tail = last.children().get(last.children().size() - 1);
        assertTrue(((TextComponent) tail).content().contains("後面"));
        assertEquals(NamedTextColor.GRAY, TextColor.color(tail.color()));
    }

    @Test
    void wordsAndNumbersAreNotSplit() {
        final String text = "增加 +40% 的 Lv.20 傷害 " + "abcdefghij ".repeat(10) + "3-5 點";
        final List<String> lines = TextWrap.wrap(text, 30);
        for (final String line : lines) {
            assertTrue(TextWrap.width(line) <= 30, line);
            for (final String word : line.split(" ")) {
                assertTrue(text.contains(word), word);
            }
        }
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("40%") || l.endsWith("+")), lines.toString());
        assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""));
    }

    @Test
    void everyDataDescriptionFitsAfterWrapping() throws Exception {
        final SkillConfig config = new SkillConfig();
        config.load(DATA);
        final List<String> texts = new ArrayList<>();
        for (final SkillDefinition skill : config.all()) {
            texts.add(skill.description());
        }
        for (final SupportDefinition support : config.supports()) {
            texts.add(support.descriptionText());
        }
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        for (final CurrencyType type : currencies.all()) {
            texts.add(type.description());
            texts.add(type.flavor());
        }
        int checked = 0;
        for (final String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // 寶石、通貨的說明行就是這樣畫的（GemService.skillLore／supportLore、CurrencyService.icon）
            final List<Component> lines = TextWrap.lines(List.of("<gray>" + ItemRenderer.escape(text)));
            for (final Component line : lines) {
                assertTrue(TextWrap.width(line) <= TextWrap.MAX_WIDTH, "太寬：" + plain(line));
            }
            assertEquals(text.strip(), joined(lines).strip());
            checked++;
        }
        assertTrue(checked > 500, "有讀到資料：" + checked);
    }
}
