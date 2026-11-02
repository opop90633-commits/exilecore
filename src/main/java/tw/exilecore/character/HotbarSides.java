package tw.exilecore.character;

import tw.exilecore.item.ItemRenderer;
import tw.exilecore.skill.MinionService;
import tw.exilecore.web.HudFont;
import tw.exilecore.web.OrbFont;

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
    /**
     * 2026-11-02 照站長（生命球、魔力球貼著快捷欄兩側，{@link OrbHud}）：有畫球的時候兩側的字從球的外側開始（空 4 格）；
     * 這次之前左邊從 {@value #LEFT_EDGE}、右邊從 {@value #RIGHT_START} 開始，會被球蓋住。
     */
    static final int ORB_EDGE = tw.exilecore.web.OrbFont.OUTER + 4;

    static int leftEdge(final boolean orbs) {
        return orbs ? Math.max(LEFT_EDGE, ORB_EDGE) : LEFT_EDGE;
    }

    static int rightStart(final boolean orbs) {
        return orbs ? Math.max(RIGHT_START, ORB_EDGE) : RIGHT_START;
    }
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
        return lootStart(pets, sides, false);
    }

    /** orbs = 有畫生命球、魔力球（2026-11-02：整個往外移到球的外側）。 */
    static int lootStart(final List<MinionService.HudCount> pets, final boolean sides, final boolean orbs) {
        final int shift = rightStart(orbs) - RIGHT_START;
        if (!sides || pets.isEmpty()) {
            return LOOT_START + shift;
        }
        int widest = 0;
        final int rows = Math.min(pets.size(), HudFont.ROWS);
        for (int row = 0; row < rows; row++) {
            final MinionService.HudCount pet = pets.get(row);
            final int w = HudFont.width(HudFont.SKULL + pet.name() + " " + pet.count() + "/" + pet.limit());
            widest = Math.max(widest, w < 0 ? 80 : w);
        }
        return Math.min(LOOT_MAX_START + shift, Math.max(LOOT_START + shift, rightStart(orbs) + widest + LOOT_GAP));
    }

    /** 快捷列第 slot 格（0–8）的中心，相對畫面中間（GUI 像素）。 */
    static int slotCenter(final int slot) {
        return -80 + 20 * slot;
    }

    /** 增益的顏色：還在、快結束（5 秒內）、沒了（2026-10-20）。 */
    private static final String BUFF_COLOR = "#f5b942";
    private static final String BUFF_ENDING = "#ff9a3c";
    private static final String BUFF_MISSING = "#ff4a4a";
    /** 2026-10-31：技能格上方的榮耀：還不夠、夠了。 */
    private static final String GLORY_COLOR = "#c9a7ff";
    private static final String GLORY_READY = "#7dff8a";
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
            } else if (slot.label() != null) {
                text.append("<").append(slot.ready() ? GLORY_READY : GLORY_COLOR).append(">✧").append(ItemRenderer.escape(slot.name()))
                        .append(" 榮耀 ").append(slot.label()); // 2026-10-31
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
        return render(auras, pets, buffs, mainPlain, false);
    }

    /**
     * orbs = 有畫生命球、魔力球（2026-11-02：左右兩段從球的外側開始）。
     * <p>
     * 2026-11-02 照站長（第二版，資源包版不擠）：
     * <ul>
     *   <li><b>光環</b>（沒有倒數）：最底下一行（字頂在畫面高 − {@value HudFont#AURA_TOP}，聊天欄最下面那行的下面），
     *       著色器（{@link tw.exilecore.web.PartyFont}）整行搬到畫面左下角；著色器沒作用時待在生命球左邊（右緣在 −{@value #AURA_EDGE}）。
     *       顏色帶記號：G = 209、B = 253～255（灰、圖示、名稱），R = 這行的寬度 ÷ 2（著色器照這個算要搬多遠）。</li>
     *   <li><b>有倒數的增益</b>（技能格的、其他的，增益沒了的提醒）：全部寫「名稱 秒數」，從生命球左邊（右緣在 −leftEdge）第 1 排往上排一欄
     *       （{@value HudFont#BUFF_ROWS} 排），放不下往左再開一欄，最左到 −{@value #LEFT_LIMIT}（GUI 縮放 4 的 1080p 畫面左邊）；
     *       還是塞不下改用小一號的字（{@value HudFont#SMALL_ROWS} 排），再塞不下最後一格寫「+N」。重要的（護符、神龕）、快結束的排最下面（靠球）。</li>
     *   <li><b>榮耀</b>：技能格正上方的小點陣數字「現在/需要」（夠了綠色），在經驗條上面（原本愛心那一排）；只畫要榮耀的那幾格。</li>
     *   <li><b>召喚物</b>：魔力球右邊，從快捷欄那一排往上疊（照舊）。</li>
     * </ul>
     * 2026-11-02 照站長（第三版，不擋聊天）：增益（第 3 排以上進到聊天框）與召喚物另外畫一份到<b>畫面右下角</b>（{@link #cornerBlock}，
     * 著色器搬過去）；上面那份（生命球左邊、魔力球右邊）變成著色器沒作用（光影模組）時的備用：顏色帶記號（{@link #fallback}）、
     * 影子是 {@value #FALLBACK_SHADOW}，著色器看到就藏起來，沒作用時照舊看得到。撿到東西改進右邊記分板（PlayerStateService.lootToSidebar）。
     * 這次之前：光環在生命球左邊快捷欄那一排、增益只寫最快結束的 2 個（其他 +N）、技能格的增益秒數與榮耀寫在經驗條上（跟等級數字、經驗條疊在一起）。
     */
    static String render(final List<String> auras, final List<MinionService.HudCount> pets,
                         final tw.exilecore.skill.SkillService.HudBuffs buffs, final String mainPlain, final boolean orbs) {
        final int leftEdge = leftEdge(orbs);
        final int rightStart = rightStart(orbs);
        final int half = (int) Math.round(HudFont.estimateVanilla(mainPlain) / 2.0);
        final StringBuilder out = new StringBuilder("<reset>");
        if (!auras.isEmpty()) {
            final String line = auraLine(auras, half);
            if (line == null) {
                return null;
            }
            out.append(line);
        }
        // 榮耀：技能格正上方的小數字
        for (final tw.exilecore.skill.SkillService.SlotTimer slot : buffs.slots()) {
            if (slot.label() == null) {
                continue;
            }
            final String text = slot.label();
            final int w = OrbFont.width(text);
            final int center = slotCenter(slot.hotbarSlot());
            final int start = center - w / 2;
            out.append("<font:").append(Math.abs(center) < GLORY_CENTER_GAP ? HudFont.GLORY_UP_FONT : HudFont.GLORY_FONT).append(">")
                    .append("<font:").append(HudFont.FONT).append(">").append(HudFont.shift(start - half)).append("</font>")
                    .append("<").append(slot.ready() ? GLORY_READY : GLORY_COLOR).append(">").append(text)
                    .append("<font:").append(HudFont.FONT).append(">").append(HudFont.shift(half - start - w)).append("</font>")
                    .append("</font>");
        }
        // 有倒數的增益：生命球左邊往上排（2026-11-02：這份是著色器沒作用時的備用，顏色帶記號、著色器藏起來）
        final List<BuffLine> lines = buffLines(buffs);
        if (!lines.isEmpty() || !pets.isEmpty()) {
            final String corner = cornerBlock(pets, lines, half);
            if (corner == null) {
                return null;
            }
            out.append(corner);
            out.append("<shadow:").append(FALLBACK_SHADOW).append(">");
        }
        if (!lines.isEmpty()) {
            final String column = buffColumn(lines, leftEdge, half);
            if (column == null) {
                return null;
            }
            out.append(column);
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
                    segment.append("<").append(fallback(GRAY)).append(">").append(HudFont.encode(more));
                } else {
                    final MinionService.HudCount pet = pets.get(row);
                    w = HudFont.width(petText(pet));
                    if (w < 0) {
                        return null;
                    }
                    segment.append(petMini(pet, true));
                }
                out.append("<font:").append(HudFont.font(row)).append(">")
                        .append(HudFont.shift(rightStart - half)).append(segment).append(HudFont.shift(half - rightStart - w)).append("</font>");
            }
        }
        if (!lines.isEmpty() || !pets.isEmpty()) {
            out.append("</shadow>");
        }
        return out.toString();
    }

    /** 2026-11-02：光環那一行的右緣（著色器沒作用時；著色器也照這個算，見 PartyFont.AURA_EDGE）。 */
    public static final int AURA_EDGE = tw.exilecore.web.PartyFont.AURA_EDGE;
    /** 2026-11-02：增益最左排到畫面中間往左幾格（GUI 縮放 4 的 1080p 畫面寬 480，留 2 格）。 */
    static final int LEFT_LIMIT = 238;
    /** 正中間那格的榮耀離等級數字太近（{@code |中心| <} 這個）：改用往上 8 格的字型。 */
    static final int GLORY_CENTER_GAP = 14;

    /** 光環那一行（MiniMessage，淨寬 0）；有字畫不出來回傳 null。 */
    static String auraLine(final List<String> auras, final int half) {
        final StringBuilder names = new StringBuilder();
        int shown = 0;
        String more = "";
        for (final String name : auras) {
            if (shown == MAX_AURAS) {
                more = " +" + (auras.size() - shown);
                break;
            }
            if (shown > 0) {
                names.append(' ');
            }
            names.append(name);
            shown++;
        }
        final String icon = HudFont.STAR + " ";
        final int width = HudFont.width(icon + names + more);
        if (width < 0) {
            return null;
        }
        final int start = -(AURA_EDGE + width);
        return "<font:" + HudFont.AURA_FONT + ">" + HudFont.shift(start - half)
                + "<" + auraColor(width, tw.exilecore.web.PartyFont.AURA_ICON) + ">" + HudFont.encode(icon)
                + "<" + auraColor(width, tw.exilecore.web.PartyFont.AURA_NAME) + ">" + HudFont.encode(names.toString())
                + (more.isEmpty() ? "" : "<" + auraColor(width, tw.exilecore.web.PartyFont.AURA_MORE) + ">" + HudFont.encode(more))
                + HudFont.shift(half - start - width) + "</font>";
    }

    /** 光環那一行的顏色記號：R = 寬度 ÷ 2、G = 209、B = 種類（253 灰、254 圖示、255 名稱）。純函式（測試用）。 */
    static String auraColor(final int width, final int kind) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", tw.exilecore.web.PartyFont.auraWidthCode(width),
                tw.exilecore.web.PartyFont.AURA_MARK, kind);
    }

    /** 增益的一行：名稱、時間、顏色；sort = 排序用（越小越靠球）。 */
    record BuffLine(String name, String time, String nameColor, String timeColor, int tier, long ticks) {
        String text() {
            return name + " " + time;
        }
    }

    /**
     * 有倒數的增益（技能格的、其他的、沒了的提醒；榮耀不算），照 重要的（護符、神龕）→ 沒了的提醒、技能格的 → 其他，同一級快結束的在前。
     * 純函式（測試用）。
     */
    static List<BuffLine> buffLines(final tw.exilecore.skill.SkillService.HudBuffs buffs) {
        final List<BuffLine> lines = new java.util.ArrayList<>();
        for (final tw.exilecore.skill.SkillService.SlotTimer slot : buffs.slots()) {
            if (slot.label() != null) {
                continue; // 榮耀：技能格上方
            }
            final String name = HudFont.width(slot.name()) >= 0 ? slot.name() : "增益";
            if (slot.missing()) {
                lines.add(new BuffLine(name, "沒了", BUFF_MISSING, BUFF_MISSING, 1, 0));
            } else if (slot.ticks() > 0) {
                lines.add(new BuffLine(name, timeText(slot.ticks()), BUFF_COLOR, timeColor(slot.ticks()), 1, slot.ticks()));
            }
        }
        for (final tw.exilecore.skill.SkillService.BuffTimer buff : buffs.others()) {
            final String name = HudFont.width(buff.name()) >= 0 ? buff.name() : "增益"; // 沒收進字型的名稱（新加的）
            lines.add(new BuffLine(name, timeText(buff.ticks()), buff.important() ? IMPORTANT_COLOR : BUFF_COLOR, timeColor(buff.ticks()),
                    buff.important() ? 0 : 2, buff.ticks()));
        }
        lines.sort(java.util.Comparator.comparingInt(BuffLine::tier).thenComparingLong(BuffLine::ticks));
        return lines;
    }

    /** 重要的增益（護符、神龕）的名稱顏色。 */
    private static final String IMPORTANT_COLOR = "#ffe08a";

    /** 增益排好的位置：第幾個放在哪一欄的哪一排（x = 這一格的右緣，相對畫面中間）。 */
    record Slot(int index, int right, int row) {
    }

    /** 排法：small = 用小一號的字；slots = 放得下的（最後一格是「+N」時 more > 0）。 */
    record Placement(boolean small, List<Slot> slots, int more) {
    }

    /**
     * 增益怎麼排（純函式，測試用）：從 right（負的，生命球左邊）往上排 rows 排，放不下往左再開一欄（中間空 gap），最左到 −limit；
     * 大字放不下改小字，小字也放不下最後一格寫「+N」。
     *
     * @param widths      每一行大字的寬度
     * @param smallWidths 每一行小字的寬度
     */
    static Placement placeBuffs(final List<Integer> widths, final List<Integer> smallWidths, final int right, final int limit) {
        final Placement big = place(widths, right, limit, HudFont.BUFF_ROWS, 6);
        if (big.slots().size() == widths.size()) {
            return big;
        }
        final Placement small = place(smallWidths, right, limit, HudFont.SMALL_ROWS, 4);
        if (small.slots().size() == widths.size() || small.slots().isEmpty()) {
            return new Placement(true, small.slots(), widths.size() - small.slots().size());
        }
        // 最後一格改成「+N」
        final List<Slot> slots = new java.util.ArrayList<>(small.slots());
        slots.removeLast();
        return new Placement(true, slots, widths.size() - slots.size());
    }

    private static Placement place(final List<Integer> widths, final int right, final int limit, final int rows, final int gap) {
        final List<Slot> slots = new java.util.ArrayList<>();
        int x = right;
        for (int i = 0; i < widths.size(); i += rows) {
            int column = 0;
            for (int k = i; k < Math.min(widths.size(), i + rows); k++) {
                column = Math.max(column, widths.get(k));
            }
            if (x - column < -limit) {
                break;
            }
            for (int k = i; k < Math.min(widths.size(), i + rows); k++) {
                slots.add(new Slot(k, x, k - i));
            }
            x -= column + gap;
        }
        return new Placement(false, slots, 0);
    }

    /** 增益那幾欄（MiniMessage，淨寬 0）；有字畫不出來回傳 null。 */
    static String buffColumn(final List<BuffLine> lines, final int leftEdge, final int half) {
        final List<Integer> widths = new java.util.ArrayList<>();
        final List<Integer> smallWidths = new java.util.ArrayList<>();
        for (final BuffLine line : lines) {
            final int w = HudFont.width(line.text());
            final int sw = HudFont.smallWidth(line.text());
            if (w < 0 || sw < 0) {
                return null;
            }
            widths.add(w);
            smallWidths.add(sw);
        }
        final Placement placement = placeBuffs(widths, smallWidths, -leftEdge, LEFT_LIMIT);
        final StringBuilder out = new StringBuilder();
        for (final Slot slot : placement.slots()) {
            final BuffLine line = lines.get(slot.index());
            final int w = placement.small() ? smallWidths.get(slot.index()) : widths.get(slot.index());
            out.append(cell(placement.small(), slot, w, half, "<" + fallback(line.nameColor()) + ">" + HudFont.encode(line.name() + " ")
                    + "<" + fallback(line.timeColor()) + ">" + HudFont.encode(line.time())));
        }
        if (placement.more() > 0) {
            final String more = "+" + placement.more();
            final int index = placement.slots().size();
            final int rows = placement.small() ? HudFont.SMALL_ROWS : HudFont.BUFF_ROWS;
            final Slot last = placement.slots().isEmpty() ? new Slot(index, -leftEdge, 0) : placement.slots().getLast();
            final Slot at = last.row() + 1 < rows ? new Slot(index, last.right(), last.row() + 1) : new Slot(index, last.right(), last.row());
            final int w = placement.small() ? HudFont.smallWidth(more) : HudFont.width(more);
            out.append(cell(placement.small(), at, w, half, "<" + fallback(GRAY) + ">" + HudFont.encode(more)));
        }
        return out.toString();
    }

    private static String cell(final boolean small, final Slot slot, final int width, final int half, final String body) {
        final String font = small ? HudFont.smallFont(slot.row()) : "exilecore:hud_b" + (slot.row() + 1);
        final int start = slot.right() - width;
        return "<font:" + font + ">" + HudFont.shift(start - half) + body + HudFont.shift(half - start - width) + "</font>";
    }

    // ---- 2026-11-02 照站長（第三版）：增益、召喚物搬到畫面右下角（資源包版） ----

    private static final String GRAY = "#aaaaaa";
    /** 備用那份（著色器沒作用時看得到的）的影子顏色：著色器看到這個顏色就藏起來（見 PartyFont.FALLBACK_SHADOW）。 */
    static final String FALLBACK_SHADOW = "#010203:0.9";

    /** 備用那份的顏色：真正的顏色差一點點（{@link tw.exilecore.web.PartyFont#fallbackColor}），著色器照這個認出來藏起來。 */
    static String fallback(final String color) {
        final int rgb = switch (color) {
            case "white" -> 0xffffff;
            case "gray" -> 0xaaaaaa;
            default -> Integer.parseInt(color.substring(1), 16);
        };
        return String.format(java.util.Locale.ROOT, "#%06x", tw.exilecore.web.PartyFont.fallbackColor(rgb));
    }

    /** 召喚物一行的純文字（算寬度用）。 */
    static String petText(final MinionService.HudCount pet) {
        return HudFont.SKULL + pet.name() + " " + pet.count() + "/" + pet.limit();
    }

    /** 召喚物一行（MiniMessage）；fallback = 備用那份（顏色帶記號）。 */
    static String petMini(final MinionService.HudCount pet, final boolean fallback) {
        final String full = pet.count() >= pet.limit() ? "#ffd27f" : "#ffffff";
        return "<" + (fallback ? fallback(COLOR) : COLOR) + ">" + HudFont.encode(String.valueOf(HudFont.SKULL))
                + "<" + (fallback ? fallback("#ffffff") : "#ffffff") + ">" + HudFont.encode(pet.name() + " ")
                + "<" + (fallback ? fallback(full) : full) + ">" + HudFont.encode(String.valueOf(pet.count()))
                + "<" + (fallback ? fallback(GRAY) : GRAY) + ">" + HudFont.encode("/" + pet.limit());
    }

    /** 右下角最多幾排（第 0 排是快捷欄那一排）：第 7 排的字頂在畫面高 − 85，GUI 縮放 4（1080p，高 270）時記分板 15 行的下緣在 − 90。 */
    static final int CORNER_ROWS = 8;
    /** 第二欄以後從第幾排開始（下面是魔力球：GUI 縮放 4 時第一欄以外會疊到球）。小字是第 3 排（字底在畫面高 − 44）。 */
    static final int CORNER_UPPER = 4;
    static final int CORNER_UPPER_SMALL = 3;
    /** 右下角整塊最寬多少：GUI 縮放 4（1080p，寬 480）時從右邊往左 3＋146 = 331，聊天框（預設 320 寬＋邊）右緣在 328。 */
    static final int CORNER_WIDTH = 146;

    /** 右下角的排法：small = 小字；slots 的 right = 這一格的右緣（離畫面右邊幾格，≤ 0）；moreAt = 「+N」那格（more = 0 時 null）。 */
    record Corner(boolean small, List<Slot> slots, int more, Slot moreAt) {
    }

    /**
     * 右下角怎麼排（純函式，測試用）：從畫面右邊往左一欄一欄排、每欄由快捷欄那一排往上（第 0～{@value #CORNER_ROWS} − 1 排），
     * 第二欄以後只用魔力球上面那幾排（第 {@value #CORNER_UPPER} 排起），整塊不超過 {@value #CORNER_WIDTH} 寬；
     * 大字放不下改小字（{@link HudFont#SMALL_STEP} 一排，同樣 8 排），再放不下最後一格寫「+N」。
     */
    static Corner placeCorner(final List<Integer> widths, final List<Integer> smallWidths) {
        final List<Slot> big = corner(widths, CORNER_UPPER, 6);
        if (big.size() == widths.size()) {
            return new Corner(false, big, 0, null);
        }
        final List<Slot> small = corner(smallWidths, CORNER_UPPER_SMALL, 4);
        if (small.size() == widths.size()) {
            return new Corner(true, small, 0, null);
        }
        if (small.isEmpty()) {
            return new Corner(true, small, widths.size(), new Slot(0, 0, 0));
        }
        final List<Slot> slots = new java.util.ArrayList<>(small);
        final Slot last = slots.removeLast();
        return new Corner(true, slots, widths.size() - slots.size(), last);
    }

    private static List<Slot> corner(final List<Integer> widths, final int upper, final int gap) {
        final List<Slot> slots = new java.util.ArrayList<>();
        int x = 0;
        int i = 0;
        for (int column = 0; i < widths.size(); column++) {
            final int first = column == 0 ? 0 : upper;
            final int end = Math.min(widths.size(), i + CORNER_ROWS - first);
            int w = 0;
            for (int k = i; k < end; k++) {
                w = Math.max(w, widths.get(k));
            }
            if (-x + w > CORNER_WIDTH) {
                break;
            }
            for (int k = i; k < end; k++) {
                slots.add(new Slot(k, x, first + k - i));
            }
            x -= w + gap;
            i = end;
        }
        return slots;
    }

    /**
     * 右下角那一塊（MiniMessage，淨寬 0）：召喚物（排最下面）、有倒數的增益；先往右推 {@link tw.exilecore.web.PartyFont#CORNER_SHIFT} 格
     * （著色器沒作用時在畫面外面，看不到），著色器把它搬到離畫面右邊 {@link tw.exilecore.web.PartyFont#CORNER_RIGHT} 格。顏色是真正的顏色。
     * 有字畫不出來回傳 null。
     */
    static String cornerBlock(final List<MinionService.HudCount> pets, final List<BuffLine> lines, final int half) {
        final List<String> bodies = new java.util.ArrayList<>();
        final List<Integer> widths = new java.util.ArrayList<>();
        final List<Integer> smallWidths = new java.util.ArrayList<>();
        for (final MinionService.HudCount pet : pets) {
            bodies.add(petMini(pet, false));
            widths.add(HudFont.width(petText(pet)));
            smallWidths.add(HudFont.smallWidth(petText(pet)));
        }
        for (final BuffLine line : lines) {
            bodies.add("<" + line.nameColor() + ">" + HudFont.encode(line.name() + " ") + "<" + line.timeColor() + ">" + HudFont.encode(line.time()));
            widths.add(HudFont.width(line.text()));
            smallWidths.add(HudFont.smallWidth(line.text()));
        }
        if (widths.contains(-1) || smallWidths.contains(-1)) {
            return null;
        }
        final Corner placement = placeCorner(widths, smallWidths);
        final StringBuilder out = new StringBuilder();
        for (final Slot slot : placement.slots()) {
            final int w = placement.small() ? smallWidths.get(slot.index()) : widths.get(slot.index());
            out.append(cornerCell(placement.small(), slot, w, half, bodies.get(slot.index())));
        }
        if (placement.more() > 0) {
            final String more = "+" + placement.more();
            final int w = placement.small() ? HudFont.smallWidth(more) : HudFont.width(more);
            out.append(cornerCell(placement.small(), placement.moreAt(), w, half, "<" + GRAY + ">" + HudFont.encode(more)));
        }
        return out.toString();
    }

    private static String cornerCell(final boolean small, final Slot slot, final int width, final int half, final String body) {
        final String font = small ? HudFont.smallFont(slot.row()) : HudFont.buffFont(slot.row());
        final int start = tw.exilecore.web.PartyFont.CORNER_SHIFT + slot.right() - width;
        return "<font:" + font + ">" + HudFont.shift(start - half) + body + HudFont.shift(half - start - width) + "</font>";
    }
}
