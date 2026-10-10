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
 * 昇華預覽（/ascend preview）：還沒選、甚至還沒解鎖之前，就能看每個昇華職業的完整天賦樹。
 * 排法跟昇華天賦一樣（每條分支一列，從左邊的徽記往右一個接一個），只能看、不能配點。
 * <p>
 * 下面一排：左右箭頭換上一個／下一個；「只看我的職業／全部職業」切換；中間的按鈕在可以昇華時直接選這條（會先問一次），
 * 看的是自己的昇華職業時打開昇華天賦。
 */
final class AscendancyPreviewMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 46;
    private static final int SLOT_SCOPE = 48;
    private static final int SLOT_ACTION = 49;
    private static final int SLOT_LIST = 50;
    private static final int SLOT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;
    /** 分支裡第幾個天賦放在第幾欄（中間隔一格連線；跟昇華天賦一樣）。 */
    private static final int[] NODE_COLUMNS = {2, 4, 6, 8};

    private final AscendancyService service;
    /** true = 看全部職業的昇華；false = 只看自己職業的選項。 */
    private boolean allClasses;
    private String currentId;

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
    }

    /** 這一頁可以翻的昇華職業：自己職業的選項（沒有選項的職業就看全部）。 */
    private List<AscendancyConfig.Ascendancy> list() {
        final List<AscendancyConfig.Ascendancy> mine = service.ascension(player).options();
        if (allClasses || mine.isEmpty()) {
            return new ArrayList<>(service.config().ascendancies());
        }
        return mine;
    }

    /** 自己的職業有沒有昇華職業（沒有的話「只看我的職業」沒有意義）。 */
    private boolean hasOwnOptions() {
        return !service.ascension(player).options().isEmpty();
    }

    private int index(final List<AscendancyConfig.Ascendancy> list) {
        if (currentId == null) {
            final AscendancyConfig.Ascendancy chosen = service.ascension(player).chosen();
            currentId = chosen != null ? chosen.id() : list.isEmpty() ? null : list.getFirst().id();
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(currentId)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 昇華與試煉"));
        set(SLOT_CLOSE, closeButton());
        final List<AscendancyConfig.Ascendancy> list = list();
        if (list.isEmpty()) {
            set(22, icon(Material.GRAY_DYE, "<gray>還沒有任何昇華職業", "<dark_gray>ascendancy.yml 裡沒有設定"));
            return;
        }
        final int index = index(list);
        final AscendancyConfig.Ascendancy current = list.get(index);
        currentId = current.id();
        final Ascension ascension = service.ascension(player);
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        final boolean mine = chosen != null && chosen.id().equals(current.id());

        set(SLOT_HEADER, glowing(icon(Menu.material(current.icon(), Material.NETHER_STAR), current.colored() + " <dark_gray>（預覽）",
                header(current, ascension))));

        // 天賦樹（只能看）
        final List<List<AscendancyConfig.Node>> branches = current.branches();
        final int emblemRow = 1 + (Math.min(branches.size(), AscendancyConfig.MAX_BRANCHES) - 1) / 2;
        set(emblemRow * 9, glowing(icon(Menu.material(current.icon(), Material.NETHER_STAR), current.colored(), "<dark_gray>分支從這裡往右長")));
        for (int b = 0; b < branches.size() && b < AscendancyConfig.MAX_BRANCHES; b++) {
            final int row = 1 + b;
            final List<AscendancyConfig.Node> branch = branches.get(b);
            set(row * 9 + 1, link(mine && !branch.isEmpty() && ascension.isAllocated(branch.getFirst())));
            for (int i = 0; i < branch.size() && i < NODE_COLUMNS.length; i++) {
                final AscendancyConfig.Node node = branch.get(i);
                final int slot = row * 9 + NODE_COLUMNS[i];
                set(slot, node(current, node, i == 0, mine && ascension.isAllocated(node)));
                if (i + 1 < branch.size() && i + 1 < NODE_COLUMNS.length) {
                    set(slot + 1, link(mine && ascension.isAllocated(branch.get(i + 1))));
                }
            }
        }

        // 下面一排
        if (list.size() > 1) {
            final AscendancyConfig.Ascendancy previous = list.get((index - 1 + list.size()) % list.size());
            final AscendancyConfig.Ascendancy next = list.get((index + 1) % list.size());
            set(SLOT_PREVIOUS, icon(Material.SPECTRAL_ARROW, "<white>← 上一個", previous.colored() + " <dark_gray>（"
                    + AscendancyService.className(previous.classId()) + "）"));
            set(SLOT_NEXT, icon(Material.SPECTRAL_ARROW, "<white>下一個 →", next.colored() + " <dark_gray>（"
                    + AscendancyService.className(next.classId()) + "）"));
        }
        if (hasOwnOptions()) {
            set(SLOT_SCOPE, allClasses
                    ? icon(Material.COMPASS, "<white>只看我的職業", "<gray>現在：全部職業的昇華（" + list.size() + " 個）", "",
                    "<yellow>左鍵：只看你的職業能選的")
                    : icon(Material.COMPASS, "<white>看全部職業的昇華", "<gray>現在：只看你的職業能選的（" + list.size() + " 個）",
                    "<dark_gray>想練分身、想知道別人的昇華在做什麼", "", "<yellow>左鍵：看全部"));
        }
        set(SLOT_LIST, overview(list, index));
        set(SLOT_ACTION, action(current, ascension));
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
        } else {
            lore.add("<gray>完成第一試煉之後才能選（選一條，選了不能換）");
        }
        int cost = 0;
        for (final AscendancyConfig.Node node : current.nodes()) {
            cost += node.cost();
        }
        lore.add("<dark_gray>全部 " + current.nodes().size() + " 個天賦、" + cost + " 點；昇華點最多 " + service.config().totalPoints() + " 點");
        lore.add("<dark_gray>每條分支要從第一個配起；滑到每個天賦上看效果");
        return lore;
    }

    private static ItemStack link(final boolean lit) {
        return icon(lit ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, lit ? "<green>—" : "<dark_gray>—");
    }

    private ItemStack node(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node, final boolean first, final boolean owned) {
        final List<String> lore = new ArrayList<>();
        for (final String line : StatLines.describeAll(node.stats())) {
            lore.add("<white>" + AscendancyService.escape(line));
        }
        if (node.note() != null) {
            lore.add("<dark_gray><i>" + AscendancyService.escape(node.note()));
        }
        lore.add("");
        lore.add("<gray>" + node.cost() + " 點<dark_gray>　" + (first ? "分支的第一個" : "接在前一個後面"));
        if (owned) {
            lore.add("<green>✔ 你已經配了");
        }
        final ItemStack icon = icon(Menu.material(node.icon(), Material.AMETHYST_SHARD),
                (owned ? "<green>✔ " : "") + "<" + ascendancy.color() + ">" + AscendancyService.escape(node.name()), lore);
        return owned ? glowing(icon) : icon;
    }

    /** 這一頁可以翻的清單（目前的打 ▶）。 */
    private ItemStack overview(final List<AscendancyConfig.Ascendancy> list, final int index) {
        final List<String> lore = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final AscendancyConfig.Ascendancy ascendancy = list.get(i);
            lore.add((i == index ? "<green>▶ " : "<dark_gray>　 ") + ascendancy.colored() + " <dark_gray>（"
                    + AscendancyService.className(ascendancy.classId()) + "）");
        }
        lore.add("");
        lore.add("<dark_gray>用左右箭頭翻");
        return icon(Material.BOOK, "<white>昇華職業 " + (index + 1) + "/" + list.size(), lore);
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
            lore.add("<dark_gray>這是" + AscendancyService.className(current.classId()) + "的昇華：要用那個職業的角色才能選");
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
            case SLOT_BACK -> service.open(player);
            case SLOT_CLOSE -> goBack();
            case SLOT_PREVIOUS, SLOT_NEXT -> {
                if (list.size() <= 1) {
                    return;
                }
                final int index = index(list);
                final int step = slot == SLOT_NEXT ? 1 : -1;
                currentId = list.get((index + step + list.size()) % list.size()).id();
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
                redraw();
            }
            case SLOT_SCOPE -> {
                if (!hasOwnOptions()) {
                    return;
                }
                allClasses = !allClasses;
                final List<AscendancyConfig.Ascendancy> next = list();
                if (next.stream().noneMatch(ascendancy -> ascendancy.id().equals(currentId))) {
                    currentId = next.isEmpty() ? null : next.getFirst().id();
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                redraw();
            }
            case SLOT_ACTION -> {
                if (!acceptAction(click) || list.isEmpty()) {
                    return;
                }
                act(list.get(index(list)));
            }
            default -> {
                // 天賦只能看
            }
        }
    }

    private void act(final AscendancyConfig.Ascendancy current) {
        final Ascension ascension = service.ascension(player);
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
