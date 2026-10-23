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
    /** 撿到東西的角落提示的左緣（2026-10）：2026-10-23 再往右 20 格（以前 RIGHT_START + 10）。 */
    static final int LOOT_START = RIGHT_START + 30;
    /** 有召喚物時：撿到東西的提示放在召喚物那一欄的右邊，中間空這麼多（2026-10-23：以前疊在召喚物上面）。 */
    private static final int LOOT_GAP = 14;
    /** 撿到東西的提示左緣最多到這裡（太右邊會被畫面切掉）。 */
    private static final int LOOT_MAX_START = 200;
    /** 光環最多寫幾個名字（其他的寫 +N）。 */
    private static final int MAX_AURAS = 4;
    private static final String COLOR = "#8fd3ff";

    private HotbarSides() {
    }

    /**
     * 撿到東西的角落提示（2026-10，{@link LootFeed}）：快捷欄右邊，從快捷欄那一排往上疊，最新的在最下面（MiniMessage，淨寬 0）。
     * 2026-10-23：跟召喚物分開：有召喚物時放在召喚物那一欄的右邊（以前疊在召喚物上面）。
     *
     * @param start     左緣（相對畫面中間，{@link #lootStart}）
     * @param mainPlain 動作列主要那段的純文字（估寬度用）
     */
    static String renderLoot(final List<LootFeed.Entry> entries, final int start, final String mainPlain, final long now) {
        final int startRow = 0;
        final int lootStart = start;
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
                    .append(tw.exilecore.web.LootHud.shift(lootStart - half))
                    .append(tw.exilecore.web.LootHud.card(entry.color(), entry.icon(), text, faded))
                    .append(tw.exilecore.web.LootHud.shift(half - lootStart - width))
                    .append("</font>");
        }
        return out.toString();
    }

    /**
     * 撿到東西的提示的左緣：沒有召喚物（或沒畫在快捷欄旁邊）是 {@link #LOOT_START}；
     * 有召喚物就放在召喚物那一欄最寬那行的右邊（最多到 {@link #LOOT_MAX_START}）。
     */
    static int lootStart(final List<MinionService.HudCount> pets, final boolean sides) {
        if (!sides || pets.isEmpty()) {
            return LOOT_START;
        }
        int widest = 0;
        final int rows = Math.min(pets.size(), HudFont.ROWS);
        for (int row = 0; row < rows; row++) {
            final MinionService.HudCount pet = pets.get(row);
            final int w = HudFont.width(HudFont.SKULL + pet.name() + " " + pet.count() + "/" + pet.limit());
            widest = Math.max(widest, w < 0 ? 80 : w);
        }
        return Math.min(LOOT_MAX_START, Math.max(LOOT_START, RIGHT_START + widest + LOOT_GAP));
    }

    /** 快捷列第 slot 格（0–8）的中心，相對畫面中間（GUI 像素）。 */
    static int slotCenter(final int slot) {
        return -80 + 20 * slot;
    }

    /** 增益的顏色：還在、快結束（5 秒內）、沒了（2026-10-20）。 */
    private static final String BUFF_COLOR = "#f5b942";
    private static final String BUFF_ENDING = "#ff9a3c";
    private static final String BUFF_MISSING = "#ff4a4a";
    /** 快捷欄左邊那一排最多寫幾個增益（其他的寫 +N）。 */
    private static final int SIDE_BUFFS = 2;
    /** 沒套資源包時動作列最多寫幾個增益。 */
    private static final int INLINE_BUFFS = 3;

    /** 剩幾 tick → 「12s」「3.5s」（每半秒一格）「2:05」。 */
    static String timeText(final long ticks) {
        final long t = Math.max(0, ticks);
        if (t >= 1200) {
            final long seconds = (t + 19) / 20;
            return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        if (t >= 200) {
            return ((t + 19) / 20) + "s";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", ((t + 9) / 10) / 2.0);
    }

    private static String timeColor(final long ticks) {
        return ticks <= 100 ? BUFF_ENDING : "white";
    }

    /** 沒套資源包：接在動作列後面的那段（MiniMessage）；都沒有是空字串。 */
    static String inline(final List<String> auras, final List<MinionService.HudCount> pets) {
        return inline(auras, pets, tw.exilecore.skill.SkillService.HudBuffs.EMPTY);
    }

    static String inline(final List<String> auras, final List<MinionService.HudCount> pets, final tw.exilecore.skill.SkillService.HudBuffs buffs) {
        final StringBuilder text = new StringBuilder();
        // 2026-10-20 增益倒數：技能格的先寫（沒了的寫紅色），其他的照快結束排
        int written = 0;
        for (final tw.exilecore.skill.SkillService.SlotTimer slot : buffs.slots()) {
            if (written >= INLINE_BUFFS) {
                break;
            }
            text.append(text.isEmpty() ? "" : " ");
            if (slot.missing()) {
                text.append("<").append(BUFF_MISSING).append(">⌛").append(ItemRenderer.escape(slot.name())).append(" 沒了");
            } else {
                text.append("<").append(BUFF_COLOR).append(">⌛").append(ItemRenderer.escape(slot.name()))
                        .append(" <").append(timeColor(slot.ticks())).append(">").append(timeText(slot.ticks()));
            }
            written++;
        }
        for (final tw.exilecore.skill.SkillService.BuffTimer buff : buffs.others()) {
            if (written >= INLINE_BUFFS) {
                break;
            }
            text.append(text.isEmpty() ? "" : " ").append("<").append(BUFF_COLOR).append(">⌛").append(ItemRenderer.escape(buff.name()))
                    .append(" <").append(timeColor(buff.ticks())).append(">").append(timeText(buff.ticks()));
            written++;
        }
        if (!text.isEmpty() && !auras.isEmpty()) {
            text.append(" "); // 召喚物那段自己會補空白
        }
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
        return render(auras, pets, tw.exilecore.skill.SkillService.HudBuffs.EMPTY, mainPlain);
    }

    static String render(final List<String> auras, final List<MinionService.HudCount> pets,
                         final tw.exilecore.skill.SkillService.HudBuffs buffs, final String mainPlain) {
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
        // 2026-10-20 增益倒數：技能格上方畫秒數（增益沒了、在戰鬥中畫紅點）
        for (final tw.exilecore.skill.SkillService.SlotTimer slot : buffs.slots()) {
            final String text = slot.missing() ? String.valueOf(HudFont.SLOT_MISSING) : timeText(slot.ticks());
            final int w = HudFont.width(text);
            if (w < 0) {
                return null;
            }
            final int start = slotCenter(slot.hotbarSlot()) - w / 2;
            out.append("</font><font:").append(HudFont.SLOT_FONT).append(">")
                    .append(HudFont.shift(start - half))
                    .append("<").append(slot.missing() ? BUFF_MISSING : slot.ticks() <= 100 ? BUFF_ENDING : BUFF_COLOR).append(">")
                    .append(HudFont.encode(text))
                    .append(HudFont.shift(half - start - w));
        }
        // 其他的增益（神龕、護符、擊殺後……）：快捷欄左邊只佔一排（光環那一排的上面；沒有光環就在光環那排），
        // 橫著寫最快結束的 {@value #SIDE_BUFFS} 個，其他的寫 +N
        if (!buffs.others().isEmpty()) {
            final StringBuilder segment = new StringBuilder();
            final StringBuilder plain = new StringBuilder();
            final int shown = Math.min(SIDE_BUFFS, buffs.others().size());
            for (int i = 0; i < shown; i++) {
                final tw.exilecore.skill.SkillService.BuffTimer buff = buffs.others().get(i);
                final String name = HudFont.width(buff.name()) >= 0 ? buff.name() : "增益"; // 沒收進字型的名稱（新加的）
                final String time = timeText(buff.ticks());
                final String gap = i == 0 ? "" : "  ";
                segment.append("<").append(BUFF_COLOR).append(">").append(HudFont.encode(gap + name + " "))
                        .append("<").append(timeColor(buff.ticks())).append(">").append(HudFont.encode(time));
                plain.append(gap).append(name).append(' ').append(time);
            }
            if (buffs.others().size() > shown) {
                final String more = "  +" + (buffs.others().size() - shown);
                segment.append("<gray>").append(HudFont.encode(more));
                plain.append(more);
            }
            final int w = HudFont.width(plain.toString());
            if (w < 0) {
                return null;
            }
            out.append("</font><font:").append(HudFont.buffFont(auras.isEmpty() ? 0 : 1)).append(">")
                    .append(HudFont.shift(-(half + LEFT_EDGE + w))).append(segment).append(HudFont.shift(LEFT_EDGE + half));
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
