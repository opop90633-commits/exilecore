package tw.exilecore.character;

import tw.exilecore.item.ItemRenderer;
import tw.exilecore.skill.MinionService;
import tw.exilecore.web.HudFont;

import java.util.List;

/**
 * 動作列的光環與召喚物（2026-10）：套了資源包的人畫在快捷欄兩側（左邊光環、右邊召喚物，{@link HudFont}），
 * 沒套的人照舊接在動作列後面。
 */
final class HotbarSides {

    /** 左邊那段的右緣：快捷欄左緣（−91）再讓出副手格（29）和一點空隙。 */
    private static final int LEFT_EDGE = 124;
    /** 右邊那段的左緣：快捷欄右緣（91）再空一點（攻擊冷卻的指示在快捷欄旁邊時也不會疊到太多）。 */
    private static final int RIGHT_START = 97;
    /** 光環最多寫幾個名字（其他的寫 +N）。 */
    private static final int MAX_AURAS = 4;
    private static final String COLOR = "#8fd3ff";

    private HotbarSides() {
    }

    /**
     * 撿到東西的角落提示（2026-10，{@link LootFeed}）：快捷欄右邊，從 startRow 那一排往上疊，最新的在最下面（MiniMessage，淨寬 0）。
     *
     * @param startRow  從第幾排開始（召喚物佔掉的排數）；放不下回傳空字串
     * @param mainPlain 動作列主要那段的純文字（估寬度用）
     */
    static String renderLoot(final List<LootFeed.Entry> entries, final int startRow, final String mainPlain, final long now) {
        if (entries.isEmpty() || !tw.exilecore.web.LootHud.ready()) {
            return "";
        }
        final int rows = Math.min(entries.size(), tw.exilecore.web.LootHud.ROWS - startRow);
        if (rows <= 0) {
            return "";
        }
        final int half = (int) Math.round(HudFont.estimateVanilla(mainPlain) / 2.0);
        final StringBuilder out = new StringBuilder("<reset>");
        for (int i = 0; i < rows; i++) {
            final LootFeed.Entry entry = entries.get(entries.size() - 1 - i); // 最新的在最下面
            final String text = entry.name() + (entry.count() > 1 ? " ×" + entry.count() : "");
            final boolean faded = entry.until() - now <= LootFeed.FADE_TICKS;
            final int width = tw.exilecore.web.LootHud.cardWidth(text);
            out.append("<font:").append(tw.exilecore.web.LootHud.font(startRow + i)).append(">")
                    .append(tw.exilecore.web.LootHud.shift(RIGHT_START - half))
                    .append(tw.exilecore.web.LootHud.card(entry.color(), entry.icon(), text, faded))
                    .append(tw.exilecore.web.LootHud.shift(half - RIGHT_START - width))
                    .append("</font>");
        }
        return out.toString();
    }

    /** 沒套資源包：接在動作列後面的那段（MiniMessage）；都沒有是空字串。 */
    static String inline(final List<String> auras, final List<MinionService.HudCount> pets) {
        final StringBuilder text = new StringBuilder();
        if (!auras.isEmpty()) {
            text.append("<").append(COLOR).append(">✦");
            int shown = 0;
            for (final String name : auras) {
                if (shown == 3) {
                    text.append(" +").append(auras.size() - shown);
                    break;
                }
                text.append(' ').append(ItemRenderer.escape(name));
                shown++;
            }
        }
        for (final MinionService.HudCount pet : pets) {
            if (!text.isEmpty()) {
                text.append(" ");
            }
            text.append("<").append(COLOR).append(">☠ ").append(ItemRenderer.escape(pet.name()))
                    .append(pet.count() >= pet.limit() ? " <#ffd27f>" : " <white>").append(pet.count()).append("<gray>/").append(pet.limit());
        }
        return text.toString();
    }

    /**
     * 快捷欄兩側的那段（MiniMessage，淨寬 0，接在動作列最後面）；有字畫不出來回傳 null。
     *
     * @param mainPlain 動作列主要那段的純文字（估寬度用：整段置中，兩側照它的一半往回推）
     */
    static String render(final List<String> auras, final List<MinionService.HudCount> pets, final String mainPlain) {
        final int half = (int) Math.round(HudFont.estimateVanilla(mainPlain) / 2.0);
        final StringBuilder out = new StringBuilder("<reset><font:").append(HudFont.FONT).append(">");
        if (!auras.isEmpty()) {
            final StringBuilder names = new StringBuilder();
            int shown = 0;
            for (final String name : auras) {
                if (shown == MAX_AURAS) {
                    names.append(" +").append(auras.size() - shown);
                    break;
                }
                if (shown > 0) {
                    names.append(' ');
                }
                names.append(name);
                shown++;
            }
            final String icon = HudFont.STAR + " ";
            final int width = HudFont.width(icon + names);
            if (width < 0) {
                return null;
            }
            out.append(HudFont.shift(-(half + LEFT_EDGE + width)))
                    .append("<").append(COLOR).append(">").append(HudFont.encode(icon))
                    .append("<white>").append(HudFont.encode(names.toString()))
                    .append(HudFont.shift(LEFT_EDGE + half));
        }
        if (!pets.isEmpty()) {
            // 2026-10：召喚物一行一種，從快捷欄那一排往上疊（超過 32 種才在最後一行寫 +N）
            final int rows = Math.min(pets.size(), HudFont.ROWS);
            for (int row = 0; row < rows; row++) {
                final boolean overflow = row == rows - 1 && pets.size() > rows;
                final StringBuilder segment = new StringBuilder();
                final int w;
                if (overflow) {
                    final String more = "+" + (pets.size() - row);
                    w = HudFont.width(more);
                    if (w < 0) {
                        return null;
                    }
                    segment.append("<gray>").append(HudFont.encode(more));
                } else {
                    final MinionService.HudCount pet = pets.get(row);
                    final String icon = String.valueOf(HudFont.SKULL);
                    final String name = pet.name() + " ";
                    final String count = String.valueOf(pet.count());
                    final String limit = "/" + pet.limit();
                    w = HudFont.width(icon + name + count + limit);
                    if (w < 0) {
                        return null;
                    }
                    segment.append("<").append(COLOR).append(">").append(HudFont.encode(icon))
                            .append("<white>").append(HudFont.encode(name))
                            .append(pet.count() >= pet.limit() ? "<#ffd27f>" : "<white>").append(HudFont.encode(count))
                            .append("<gray>").append(HudFont.encode(limit));
                }
                out.append("</font><font:").append(HudFont.font(row)).append(">")
                        .append(HudFont.shift(RIGHT_START - half)).append(segment).append(HudFont.shift(half - RIGHT_START - w));
            }
        }
        return out.append("</font>").toString();
    }
}
