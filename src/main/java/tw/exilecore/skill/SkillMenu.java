package tw.exilecore.skill;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能面板（/skills）。2026-10 重新排過（拿掉 F 鍵切換的 B 組，改成數字鍵 3–8；技能三頁）：
 * <pre>
 * 第 2 排：滑鼠的 4 個按鍵格（左、右、潛行＋左、潛行＋右）；右邊是「選起來的技能」、⬆ 升一級、取出成寶石
 * 第 3 排：數字鍵 3–8 的 6 個按鍵格；最右邊換頁
 * 第 4 排：沒套用資源包時指著技能的玻璃片
 * 第 5 排：技能（這一頁的 9 個，一共三頁 27 個）
 * 第 6 排：← 回選單、選起來的技能的輔助孔、說明、關閉
 * </pre>
 * <ul>
 *   <li>綁按鍵（兩種順序都可以）：點技能再點按鍵格，或點按鍵格再點技能；滑鼠移到技能上按 1 綁左鍵、2 綁右鍵、3–8 綁那個數字鍵；
 *       右鍵點按鍵格改回普攻。每個按鍵格直接顯示綁的技能的圖示與名稱。</li>
 *   <li>光環、精魂召喚要綁在上方按鍵格才開著（常駐，不用按）。</li>
 *   <li>寶石（PoE2）：從背包拿起寶石（拖過來），能放的空格變成綠色、能放的技能亮起來、放不下的變暗，
 *       點那一格放下：技能寶石放進技能格、輔助寶石掛到技能或輔助孔、
 *       未切割技能寶石點技能升一級、鑿星石點技能開孔；Shift＋點技能取出成寶石，點掛著的輔助拿下來</li>
 *   <li>亮起來：套用介面資源包的人看到外框（選到的金色、可以放的綠色，{@link Menu#highlight}）；
 *       沒套用的人在技能上面那一排看到綠色玻璃片指著（選起來的、可以放的），先點的按鍵格變成綠色玻璃片</li>
 * </ul>
 * 可以從自己的背包拿東西（游標），選單上的圖示拿不走。
 */
public final class SkillMenu extends Menu {

    /** 按鍵格（照 {@link SkillLoadout} 的按鍵格子順序）：滑鼠的 4 格在第 2 排、數字鍵 3–8 在第 3 排。 */
    private static final int[] BIND = {10, 11, 12, 13, 19, 20, 21, 22, 23, 24};
    private static final int SLOT_MOUSE_LABEL = 9;
    /** 最上面一排中間：技能按鍵存檔（月卡）。 */
    private static final int SLOT_PRESETS = 4;
    private static final int SLOT_NUMBER_LABEL = 18;
    /** 右邊：選起來的技能、升一級、取出成寶石。 */
    private static final int SLOT_SELECTED = 15;
    private static final int SLOT_LEVEL_UP = 16;
    private static final int SLOT_TAKE_OUT = 17;
    /** 換頁（技能三頁）。 */
    private static final int SLOT_PAGE = 26;
    /** 技能上面那一排：沒套用資源包時，用綠色玻璃片指著選起來的、可以放的技能。 */
    private static final int RAIL_START = 27;
    private static final int SKILL_START = 36;
    private static final int SLOT_SOCKETS_LABEL = 46;
    private static final int SUPPORT_START = 47;
    private static final int SLOT_GUIDE = 52;
    private static final int SLOT_CLOSE = SLOT_CLOSE_BAR;
    private static final int SIZE = 54;

    private final SkillService service;
    private final GemService gems;
    private int selected = -1;
    /** 技能上面那一排現在有指示的格子（點指示＝點下面的技能）。 */
    private final boolean[] railShown = new boolean[SkillLoadout.PAGE_SIZE];
    /** 技能的第幾頁（從 0 起）。 */
    private int page;
    /** 先點了按鍵格、等著點技能（按鍵格子的編號，-1 = 沒有）。 */
    private int pendingKey = -1;
    private boolean changed;

    /** 游標上拿著什麼（決定哪些格子要亮、哪些要變暗）。 */
    private enum Holding { NONE, SKILL_GEM, SUPPORT_GEM, UNCUT_SKILL, SOCKET_ORB, OTHER }

    /** 拿著的東西放在某一格：BEST＝放這裡（亮起來）、SWAP＝可以放，但會換下原本的、NO＝不能放（變暗）。 */
    private enum Fit { BEST, SWAP, NO }

    /** 一格的放置提示：text 是說明最後一行（已經是 MiniMessage）。 */
    private record Hint(Fit fit, String text) {
        static Hint best(final String miniMessage) {
            return new Hint(Fit.BEST, "<green>" + miniMessage);
        }

        static Hint swap(final String miniMessage) {
            return new Hint(Fit.SWAP, "<yellow>" + miniMessage);
        }

        /** problem 是純文字（會跳脫）。 */
        static Hint no(final String problem) {
            return new Hint(Fit.NO, "<red>" + ItemRenderer.escape(problem));
        }
    }

    public SkillMenu(final ExileCore plugin, final Player player, final SkillService service) {
        super(plugin, player, 6, "<dark_gray>技能");
        this.service = service;
        this.gems = service.gems();
    }

    // ---- 畫面 ----

    @Override
    protected void draw() {
        for (int i = 0; i < SIZE; i++) {
            set(i, i < 9 || i >= SLOT_BACK ? bar() : filler());
        }
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout loadout = state.skills();
        if (selected >= 0 && loadout.skill(selected) == null) {
            selected = -1;
        }
        final ItemStack cursor = player.getItemOnCursor();
        final Holding holding = holding(cursor);

        set(SLOT_GUIDE, helpButton(List.of(
                "<#e0c080>綁按鍵（哪個先點都可以）",
                "點技能 → 點上面的按鍵格；或點按鍵格 → 點技能",
                "滑鼠移到技能上按數字鍵 <white>1</white><gray>：綁左鍵、<white>2</white><gray>：綁右鍵、<white>3–8</white><gray>：綁那個數字鍵",
                "潛行＋左、潛行＋右：點技能再點按鍵格；右鍵點按鍵格：改回普攻",
                "右上角換頁：技能一共三頁、27 個",
                "",
                "<#e0c080>升級",
                "選起來之後點「⬆ 升一級」：自動用背包或通貨袋裡夠用、等級最低的未切割技能寶石",
                "",
                "<#e0c080>放寶石（把寶石拖過來）",
                "在背包點一下寶石拿起來：能放的空格變綠、能放的技能亮起來，放不下的變暗；再點那一格",
                "技能寶石 → 技能格　輔助寶石 → 技能或輔助孔",
                "未切割技能寶石 → 技能（升一級）　鑿星石 → 技能（開孔）",
                "背包裡的寶石 Shift＋點一下：直接放進面板",
                "點掛著的輔助：拿下來；專屬掉落的輔助一個技能只能掛一顆",
                "",
                "光環、精魂召喚要綁在上方按鍵格才開著（常駐，不用按），保留的精魂加起來不能超過上限（照面板的順序）",
                "按鍵：左鍵、右鍵、潛行＋左、潛行＋右，還有數字鍵 3–8（1、2 是藥劑，9 是手上的武器）",
                "沒綁技能的按鍵是普攻：弓射箭、法杖與長杖射魔法彈、其他近戰")));
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_CLOSE, closeButton());
        // 2026-10 月卡福利：技能按鍵存好幾組
        set(SLOT_PRESETS, icon(Material.CLOCK, "<#bcd4ff>技能組（月卡）　<white>第 " + (plugin.players().get(player).activeSkillSet() + 1) + " 組",
                "<gray>5 組技能面板（技能、輔助、按鍵），切換時一起換；只有正在用的那一組有效果",
                plugin.sponsors() != null && plugin.sponsors().cardDaysLeft(player) > 0 ? "<green>月卡：可以使用" : "<dark_gray>要有月卡才能使用",
                "", "<yellow>左鍵：打開"));
        set(SLOT_MOUSE_LABEL, icon(Material.OAK_SIGN, "<white>滑鼠",
                "<gray>右邊 4 格：左鍵、右鍵、潛行＋左鍵、潛行＋右鍵",
                service.hold().enabled() ? "<gray>左鍵、右鍵都可以按住連續施放" : "<gray>右鍵可以按住連續施放（左鍵一下一下點）"));
        set(SLOT_NUMBER_LABEL, icon(Material.OAK_SIGN, "<white>數字鍵 3–8",
                "<gray>右邊 6 格：在可以戰鬥的地方按 3–8 放技能",
                "<gray>手上一直拿著第 9 格（武器），1、2 喝藥劑",
                "<dark_gray>（營地裡數字鍵照原版換格子）"));
        for (int bindSlot = 0; bindSlot < SkillLoadout.BIND_SLOTS; bindSlot++) {
            set(BIND[bindSlot], bindIcon(state, bindSlot, holding));
        }
        page = Math.max(0, Math.min(SkillLoadout.PAGES - 1, page));
        final int nextPage = (page + 1) % SkillLoadout.PAGES;
        final List<String> pageLore = new ArrayList<>();
        for (int p = 0; p < SkillLoadout.PAGES; p++) {
            int count = 0;
            for (int i = 0; i < SkillLoadout.PAGE_SIZE; i++) {
                if (loadout.skill(p * SkillLoadout.PAGE_SIZE + i) != null) {
                    count++;
                }
            }
            pageLore.add((p == page ? "<white>▶ " : "<gray>　 ") + "第 " + (p + 1) + " 頁：" + count + " 個技能");
        }
        pageLore.add("<gray>技能 " + loadout.size() + "／" + SkillLoadout.MAX_SKILLS);
        pageLore.add("");
        pageLore.add("<yellow>左鍵：換到第 " + (nextPage + 1) + " 頁　右鍵：上一頁");
        final Material[] pageDyes = {Material.LIME_DYE, Material.LIGHT_BLUE_DYE, Material.MAGENTA_DYE};
        set(SLOT_PAGE, icon(pageDyes[page % pageDyes.length], "<white>技能第 " + (page + 1) + "／" + SkillLoadout.PAGES + " 頁",
                pageLore.toArray(new String[0])));

        drawSelected(state, loadout);
        for (int i = 0; i < SkillLoadout.PAGE_SIZE; i++) {
            final int position = page * SkillLoadout.PAGE_SIZE + i;
            final Drawn drawn = skillIcon(state, loadout, position, holding, cursor);
            set(SKILL_START + i, drawn.icon());
            // 技能畫得出外框（套用資源包）就不用指示
            railShown[i] = drawn.rail() != null && !drawn.framed();
            if (railShown[i]) {
                set(RAIL_START + i, drawn.rail());
            }
        }
        drawSockets(loadout, holding, cursor);
    }

    /** 右邊的「選起來的技能」、⬆ 升一級、取出成寶石。 */
    private void drawSelected(final PlayerState state, final SkillLoadout loadout) {
        final SkillLoadout.Entry entry = selected >= 0 ? loadout.skill(selected) : null;
        final SkillDefinition definition = entry == null ? null : service.config().skill(entry.skillId());
        if (entry != null && definition == null) {
            // skills.yml 裡沒有了（被刪掉或改名）：只能取出成寶石
            set(SLOT_SELECTED, highlight(icon(Material.BARRIER, "<red>不認得的技能：" + ItemRenderer.escape(entry.skillId()),
                    "<gray>skills.yml 裡沒有這個技能（被刪掉或改名了）", "", "<yellow>點一下：取消選取")));
            set(SLOT_LEVEL_UP, icon(Material.GRAY_DYE, "<gray>⬆ 升一級", "<red>不認得的技能不能升級"));
            set(SLOT_TAKE_OUT, icon(Material.HEART_OF_THE_SEA, "<white>取出成技能寶石", "<gray>放進背包", "", "<yellow>左鍵：取出"));
            return;
        }
        if (entry == null) {
            set(SLOT_SELECTED, icon(Material.GRAY_DYE, pendingKey >= 0 ? "<green>點下面一個技能綁到 " + keyName(pendingKey) : "<gray>還沒選技能",
                    "<gray>點下面的技能選起來：", "<gray>看它的輔助孔、綁按鍵、升級、取出",
                    "<dark_gray>技能 " + loadout.size() + "／" + SkillLoadout.MAX_SKILLS + "（未切割技能寶石右鍵可以雕出新技能）"));
            set(SLOT_LEVEL_UP, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>⬆ 升一級", "<gray>先選一個技能"));
            set(SLOT_TAKE_OUT, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>取出成寶石", "<gray>先選一個技能"));
            return;
        }
        final List<String> lore = new ArrayList<>(service.describe(player, definition, entry));
        lore.add("");
        lore.add("<gray>輔助孔 <white>" + entry.sockets() + "</white>／" + SkillLoadout.MAX_SOCKETS + "：" + supportSummary(entry));
        final List<String> bound = boundKeys(loadout, selected);
        lore.add(bound.isEmpty() ? "<dark_gray>還沒綁在任何按鍵上" : "<gray>綁在：<white>" + String.join("、", bound));
        lore.add("");
        lore.add("<yellow>點一下：取消選取");
        set(SLOT_SELECTED, highlight(SkillArt.decorate(icon(material(definition.icon(), Material.BOOK),
                "<green>選取中：<#e0c080>" + ItemRenderer.escape(definition.name()) + " <gray>Lv." + entry.level(), lore), definition)));
        // 升一級：自動挑背包或通貨袋裡夠用、等級最低的那顆
        final String problem = gems.levelUpProblem(state, entry, SkillLoadout.GEM_CAP);
        final GemService.UncutSource source = problem == null ? gems.bestForLevelUp(player, entry) : null;
        if (problem != null) {
            set(SLOT_LEVEL_UP, icon(Material.GRAY_DYE, "<gray>⬆ 升一級", "<red>" + ItemRenderer.escape(problem)));
        } else if (source == null) {
            set(SLOT_LEVEL_UP, icon(Material.GRAY_DYE, "<gray>⬆ 升到 Lv." + (entry.level() + 1),
                    "<red>沒有夠用的未切割技能寶石", "<gray>要 Lv." + (entry.level() + 1) + " 以上的（背包或通貨袋裡）"));
        } else {
            set(SLOT_LEVEL_UP, targetHighlight(icon(Material.EXPERIENCE_BOTTLE, "<green>⬆ 升到 Lv." + (entry.level() + 1),
                    "<gray>用掉一顆：<white>" + ItemRenderer.escape(gems.describe(player, source)),
                    "<dark_gray>（自動挑夠用、等級最低的那顆）", "", "<yellow>左鍵：升一級")));
        }
        set(SLOT_TAKE_OUT, icon(Material.HEART_OF_THE_SEA, "<white>取出成技能寶石",
                "<gray>帶著等級、輔助孔與掛著的輔助寶石放進背包", "<gray>（綁的按鍵改回普攻）", "", "<yellow>左鍵：取出",
                "<dark_gray>也可以 Shift＋點技能"));
    }

    private static String keyName(final int bindSlot) {
        return SkillLoadout.keyName(bindSlot);
    }

    private Holding holding(final ItemStack cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return Holding.NONE;
        }
        final GemData gem = gems.read(cursor).orElse(null);
        if (gem != null) {
            return switch (gem.kind()) {
                case SKILL -> Holding.SKILL_GEM;
                case SUPPORT -> Holding.SUPPORT_GEM;
                case UNCUT_SKILL -> Holding.UNCUT_SKILL;
                case UNCUT_SUPPORT -> Holding.OTHER;
            };
        }
        final CurrencyType currency = plugin.currencies().read(cursor).orElse(null);
        return currency != null && currency.isSkillCurrency() ? Holding.SOCKET_ORB : Holding.OTHER;
    }

    private ItemStack bindIcon(final PlayerState state, final int bindSlot, final Holding holding) {
        final SkillService.Resolved resolved = service.resolve(state, bindSlot);
        final SkillDefinition definition = resolved.definition();
        final boolean pending = pendingKey == bindSlot;
        final List<String> lore = new ArrayList<>();
        if (resolved.isDefault()) {
            lore.add("<gray>沒有綁技能：普攻");
            lore.addAll(service.describeDefault(player, definition));
        } else {
            lore.addAll(service.describe(player, definition, resolved.entry()));
        }
        lore.add("");
        if (pending) {
            lore.add("<green>▶ 現在點下面的一個技能綁上來");
            lore.add("<gray>再點一次這格：取消");
        } else if (selected >= 0) {
            final SkillLoadout.Entry entry = state.skills().skill(selected);
            final SkillDefinition chosen = entry == null ? null : service.config().skill(entry.skillId());
            lore.add("<yellow>左鍵：把 " + ItemRenderer.escape(chosen == null ? "選好的技能" : chosen.name()) + " 綁在這裡");
        } else {
            lore.add("<yellow>左鍵：選一個技能綁上來");
        }
        if (!resolved.isDefault()) {
            lore.add("<yellow>右鍵：改回普攻");
        }
        final boolean auraOn = !resolved.isDefault() && definition.mechanic() == Mechanic.AURA && service.auraActive(player, definition.id());
        final String title = (pending ? "<green>▶ " : "") + "<white>" + keyName(bindSlot) + "："
                + (resolved.isDefault() ? "<gray>" : auraOn ? "<#8fd3ff>✦ <#e0c080>" : "<#e0c080>")
                + ItemRenderer.escape(definition.name()) + (resolved.isDefault() ? "" : " <gray>Lv." + resolved.level())
                + (auraOn ? " <#8fd3ff>開啟中" : "");
        final ItemStack stack = SkillArt.decorate(icon(material(definition.icon(), resolved.isDefault() ? Material.WOODEN_SWORD : Material.BOOK), title, lore), definition);
        if (pending) {
            // 先點的這一格，等著點技能：外框；畫不出外框（沒資源包）就換成綠色玻璃片
            return frames(stack) ? highlight(stack) : icon(Material.LIME_STAINED_GLASS_PANE, title, lore);
        }
        // 選了技能、手上沒東西：每個按鍵格都可以點（只發光，綠色底線留給選起來的那個技能）；開著的光環也一直發光
        return (selected >= 0 && holding == Holding.NONE) || auraOn ? glowing(stack) : stack;
    }

    /** 技能格畫出來的樣子、它上面那一格的指示（沒有就是 null）、技能格是不是畫了外框（畫了就不用指示）。 */
    private record Drawn(ItemStack icon, ItemStack rail, boolean framed) {
        Drawn(final ItemStack icon, final ItemStack rail) {
            this(icon, rail, false);
        }
    }

    /** 技能上面那一格的指示（綠色＝選起來的、放這裡；黃色＝可以放，但會換下原本的）。 */
    private static ItemStack rail(final Material material, final String title, final String line) {
        return icon(material, title, line);
    }

    private Drawn skillIcon(final PlayerState state, final SkillLoadout loadout, final int position, final Holding holding,
                            final ItemStack cursor) {
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            if (holding == Holding.SKILL_GEM) {
                final GemData gem = gems.read(cursor).orElse(null);
                final String problem = gem == null ? "不認得的寶石" : skillGemProblem(loadout, position, gem);
                final ItemStack empty = icon(Material.LIME_STAINED_GLASS_PANE, "<green>▶ 空的技能格", "<green>放這裡：把技能寶石放進面板");
                return problem == null
                        ? new Drawn(targetHighlight(empty), null) // 空格本身就變綠了，不用另外指
                        : new Drawn(icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>空的技能格", "<red>" + ItemRenderer.escape(problem)), null);
            }
            return new Drawn(icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>空的技能格",
                    "<gray>拿技能寶石點這裡放進來",
                    "<gray>未切割技能寶石右鍵可以雕出技能寶石"), null);
        }
        final SkillDefinition definition = service.config().skill(entry.skillId());
        if (definition == null) {
            return new Drawn(icon(Material.BARRIER, "<red>不認得的技能：" + ItemRenderer.escape(entry.skillId()),
                    "<gray>skills.yml 裡沒有這個技能（被刪掉或改名了）", "<yellow>Shift＋點一下：取出成寶石"), null);
        }
        final String name = ItemRenderer.escape(definition.name());
        final Hint hint = dropHint(state, loadout, position, entry, definition, holding, cursor);
        if (hint != null && hint.fit() == Fit.NO && holding != Holding.OTHER) {
            // 拿著的寶石／鑿星石放不進這個技能：變暗（拿著別的東西只是在整理背包，不要整片變灰）
            return new Drawn(icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>" + name + " Lv." + entry.level(),
                    hint.text(), "<dark_gray>（放下手上的東西就恢復）"), null);
        }
        final List<String> lore = new ArrayList<>(service.describe(player, definition, entry));
        lore.add("");
        lore.add("<gray>輔助孔 <white>" + entry.sockets() + "</white>／" + SkillLoadout.MAX_SOCKETS + "：" + supportSummary(entry));
        final List<String> bound = boundKeys(loadout, position);
        lore.add(bound.isEmpty() ? "<dark_gray>還沒綁在任何按鍵上" : "<gray>綁在：<white>" + String.join("、", bound));
        boolean chosen = false; // 選起來的：金色外框
        boolean target = false; // 拿著的東西放這裡最好：綠色外框
        boolean glow = false; // 只發光：可以放但會換下原本的、等著綁按鍵時每個技能都可以點
        ItemStack rail = null;
        if (hint != null) {
            lore.add(hint.text());
            target = hint.fit() == Fit.BEST;
            glow = hint.fit() == Fit.SWAP;
            if (target) {
                rail = rail(Material.LIME_STAINED_GLASS_PANE, "<green>▼ 放這裡：" + name, hint.text());
            } else if (glow) {
                rail = rail(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>▼ 可以換：" + name, hint.text());
            }
        } else if (pendingKey >= 0) {
            lore.add("<green>點一下：綁到 " + keyName(pendingKey));
            glow = true;
            rail = rail(Material.LIME_STAINED_GLASS_PANE, "<green>▼ " + name, "<green>點下面這個技能：綁到 " + keyName(pendingKey));
        } else {
            chosen = position == selected;
            lore.add(chosen ? "<green>已選取：點上面的按鍵格綁上去，最下面一排是它的輔助孔"
                    : "<yellow>點一下：選取（看輔助孔、綁按鍵、升級）");
            lore.add("<yellow>數字鍵 1：綁左鍵　2：綁右鍵　3–8：綁那個數字鍵");
            lore.add("<yellow>Shift＋點一下：取出成技能寶石");
            if (chosen) {
                rail = rail(Material.LIME_STAINED_GLASS_PANE, "<green>▼ 選取中：" + name, "<gray>最下面一排是它的輔助孔");
            }
        }
        final boolean auraOn = definition.mechanic() == Mechanic.AURA && service.auraActive(player, definition.id());
        final ItemStack stack = SkillArt.decorate(icon(material(definition.icon(), Material.BOOK),
                (chosen || target ? "<green>▶ " : "") + (auraOn ? "<#8fd3ff>✦ " : "") + "<#e0c080>" + name + " <gray>Lv." + entry.level()
                        + (auraOn ? " <#8fd3ff>開啟中" : ""), lore), definition);
        final boolean framed = (chosen || target) && frames(stack);
        // 開著的光環一直發光，一眼看得出來
        final ItemStack drawn = chosen ? highlight(stack) : target ? targetHighlight(stack) : glow || auraOn ? glowing(stack) : stack;
        return new Drawn(drawn, rail, framed);
    }

    /** 游標上拿著寶石或鑿星石時，這個技能能不能放；沒拿東西回傳 null。 */
    private Hint dropHint(final PlayerState state, final SkillLoadout loadout, final int position, final SkillLoadout.Entry entry,
                          final SkillDefinition definition, final Holding holding, final ItemStack cursor) {
        switch (holding) {
            case SKILL_GEM -> {
                final GemData gem = gems.read(cursor).orElse(null);
                final String problem = gem == null ? "不認得的寶石" : skillGemProblem(loadout, position, gem);
                if (problem != null) {
                    return Hint.no(problem);
                }
                return gem.entry() != null && gem.entry().skillId().equals(entry.skillId())
                        ? Hint.best("放這裡：換成手上這顆（等級、輔助孔照寶石，這個變回寶石）")
                        : Hint.swap("放這裡：換成手上的技能寶石（這個變回寶石）");
            }
            case SUPPORT_GEM -> {
                final GemData gem = gems.read(cursor).orElse(null);
                final SupportDefinition support = gem == null ? null : service.config().support(gem.id());
                if (support == null) {
                    return Hint.no("不認得的輔助寶石");
                }
                final String problem = supportProblem(loadout, position, entry, definition, support);
                if (problem != null) {
                    return Hint.no(problem);
                }
                // 放在技能上 = 掛到第一個空孔
                final String exclusive = SupportDefinition.exclusiveConflict(entry, -1, support, service.config()::support);
                if (exclusive != null) {
                    return Hint.no(exclusive);
                }
                return entry.freeSocket() >= 0 ? Hint.best("放這裡：掛上" + ItemRenderer.escape(support.name()))
                        : Hint.no("沒有空的輔助孔（用鑿星石開孔）");
            }
            case UNCUT_SKILL -> {
                final GemData gem = gems.read(cursor).orElse(null);
                final String problem = gem == null ? "不認得的寶石" : gems.levelUpProblem(state, entry, gem.level());
                return problem == null ? Hint.best("放這裡：升到 Lv." + (entry.level() + 1)) : Hint.no(problem);
            }
            case SOCKET_ORB -> {
                final CurrencyType orb = plugin.currencies().read(cursor).orElse(null);
                if (orb == null) {
                    return null;
                }
                if (entry.sockets() >= SkillLoadout.MAX_SOCKETS) {
                    return Hint.no("已經有 " + SkillLoadout.MAX_SOCKETS + " 個輔助孔了");
                }
                return entry.sockets() == orb.sockets() - 1 ? Hint.best("放這裡：開第 " + orb.sockets() + " 個輔助孔")
                        : Hint.no("要用" + gems.socketOrbName(entry.sockets() + 1) + "開第 " + (entry.sockets() + 1) + " 個孔");
            }
            case OTHER -> {
                return Hint.no("這個東西不能放在技能面板");
            }
            default -> {
                return null;
            }
        }
    }

    private String supportSummary(final SkillLoadout.Entry entry) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < entry.sockets(); i++) {
            final String id = entry.support(i);
            if (id.isEmpty()) {
                names.add("<dark_gray>空</dark_gray>");
            } else {
                final SupportDefinition support = service.config().support(id);
                names.add("<#8fd3ff>" + ItemRenderer.escape(support == null ? id : support.name()) + "</#8fd3ff>");
            }
        }
        return String.join("<gray>、", names);
    }

    /** 技能寶石能不能放在 target 這一格（照 GemService.place 的規則）；可以回傳 null。 */
    private String skillGemProblem(final SkillLoadout loadout, final int target, final GemData gem) {
        final SkillLoadout.Entry entry = gem.entry();
        if (entry == null) {
            return "這不是技能寶石";
        }
        final SkillDefinition definition = service.config().skill(entry.skillId());
        if (definition == null || definition.hidden()) {
            return "不認得的技能：" + entry.skillId();
        }
        final int existing = loadout.find(entry.skillId());
        if (existing >= 0 && existing != target) {
            return "面板上已經有" + definition.name() + "了（放在那一格可以換）";
        }
        final int sibling = service.config().familyConflict(loadout, entry.skillId(), target);
        if (sibling >= 0) {
            final SkillDefinition other = service.config().skill(loadout.skill(sibling).skillId());
            return "面板上已經有" + (other == null ? "同系列的技能" : other.name()) + "（異質寶石跟原本的技能只能放一個，放在那一格可以換）";
        }
        {
            for (final String supportId : entry.supportIds()) {
                final int used = service.config().oncePerCharacter(supportId) ? loadout.supportUsedBy(supportId) : -1;
                if (used >= 0 && used != target) {
                    final SupportDefinition support = service.config().support(supportId);
                    return (support == null ? supportId : support.name()) + "已經掛在別的技能上（這種輔助一個角色只能掛一顆）";
                }
            }
        }
        return null;
    }

    /** 輔助寶石能不能掛在這個技能上（不看有沒有空孔）；可以回傳 null。 */
    private String supportProblem(final SkillLoadout loadout, final int position, final SkillLoadout.Entry entry,
                                  final SkillDefinition definition, final SupportDefinition support) {
        if (definition == null) {
            return support.name() + "不能輔助這個技能（要" + support.requiresText() + "）";
        }
        final String refusal = support.refusal(definition);
        if (refusal != null) {
            return support.name() + "不能輔助這個技能（" + refusal + "）";
        }
        if (service.config().oncePerCharacter(support.id())) {
            final int used = loadout.supportUsedBy(support.id());
            if (used >= 0 && used != position) {
                return support.name() + "已經掛在別的技能上（這種輔助一個角色只能掛一顆）";
            }
        }
        if (entry.hasSupport(support.id())) {
            return "已經掛著" + support.name() + "（同一個技能不能掛兩顆一樣的）";
        }
        return null;
    }

    private void drawSockets(final SkillLoadout loadout, final Holding holding, final ItemStack cursor) {
        final SkillLoadout.Entry entry = selected >= 0 ? loadout.skill(selected) : null;
        final SkillDefinition definition = entry == null ? null : service.config().skill(entry.skillId());
        if (entry == null) {
            set(SLOT_SOCKETS_LABEL, icon(Material.GRAY_DYE, "<gray>輔助孔", "<gray>先點上面的一個技能",
                    holding == Holding.SUPPORT_GEM ? "<gray>（輔助寶石也可以直接放到亮起來的技能上）" : ""));
            for (int i = 0; i < SkillLoadout.MAX_SOCKETS; i++) {
                set(SUPPORT_START + i, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>輔助孔", "<gray>先點上面的一個技能"));
            }
            return;
        }
        set(SLOT_SOCKETS_LABEL, icon(Material.AMETHYST_CLUSTER, "<#8fd3ff>"
                        + ItemRenderer.escape(definition == null ? entry.skillId() : definition.name()) + " 的輔助孔 <gray>"
                        + entry.sockets() + "／" + SkillLoadout.MAX_SOCKETS,
                "<gray>拿輔助寶石點空的孔掛上去，點掛著的拿下來",
                service.config().supportOncePerCharacter() ? "<gray>同一種輔助一個角色只能用一次"
                        : "<gray>同一種輔助可以掛在不同技能上（各用一顆），要保留精魂的輔助一個角色只能掛一顆"));
        // 手上拿的輔助寶石（能不能掛在這個技能上）或鑿星石
        SupportDefinition held = null;
        String problem = null;
        if (holding == Holding.SUPPORT_GEM) {
            final GemData gem = gems.read(cursor).orElse(null);
            held = gem == null ? null : service.config().support(gem.id());
            problem = held == null ? "不認得的輔助寶石" : supportProblem(loadout, selected, entry, definition, held);
        }
        final CurrencyType orb = holding == Holding.SOCKET_ORB ? plugin.currencies().read(cursor).orElse(null) : null;
        for (int i = 0; i < SkillLoadout.MAX_SOCKETS; i++) {
            set(SUPPORT_START + i, socketIcon(entry, definition, i, holding, held, problem, orb));
        }
    }

    /**
     * 一個輔助孔。拿著輔助寶石時：能掛的空孔變成亮綠色、掛著別的孔可以換（黃字）、掛不上去的變暗；
     * 拿著鑿星石時：它能開的那個孔變成亮綠色。
     */
    private ItemStack socketIcon(final SkillLoadout.Entry entry, final SkillDefinition definition, final int index,
                                 final Holding holding, final SupportDefinition held, final String problem, final CurrencyType orb) {
        final boolean supportInHand = holding == Holding.SUPPORT_GEM;
        if (index >= entry.sockets()) {
            final String orbName = gems.socketOrbName(index + 1);
            final String title = "第 " + (index + 1) + " 個輔助孔（還沒開）";
            if (orb != null && index == entry.sockets() && orb.sockets() == index + 1) {
                return targetHighlight(icon(Material.LIME_STAINED_GLASS_PANE, "<green>▶ " + title,
                        "<green>放這裡：用" + ItemRenderer.escape(orb.name()) + "開這個孔"));
            }
            return icon(Material.RED_STAINED_GLASS_PANE, "<gray>" + title,
                    orb != null && index == entry.sockets() ? "<red>要用" + ItemRenderer.escape(orbName) + "開這個孔"
                            : index == entry.sockets() ? "<gray>拿" + ItemRenderer.escape(orbName) + "點這裡或點技能來開"
                            : "<gray>要先開第 " + index + " 個孔，再用" + ItemRenderer.escape(orbName) + "開這個");
        }
        final String id = entry.support(index);
        // 專屬掉落的輔助一個技能一顆：照這一孔算（換掉孔裡那顆專屬的可以）
        final String exclusive = supportInHand && problem == null
                ? SupportDefinition.exclusiveConflict(entry, index, held, service.config()::support) : null;
        final String socketProblem = problem != null ? problem : exclusive;
        if (id.isEmpty()) {
            if (supportInHand) {
                return socketProblem == null
                        ? targetHighlight(icon(Material.LIME_STAINED_GLASS_PANE, "<green>▶ 空的輔助孔",
                                "<green>放這裡：掛上" + ItemRenderer.escape(held.name())))
                        : icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>空的輔助孔", "<red>" + ItemRenderer.escape(socketProblem));
            }
            return icon(Material.WHITE_STAINED_GLASS_PANE, "<gray>空的輔助孔", "<gray>拿輔助寶石點這裡掛上去");
        }
        final SupportDefinition support = service.config().support(id);
        if (support == null) {
            return icon(Material.BARRIER, "<red>不認得的輔助：" + ItemRenderer.escape(id),
                    "<gray>supports.yml 裡沒有這個輔助", "<yellow>點一下：拿下來");
        }
        if (supportInHand && socketProblem != null) {
            return icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>" + ItemRenderer.escape(support.name()),
                    "<red>" + ItemRenderer.escape(socketProblem), "<dark_gray>（放下手上的東西就恢復）");
        }
        final List<String> lore = new ArrayList<>(GemService.effectLines(support));
        if (definition != null && !support.supports(definition)) {
            lore.add("<red>不能輔助這個技能（" + ItemRenderer.escape(support.refusal(definition)) + "，不會生效）");
        }
        if (!support.cuttable()) {
            for (int j = 0; j < index; j++) {
                final SupportDefinition before = service.config().support(entry.support(j));
                if (before != null && !before.cuttable() && (definition == null || before.supports(definition))) {
                    lore.add("<red>不會生效：一個技能只能掛一顆專屬掉落的輔助寶石（前面已經有" + ItemRenderer.escape(before.name()) + "）");
                    break;
                }
            }
        }
        lore.add("");
        lore.add(supportInHand ? "<yellow>放這裡：換成" + ItemRenderer.escape(held.name()) + "（這個拿下來）" : "<yellow>點一下：拿下來");
        final ItemStack stack = SkillArt.decorateSupport(icon(material(support.icon(), Material.GHAST_TEAR), "<#8fd3ff>" + ItemRenderer.escape(support.name()), lore), support.id());
        return supportInHand ? glowing(stack) : stack; // 可以換：只發光（外框留給空的孔）
    }

    private static List<String> boundKeys(final SkillLoadout loadout, final int position) {
        final List<String> keys = new ArrayList<>();
        for (int bindSlot = 0; bindSlot < SkillLoadout.BIND_SLOTS; bindSlot++) {
            if (loadout.bound(bindSlot) == position) {
                keys.add(keyName(bindSlot));
            }
        }
        return keys;
    }

    // ---- 點擊 ----

    @Override
    protected boolean allowsItemMovement() {
        return true; // 要能從背包拿寶石到游標上
    }

    @Override
    protected void handleRawClick(final InventoryClickEvent event) {
        final int raw = event.getRawSlot();
        if (raw >= 0 && raw < SIZE) {
            event.setCancelled(true); // 選單上的圖示拿不走
            onTop(raw, event);
            return;
        }
        if (event.getClick().isShiftClick()) {
            event.setCancelled(true); // Shift 不能把東西塞進選單：背包裡的寶石直接放進面板
            onShiftFromInventory(event);
            return;
        }
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true); // 雙擊收集會把選單上的圖示吸到游標上
            return;
        }
        // 其他（自己背包裡搬東西、拿起來放下）照原版；游標上拿的東西變了，下一個 tick 重畫（能放的地方發光）
        refreshSoon();
    }

    private void refreshSoon() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                redraw();
            }
        });
    }

    @Override
    protected void handleDrag(final InventoryDragEvent event) {
        for (final int raw : event.getRawSlots()) {
            if (raw < SIZE) {
                event.setCancelled(true);
                return;
            }
        }
        refreshSoon(); // 在自己背包裡拖著分疊：游標上剩的東西變了
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        // 用 handleRawClick 處理
    }

    private void onTop(final int raw, final InventoryClickEvent event) {
        // 技能上面那一排的指示：點它＝點下面的技能
        final int slot = raw >= RAIL_START && raw < RAIL_START + SkillLoadout.PAGE_SIZE && railShown[raw - RAIL_START]
                ? raw + (SKILL_START - RAIL_START) : raw;
        if (slot == SLOT_PAGE) {
            page = (page + (event.isRightClick() ? SkillLoadout.PAGES - 1 : 1)) % SkillLoadout.PAGES;
            click(1.1f);
            redraw();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot == SLOT_PRESETS && (event.getCursor() == null || event.getCursor().isEmpty())) {
            if (changed) {
                plugin.players().markDirty(player);
                plugin.characters().save(player);
                changed = false;
            }
            new tw.exilecore.sponsor.SkillSetMenu(plugin, player, () -> new SkillMenu(plugin, player, service).open()).open();
            return;
        }
        final ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.isEmpty()) {
            onCursorClick(slot, cursor, event);
            return;
        }
        final ClickType click = event.getClick();
        final SkillLoadout loadout = plugin.players().get(player).skills();
        if (slot == SLOT_SELECTED) {
            if (selected >= 0 || pendingKey >= 0) {
                selected = -1;
                pendingKey = -1;
                click(0.9f);
                redraw();
            }
            return;
        }
        if (slot == SLOT_LEVEL_UP) {
            levelUpSelected(loadout, click);
            return;
        }
        if (slot == SLOT_TAKE_OUT) {
            if (selected >= 0 && loadout.skill(selected) != null && acceptAction(click)) {
                takeOut(selected);
            }
            return;
        }
        if (slot >= SKILL_START && slot < SKILL_START + SkillLoadout.PAGE_SIZE) {
            final int position = page * SkillLoadout.PAGE_SIZE + slot - SKILL_START;
            if (loadout.skill(position) == null) {
                pendingKey = -1;
                return;
            }
            if (click == ClickType.NUMBER_KEY) {
                // 滑鼠移到技能上按 1：左鍵、2：右鍵、3–8：那個數字鍵（9 沒有）
                final int button = event.getHotbarButton();
                pendingKey = -1;
                final int bindSlot = button == 0 ? SkillLoadout.KEY_LEFT : button == 1 ? SkillLoadout.KEY_RIGHT : SkillLoadout.hotbarBind(button + 1);
                if (bindSlot >= 0) {
                    bind(loadout, bindSlot, position);
                } else {
                    redraw();
                }
                return;
            }
            if (click.isShiftClick()) {
                takeOut(position);
                return;
            }
            if (pendingKey >= 0) {
                final int key = pendingKey;
                pendingKey = -1;
                selected = position;
                bind(loadout, key, position);
                return;
            }
            selected = selected == position ? -1 : position;
            click(1.4f);
            redraw();
            return;
        }
        if (slot >= SUPPORT_START && slot < SUPPORT_START + SkillLoadout.MAX_SOCKETS) {
            if (selected >= 0) {
                final GemService.Change change = gems.unsocket(player, selected, slot - SUPPORT_START);
                if (!change.failed()) {
                    event.getView().setCursor(change.returned());
                    changed();
                }
            }
            return;
        }
        for (int bindSlot = 0; bindSlot < SkillLoadout.BIND_SLOTS; bindSlot++) {
            if (slot != BIND[bindSlot]) {
                continue;
            }
            if (click == ClickType.NUMBER_KEY) {
                return;
            }
            if (click.isRightClick()) {
                pendingKey = -1;
                if (loadout.bound(bindSlot) >= 0) {
                    loadout.bind(bindSlot, -1);
                    changed = true;
                    click(0.8f);
                }
                redraw();
            } else if (selected >= 0) {
                bind(loadout, bindSlot, selected);
            } else {
                // 先點按鍵格：等著點一個技能（再點一次取消）
                pendingKey = pendingKey == bindSlot ? -1 : bindSlot;
                click(pendingKey >= 0 ? 1.3f : 0.9f);
                redraw();
            }
            return;
        }
    }

    /** 游標上拿著東西點選單。 */
    private void onCursorClick(final int slot, final ItemStack cursor, final InventoryClickEvent event) {
        final GemData gem = gems.read(cursor).orElse(null);
        final CurrencyType currency = plugin.currencies().read(cursor).orElse(null);
        final boolean skillSlot = slot >= SKILL_START && slot < SKILL_START + SkillLoadout.PAGE_SIZE;
        final boolean socketSlot = slot >= SUPPORT_START && slot < SUPPORT_START + SkillLoadout.MAX_SOCKETS;
        final int position = skillSlot ? page * SkillLoadout.PAGE_SIZE + slot - SKILL_START : selected;
        if (!skillSlot && !socketSlot) {
            fail("拿著東西只能點技能或輔助孔");
            return;
        }
        if (currency != null && currency.isSkillCurrency()) {
            apply(event, cursor, position < 0 ? GemService.Change.fail("先點一個技能") : gems.addSocket(player, position, currency));
            return;
        }
        if (gem == null) {
            fail("這裡只能放寶石與鑿星石");
            return;
        }
        final GemService.Change change = switch (gem.kind()) {
            case SKILL -> skillSlot ? gems.place(player, position, gem) : GemService.Change.fail("技能寶石要點技能格");
            case SUPPORT -> position < 0 ? GemService.Change.fail("先點一個技能")
                    : gems.socket(player, position, socketSlot ? slot - SUPPORT_START : -1, gem.id());
            case UNCUT_SKILL -> position < 0 || !skillSlot ? GemService.Change.fail("拿未切割技能寶石點技能來升級")
                    : levelUp(position, gem);
            case UNCUT_SUPPORT -> GemService.Change.fail("未切割輔助寶石要先右鍵切成輔助寶石");
        };
        if (!change.failed() && position >= 0) {
            selected = position;
        }
        apply(event, cursor, change);
    }

    private GemService.Change levelUp(final int position, final GemData uncut) {
        final String error = gems.levelUp(player, position, uncut.level());
        if (error == null) {
            gems.used(uncut); // 游標上的那顆接著由 apply 扣掉：記進經濟流水帳
        }
        return error == null ? GemService.Change.ok(null) : GemService.Change.fail(error);
    }

    /** 成功：游標上的東西用掉一個，換下來的東西放回游標（游標上還有剩就放進背包）。 */
    private void apply(final InventoryClickEvent event, final ItemStack cursor, final GemService.Change change) {
        if (change.failed()) {
            fail(change.error());
            return;
        }
        final int left = cursor.getAmount() - 1;
        ItemStack newCursor = left > 0 ? cursor.asQuantity(left) : null;
        if (change.returned() != null) {
            if (newCursor == null) {
                newCursor = change.returned();
            } else {
                gems.give(player, change.returned());
            }
        }
        event.getView().setCursor(newCursor == null ? ItemStack.empty() : newCursor);
        changed();
    }

    /** Shift＋點背包裡的寶石：技能寶石放進第一個空格，輔助寶石掛到選起來的技能。 */
    private void onShiftFromInventory(final InventoryClickEvent event) {
        final ItemStack item = event.getCurrentItem();
        if (item == null || item.isEmpty()) {
            return;
        }
        final GemData gem = gems.read(item).orElse(null);
        final GemService.Change change;
        if (gem != null && gem.kind() == GemData.Kind.SKILL) {
            change = gems.place(player, -1, gem);
        } else if (gem != null && gem.kind() == GemData.Kind.SUPPORT) {
            change = selected < 0 ? GemService.Change.fail("先點一個技能，再 Shift＋點輔助寶石")
                    : gems.socket(player, selected, -1, gem.id());
        } else {
            fail("Shift＋點背包裡的技能寶石或輔助寶石可以直接放進面板");
            return;
        }
        if (change.failed()) {
            fail(change.error());
            return;
        }
        event.setCurrentItem(item.getAmount() > 1 ? item.asQuantity(item.getAmount() - 1) : null);
        if (change.returned() != null) {
            gems.give(player, change.returned());
        }
        changed();
    }

    /** ⬆ 升一級：用背包或通貨袋裡夠用、等級最低的未切割技能寶石。 */
    private void levelUpSelected(final SkillLoadout loadout, final ClickType click) {
        final SkillLoadout.Entry entry = selected >= 0 ? loadout.skill(selected) : null;
        if (entry == null || service.config().skill(entry.skillId()) == null || !acceptAction(click)) {
            return; // 沒選、或技能已經不在 skills.yml 裡：不升（不要用掉寶石）
        }
        final String problem = gems.levelUpProblem(plugin.players().get(player), entry, SkillLoadout.GEM_CAP);
        if (problem != null) {
            fail(problem);
            return;
        }
        final GemService.UncutSource source = gems.bestForLevelUp(player, entry);
        if (source == null) {
            fail("沒有夠用的未切割技能寶石（要 Lv." + (entry.level() + 1) + " 以上，背包或通貨袋裡）");
            return;
        }
        final String error = gems.levelUpFrom(player, source, selected); // 扣寶石、存檔、音效都在裡面
        if (error != null) {
            fail(error);
            return;
        }
        changed = true;
        redraw();
    }

    /** 把技能綁到按鍵格子（A 組 0–3、B 組 4–7）。 */
    private void bind(final SkillLoadout loadout, final int bindSlot, final int position) {
        if (loadout.bind(bindSlot, position)) {
            changed = true;
            click(1.2f);
            final SkillLoadout.Entry entry = loadout.skill(position);
            final SkillDefinition definition = entry == null ? null : service.config().skill(entry.skillId());
            notice("<green>" + ItemRenderer.escape(definition == null ? "技能" : definition.name()) + " → " + keyName(bindSlot));
        }
        redraw();
    }

    private void takeOut(final int position) {
        if (player.getInventory().firstEmpty() < 0) {
            fail("背包滿了，空出一格再取出");
            return;
        }
        final GemService.Change change = gems.takeOut(player, position);
        if (change.failed()) {
            fail(change.error());
            return;
        }
        gems.give(player, change.returned());
        if (selected == position) {
            selected = -1;
        }
        changed();
    }

    private void changed() {
        changed = true;
        GemService.sound(player, true);
        redraw();
    }

    private void fail(final String message) {
        notice("<red>" + ItemRenderer.escape(message));
        GemService.sound(player, false);
    }

    /** 寫在動作列的提示區（直接 sendActionBar 會被生命、魔力的顯示蓋掉）。 */
    private void notice(final String miniMessage) {
        final PlayerState state = plugin.players().get(player);
        state.notice(miniMessage, plugin.getServer().getCurrentTick() + 50);
        plugin.players().display(player, state);
    }

    private void click(final float pitch) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.5f, pitch);
    }

    @Override
    protected void onClose() {
        if (changed) {
            plugin.players().markDirty(player);
            plugin.characters().save(player);
        }
    }
}
