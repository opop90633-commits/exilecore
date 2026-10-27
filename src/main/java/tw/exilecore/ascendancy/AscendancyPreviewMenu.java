package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 昇華預覽（/ascend preview）：還沒選、甚至還沒解鎖之前，就能看每個昇華職業的完整天賦樹。只能看、不能配點。
 * <p>
 * 2026-10 改版（以前要用箭頭一個一個翻、連線跟背景分不出來）：
 * <ul>
 *   <li>最上面一排是分頁：這個職業的每個昇華職業都排出來，點一下切換（看全部職業時一次 7 個，兩邊 ◀ ▶ 換一批）。</li>
 *   <li>中間四排是天賦樹（{@link AscendancyLayout}；2026-10-26 起照 PoE2 會分岔，樹太高時 ▲ ▼ 捲動）。</li>
 *   <li>下面一排：返回、只看我的職業／全部職業、昇華為這條（或打開我的昇華天賦）、怎麼看、關閉。</li>
 * </ul>
 */
final class AscendancyPreviewMenu extends Menu {

    private static final int SLOT_BACK = 45;
    private static final int SLOT_SCOPE = 47;
    private static final int SLOT_ACTION = 49;
    private static final int SLOT_GUIDE = 51;
    private static final int SLOT_CLOSE = 53;
    /** 最上面一排的分頁：昇華職業太多（看全部職業）時一次放 7 個，兩邊 ◀ ▶ 換一批。 */
    private static final int TABS_AT_ONCE = 7;
    private static final int SLOT_TABS_PREVIOUS = 0;
    private static final int SLOT_TABS_NEXT = 8;

    private final AscendancyService service;
    /** true = 看全部職業的昇華；false = 只看自己職業的選項。 */
    private boolean allClasses;
    private String currentId;
    /** 建立角色時的預覽（2026-10）：看這個職業的昇華（還沒有角色）；null = 照玩家現在的角色。 */
    private final String previewClass;
    /** 建立角色時的預覽：「返回」回到哪裡（選職業的畫面）。 */
    private final Runnable back;

    /**
     * @param startId    一打開先看哪一個（null = 自己的昇華職業，沒有就第一個）
     * @param allClasses 一開始就看全部職業
     */
    AscendancyPreviewMenu(final ExileCore plugin, final Player player, final AscendancyService service, final String startId,
                          final boolean allClasses) {
        super(plugin, player, 6, "<dark_gray>昇華預覽");
        this.service = service;
        this.allClasses = allClasses;
        this.currentId = startId;
        this.previewClass = null;
        this.back = null;
    }

    /**
     * 建立角色時的預覽（2026-10）：還沒有角色，看某個職業能走的昇華（也可以切到全部職業）。
     *
     * @param classId 先看哪個職業的
     * @param back    「返回」回到哪裡
     */
    AscendancyPreviewMenu(final ExileCore plugin, final Player player, final AscendancyService service, final String classId, final Runnable back) {
        super(plugin, player, 6, "<dark_gray>昇華預覽：" + AscendancyService.className(classId));
        this.service = service;
        this.allClasses = false;
        this.currentId = null;
        this.previewClass = classId;
        this.back = back;
    }

    /** 這個畫面看的昇華狀態：建立角色時是「這個職業、什麼都還沒做」，不然是玩家現在的角色。 */
    private Ascension ascension() {
        return previewClass != null ? new Ascension(service.config(), java.util.Map.of(), previewClass, 1) : service.ascension(player);
    }

    /** 這一頁可以翻的昇華職業：自己職業的選項（沒有選項的職業就看全部）。 */
    private List<AscendancyConfig.Ascendancy> list() {
        final List<AscendancyConfig.Ascendancy> mine = ascension().options();
        if (allClasses || mine.isEmpty()) {
            return new ArrayList<>(service.config().ascendancies());
        }
        return mine;
    }

    /** 自己的職業有沒有昇華職業（沒有的話「只看我的職業」沒有意義）。 */
    private boolean hasOwnOptions() {
        return !ascension().options().isEmpty();
    }

    private int index(final List<AscendancyConfig.Ascendancy> list) {
        if (currentId == null) {
            final AscendancyConfig.Ascendancy chosen = ascension().chosen();
            currentId = chosen != null ? chosen.id() : list.isEmpty() ? null : list.getFirst().id();
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(currentId)) {
                return i;
            }
        }
        return 0;
    }

    /** 2026-10-26：樹從第幾排開始看（▲ ▼ 捲動；換分頁時回到最上面）。 */
    private int offset;
    /** 看全部職業時，分頁目前從第幾個開始放。 */
    private int tabStart = -1;
    /** 分頁的格子 → 昇華職業的代號。 */
    private final java.util.Map<Integer, String> tabIds = new java.util.HashMap<>();
    /** 2026-10-27：畫面上的天賦（Shift＋點擊看賦予的技能）。 */
    private final java.util.Map<Integer, AscendancyConfig.Node> shownNodes = new java.util.HashMap<>();

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        tabIds.clear();
        set(SLOT_BACK, previewClass != null ? icon(Material.ARROW, "<white>← 回去選職業", "<yellow>左鍵：返回") : backButton("昇華與試煉"));
        if (previewClass == null) {
            set(SLOT_CLOSE, closeButton());
        }
        final List<AscendancyConfig.Ascendancy> list = list();
        if (list.isEmpty()) {
            set(22, icon(Material.GRAY_DYE, "<gray>還沒有任何昇華職業", "<dark_gray>ascendancy.yml 裡沒有設定"));
            return;
        }
        final int index = index(list);
        final AscendancyConfig.Ascendancy current = list.get(index);
        currentId = current.id();
        final Ascension ascension = ascension();
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        final boolean mine = chosen != null && chosen.id().equals(current.id());

        drawTabs(list, index, ascension);

        // 天賦樹（只能看）：2026-10-26 照 PoE2 畫成往右長的樹（會分岔，樹太高的時候 ▲ ▼ 捲動）；這次之前每條分支一排
        offset = AscendancyLayout.clampOffset(offset, current.rows());
        final AscendancyLayout.Page page = AscendancyLayout.page(current, offset, node -> node(current, node, mine && ascension.isAllocated(node)),
                node -> mine && ascension.isAllocated(node));
        shownNodes.clear();
        shownNodes.putAll(page.nodes());
        for (final java.util.Map.Entry<Integer, ItemStack> entry : page.items().entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
        final ItemStack up = AscendancyLayout.scroll(true, offset, page.rows());
        final ItemStack down = AscendancyLayout.scroll(false, offset, page.rows());
        if (up != null) {
            set(AscendancyLayout.SLOT_UP, up);
        }
        if (down != null) {
            set(AscendancyLayout.SLOT_DOWN, down);
        }

        // 下面一排
        if (hasOwnOptions()) {
            set(SLOT_SCOPE, allClasses
                    ? icon(Material.COMPASS, "<white>只看我的職業", "<gray>現在：全部職業的昇華（" + list.size() + " 個）", "",
                    "<yellow>左鍵：只看你的職業能選的")
                    : icon(Material.COMPASS, "<white>看全部職業的昇華", "<gray>現在：只看你的職業能選的（" + list.size() + " 個）",
                    "<dark_gray>想練分身、想知道別人的昇華在做什麼", "", "<yellow>左鍵：看全部"));
        }
        set(SLOT_ACTION, action(current, ascension));
        set(SLOT_GUIDE, helpButton(List.of(
                "最上面一排：每個昇華職業一個分頁，點一下切換",
                "中間是天賦樹：最左邊一欄直接接在起點，往右一個接一個（鏈子連著的要先配左邊的）",
                "分岔的往下一排；樹太高的時候用下面的 ▲ ▼ 捲動",
                "「選一個」的天賦配了之後，從右邊的選項挑一個（不再花點數）",
                "滑到每個天賦上看效果；寫著（尚未生效）的是還沒做出來的機制",
                "昇華點最多 " + service.config().totalPoints() + " 點：完成試煉拿點數，不可能全部配滿，要挑")));
    }

    /** 最上面一排的分頁：放得下就全部排出來（置中），放不下一次 7 個、兩邊 ◀ ▶。 */
    private void drawTabs(final List<AscendancyConfig.Ascendancy> list, final int index, final Ascension ascension) {
        final int count = list.size();
        if (count <= 9) {
            final int[] slots = tabSlots(count);
            for (int i = 0; i < count; i++) {
                setTab(slots[i], list.get(i), i == index, ascension);
            }
            return;
        }
        if (tabStart < 0 || index < tabStart || index >= tabStart + TABS_AT_ONCE) {
            tabStart = Math.max(0, Math.min(count - TABS_AT_ONCE, index - TABS_AT_ONCE / 2));
        }
        for (int i = 0; i < TABS_AT_ONCE && tabStart + i < count; i++) {
            setTab(1 + i, list.get(tabStart + i), tabStart + i == index, ascension);
        }
        if (tabStart > 0) {
            set(SLOT_TABS_PREVIOUS, icon(Material.SPECTRAL_ARROW, "<white>◀ 前面還有 " + tabStart + " 個", "<yellow>左鍵：往前看"));
        }
        final int after = count - tabStart - TABS_AT_ONCE;
        if (after > 0) {
            set(SLOT_TABS_NEXT, icon(Material.SPECTRAL_ARROW, "<white>後面還有 " + after + " 個 ▶", "<yellow>左鍵：往後看"));
        }
    }

    private void setTab(final int slot, final AscendancyConfig.Ascendancy ascendancy, final boolean current, final Ascension ascension) {
        final List<String> about = current ? header(ascendancy, ascension)
                : List.of("<gray>" + AscendancyService.classColor(ascendancy.classId()) + AscendancyService.className(ascendancy.classId())
                        + (ascendancy.summary().isBlank() ? "" : "<dark_gray>　" + AscendancyService.escape(ascendancy.summary())));
        set(slot, tab(Menu.material(ascendancy.icon(), Material.NETHER_STAR), ascendancy.colored(), current, about));
        tabIds.put(slot, ascendancy.id());
    }

    /** 標題的說明：職業、簡介、這條跟你有什麼關係、總共幾個天賦。 */
    private List<String> header(final AscendancyConfig.Ascendancy current, final Ascension ascension) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>職業：" + AscendancyService.classColor(current.classId()) + AscendancyService.className(current.classId()));
        if (!current.summary().isBlank()) {
            lore.add("<gray><i>" + AscendancyService.escape(current.summary()));
        }
        lore.add("");
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        final boolean ownClass = ascension.options().stream().anyMatch(option -> option.id().equals(current.id()));
        if (chosen != null && chosen.id().equals(current.id())) {
            lore.add("<green>這是你的昇華職業<gray>：已配 <white>" + ascension.spent() + "</white> 點，可配 <white>" + ascension.available() + "</white> 點");
        } else if (!ownClass) {
            lore.add("<dark_gray>別的職業的昇華（只能看）");
        } else if (chosen != null) {
            lore.add("<dark_gray>你已經昇華為" + chosen.colored() + "<dark_gray>：這條只能看");
        } else if (ascension.canChoose()) {
            lore.add("<#f5b942>你可以選這條：按下面的「昇華為" + AscendancyService.escape(current.name()) + "」");
        } else if (previewClass != null) {
            lore.add("<gray>選這個職業之後，完成第一試煉就能選（選一條，選了不能換）");
        } else {
            lore.add("<gray>完成第一試煉之後才能選（選一條，選了不能換）");
        }
        int count = 0;
        int cost = 0;
        for (final AscendancyConfig.Node node : current.nodes()) {
            cost += node.cost();
            count += node.option() ? 0 : 1;
        }
        lore.add("<dark_gray>全部 " + count + " 個天賦、" + cost + " 點；昇華點最多 " + service.config().totalPoints() + " 點");
        lore.add("<dark_gray>下面是天賦樹：從最左邊往右配，分岔的往下一排");
        return lore;
    }

    private ItemStack node(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node, final boolean owned) {
        final List<String> lore = AscendancyLayout.describe(ascendancy, node, true, service.skillLookup());
        if (owned) {
            lore.add("<green>✔ 你已經配了");
        }
        final ItemStack icon = icon(Menu.material(node.icon(), Material.AMETHYST_SHARD),
                (owned ? "<green>✔ " : "") + "<" + ascendancy.color() + ">" + (node.notable() ? "<b>" : "") + AscendancyService.escape(node.name()), lore);
        return owned ? glowing(icon) : icon;
    }

    /** 中間的按鈕：可以選就選這條；是自己的就打開昇華天賦；不然說明怎麼解鎖。 */
    private ItemStack action(final AscendancyConfig.Ascendancy current, final Ascension ascension) {
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        if (chosen != null && chosen.id().equals(current.id())) {
            return glowing(icon(Material.NETHER_STAR, "<white>打開我的昇華天賦", "<gray>可配 <white>" + ascension.available() + "</white> 點",
                    "", "<yellow>左鍵：配點（/ascend tree）"));
        }
        final boolean ownClass = ascension.options().stream().anyMatch(option -> option.id().equals(current.id()));
        if (chosen == null && ownClass && ascension.canChoose()) {
            return glowing(icon(Material.NETHER_STAR, "<#f5b942>昇華為" + current.colored(),
                    "<red>選了就不能換（這個角色）", "", "<yellow>左鍵：選這條（會先問一次）"));
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>預覽只能看，不能配點");
        if (!ownClass) {
            lore.add("<dark_gray>這是" + AscendancyService.className(current.classId()) + "的昇華：要" + (previewClass != null ? "選" : "用") + "那個職業的角色才能選");
        } else if (chosen != null) {
            lore.add("<dark_gray>你已經昇華為" + chosen.colored());
        } else {
            lore.add("<gray>完成第一試煉之後就能選（/ascend 開試煉）");
        }
        return icon(Material.WRITABLE_BOOK, "<white>預覽", lore);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (click == ClickType.DOUBLE_CLICK) {
            return;
        }
        final List<AscendancyConfig.Ascendancy> list = list();
        switch (slot) {
            case SLOT_BACK -> {
                if (back != null) {
                    back.run();
                } else {
                    service.open(player);
                }
            }
            case SLOT_CLOSE -> {
                if (back == null) {
                    goBack(); // 建立角色時這一格是空的（返回在左下角）
                }
            }
            case SLOT_TABS_PREVIOUS, SLOT_TABS_NEXT -> {
                if (tabIds.containsKey(slot)) {
                    switchTo(tabIds.get(slot));
                } else if (list.size() > 9) {
                    // 換一批分頁（不換正在看的）
                    tabStart = Math.max(0, Math.min(list.size() - TABS_AT_ONCE,
                            tabStart + (slot == SLOT_TABS_NEXT ? TABS_AT_ONCE : -TABS_AT_ONCE)));
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
                    redraw();
                }
            }
            case SLOT_SCOPE -> {
                if (!hasOwnOptions()) {
                    return;
                }
                allClasses = !allClasses;
                tabStart = -1;
                final List<AscendancyConfig.Ascendancy> next = list();
                if (next.stream().noneMatch(ascendancy -> ascendancy.id().equals(currentId))) {
                    currentId = next.isEmpty() ? null : next.getFirst().id();
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                redraw();
            }
            case AscendancyLayout.SLOT_UP, AscendancyLayout.SLOT_DOWN -> {
                offset += slot == AscendancyLayout.SLOT_UP ? -AscendancyLayout.STEP : AscendancyLayout.STEP;
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
                redraw();
            }
            case SLOT_ACTION -> {
                if (!acceptAction(click) || list.isEmpty()) {
                    return;
                }
                act(list.get(index(list)));
            }
            default -> {
                if (tabIds.containsKey(slot)) {
                    switchTo(tabIds.get(slot));
                    return;
                }
                // 天賦只能看；2026-10-27 照站長：Shift＋點擊看賦予的技能
                final AscendancyConfig.Node node = shownNodes.get(slot);
                if (node != null && click.isShiftClick() && acceptAction(click)) {
                    service.openSkillPreview(player, node, this::open); // 回來還是同一個畫面（同一個分頁、捲動位置）
                }
            }
        }
    }

    /** 點分頁：換看這個昇華職業。 */
    private void switchTo(final String id) {
        if (id == null || id.equals(currentId)) {
            return;
        }
        currentId = id;
        offset = 0;
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
        redraw();
    }

    private void act(final AscendancyConfig.Ascendancy current) {
        if (previewClass != null) {
            return; // 建立角色時只能看
        }
        final Ascension ascension = ascension();
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        if (chosen != null && chosen.id().equals(current.id())) {
            new AscendancyTreeMenu(plugin, player, service).open();
            return;
        }
        if (chosen != null || !ascension.canChoose() || ascension.options().stream().noneMatch(option -> option.id().equals(current.id()))) {
            return; // 只能看
        }
        final String problem = ascension.chooseProblem(current);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + AscendancyService.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        final String id = current.id();
        plugin.dialogs().confirm(player, "<#f5b942>昇華為" + current.colored() + "？",
                List.of("<gray>" + AscendancyService.escape(current.summary()),
                        "",
                        "<red>選了就不能換：這個角色之後只能配" + AscendancyService.escape(current.name()) + "的昇華天賦"),
                "昇華", () -> {
                    final String failed = service.choose(player, id);
                    if (failed != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + AscendancyService.escape(failed)));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                        new AscendancyPreviewMenu(plugin, player, service, id, allClasses).open();
                        return;
                    }
                    new AscendancyTreeMenu(plugin, player, service).open();
                }, () -> new AscendancyPreviewMenu(plugin, player, service, id, allClasses).open());
    }
}
