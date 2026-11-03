package tw.exilecore.loot;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 掉落過濾：傳奇清單（2026-11-04 照站長「傳奇要能手動選擇每種傳奇那些要隱藏」「每個傳奇都列出來，選擇那些要隱藏」）。
 * 每一種傳奇都列出來，點一下就在「會撿」與「隱藏」之間切換（照它現在實際撿不撿；分組隱藏的點了改成這一種會撿）。
 * 存檔照 LootFilter 的每種傳奇規則（隱藏／必撿）；跟分組一樣的就清掉。追逐級永遠撿，不能隱藏。
 * 分頁照傳奇分組。每個角色各自設定，關閉時存檔（跟 {@link BasesMenu} 一樣）。
 * 同日稍早是左鍵隱藏、右鍵必撿的三種狀態，站長要的是每一種直接選要不要隱藏。
 * 這次之前傳奇只能整組撿或不撿。
 */
final class UniquesMenu extends Menu {

    private static final int PER_PAGE = 36;
    private static final int SLOT_SEARCH = 8;
    private static final int SLOT_INFO = 46;
    private static final int SLOT_CLEAR = 49;

    /** 分頁：全部、已設定，後面接傳奇分組（追逐級也列，只是不能設定）。 */
    private static final int TAB_ALL = 0;
    private static final int TAB_SET = 1;

    private final LootService service;
    private final Runnable back;
    private final long characterId;
    private final Map<Integer, UniqueItem> shown = new HashMap<>();
    private LootFilter filter;
    /** 0 全部、1 已設定、2 起是 UniqueGroup 的 ordinal + 2。 */
    private int tab;
    private String query = "";
    private int page;
    private boolean changed;

    UniquesMenu(final ExileCore plugin, final Player player, final LootService service, final Runnable back) {
        super(plugin, player, 6, "<dark_gray>掉落過濾：傳奇清單");
        this.service = service;
        this.back = back;
        this.filter = service.filter(player);
        final var character = plugin.players().get(player).character();
        this.characterId = character == null ? -1 : character.id();
    }

    private boolean stale() {
        final var character = plugin.players().get(player).character();
        return character == null || character.id() != characterId;
    }

    @Override
    protected void onClose() {
        if (changed && !stale()) {
            plugin.characters().save(player);
            changed = false;
        }
    }

    private static int tabCount() {
        return 2 + LootFilter.UniqueGroup.values().length;
    }

    private LootFilter.UniqueGroup tabGroup(final int which) {
        return which >= 2 ? LootFilter.UniqueGroup.values()[which - 2] : null;
    }

    private List<UniqueItem> uniques() {
        final List<UniqueItem> list = new ArrayList<>();
        final String needle = query.toLowerCase(Locale.ROOT);
        final LootFilter.UniqueGroup only = tabGroup(tab);
        for (final UniqueItem unique : plugin.items().repository().uniques()) {
            final LootFilter.UniqueGroup group = service.uniqueGroup(unique);
            if (only != null && group != only) {
                continue;
            }
            if (tab == TAB_SET && filter.uniqueRule(unique.id()) == LootFilter.UniqueRule.FOLLOW) {
                continue;
            }
            if (!needle.isEmpty() && !unique.name().toLowerCase(Locale.ROOT).contains(needle) && !unique.id().contains(needle)) {
                continue;
            }
            list.add(unique);
        }
        // 照分組 → 部位 → 掉落等級 → 名稱
        list.sort(Comparator.comparingInt((UniqueItem unique) -> service.uniqueGroup(unique).ordinal())
                .thenComparingInt(unique -> {
                    final ItemBase base = plugin.items().repository().base(unique.base());
                    return base == null ? Integer.MAX_VALUE : base.slot().ordinal();
                })
                .thenComparingInt(UniqueItem::dropLevel)
                .thenComparing(UniqueItem::name));
        return list;
    }

    @Override
    protected void draw() {
        frame();
        shown.clear();
        for (int i = 0; i < tabCount(); i++) {
            final LootFilter.UniqueGroup group = tabGroup(i);
            final String title = i == TAB_ALL ? "全部" : i == TAB_SET ? "跟分組不一樣的（" + filter.uniqueRules().size() + "）" : group.displayName();
            final Material material = i == TAB_ALL ? Material.CHEST : i == TAB_SET ? Material.BARRIER : groupIcon(group);
            final List<String> about = new ArrayList<>();
            about.add(group == null ? "<gray>只看" + title : "<gray>" + group.about());
            if (group != null) {
                about.add(group == LootFilter.UniqueGroup.CHASE ? "<#ffb347>追逐級永遠會撿"
                        : filter.hidesUniques(group) ? "<red>這一組現在隱藏" : "<green>這一組現在會撿");
            }
            set(i, tab(material, title, i == tab, about));
        }
        set(SLOT_SEARCH, icon(Material.SPYGLASS, "<white>搜尋傳奇",
                query.isEmpty() ? "<gray>輸入名稱的一部分" : "<gray>現在：<white>" + ItemRenderer.escape(query),
                "", "<yellow>左鍵：輸入", query.isEmpty() ? "" : "<yellow>右鍵：清掉搜尋"));
        final List<UniqueItem> list = uniques();
        final int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int slot = 9;
        for (final UniqueItem unique : list.subList(page * PER_PAGE, Math.min(list.size(), (page + 1) * PER_PAGE))) {
            shown.put(slot, unique);
            set(slot, entry(unique));
            slot++;
        }
        if (list.isEmpty()) {
            set(22, icon(Material.GRAY_DYE, "<gray>沒有符合的傳奇",
                    tab == TAB_SET ? "<dark_gray>還沒有另外設定的傳奇：到其他分頁點一下" : "<dark_gray>換個分頁或搜尋看看"));
        }
        if (pages > 1) {
            set(SLOT_PREV, pageButton(false, page, pages));
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        set(SLOT_BACK, backButton("掉落過濾"));
        set(SLOT_INFO, icon(Material.BOOK, "<white>傳奇清單",
                "<gray>每一種傳奇都列在這裡，點一下切換：",
                "<green>✔ 會撿</green><gray>：自動撿進背包",
                "<red>✘ 隱藏</red><gray>：不撿，換成金幣",
                "<dark_gray>一開始照過濾器的傳奇分組（那一組隱藏的就是 ✘）",
                "<dark_gray>追逐級永遠會撿，不能隱藏",
                "",
                "<yellow>點一下：會撿 ↔ 隱藏",
                "",
                "<gray>現在隱藏 <red>" + hiddenCount() + "</red> 種"));
        if (!filter.uniqueRules().isEmpty()) {
            set(SLOT_CLEAR, icon(Material.LAVA_BUCKET, "<red>全部改回照分組", "<gray>一種一種設定的全部清掉（" + filter.uniqueRules().size() + " 種）",
                    "", "<yellow>Shift＋左鍵：全部清掉"));
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    /** 這一種傳奇現在會不會被隱藏（自己的規則優先，沒設定照分組；追逐級永遠撿）。 */
    private boolean hidden(final UniqueItem unique) {
        final LootFilter.UniqueGroup group = service.uniqueGroup(unique);
        if (group == LootFilter.UniqueGroup.CHASE) {
            return false;
        }
        final LootFilter.UniqueRule rule = filter.uniqueRule(unique.id());
        return rule == LootFilter.UniqueRule.FOLLOW ? filter.hidesUniques(group) : rule == LootFilter.UniqueRule.HIDE;
    }

    private int hiddenCount() {
        int count = 0;
        for (final UniqueItem unique : plugin.items().repository().uniques()) {
            if (hidden(unique)) {
                count++;
            }
        }
        return count;
    }

    static Material groupIcon(final LootFilter.UniqueGroup group) {
        return switch (group) {
            case CHASE -> Material.NETHER_STAR;
            case COMMON -> Material.NETHERITE_SWORD;
            case EVENT -> Material.CHEST;
            case PINNACLE -> Material.DRAGON_HEAD;
            case ENDGAME -> Material.WITHER_SKELETON_SKULL;
            case HEIST -> Material.ECHO_SHARD;
        };
    }

    private ItemStack entry(final UniqueItem unique) {
        final LootFilter.UniqueGroup group = service.uniqueGroup(unique);
        final boolean chase = group == LootFilter.UniqueGroup.CHASE;
        final boolean hide = hidden(unique);
        final ItemBase base = plugin.items().repository().base(unique.base());
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + group.displayName() + "傳奇" + (base == null ? "" : "・" + ItemRenderer.escape(base.name()))
                + "・掉落等級 " + unique.dropLevel());
        if (chase) {
            lore.add("<#ffb347>追逐級：永遠會撿，不能隱藏");
        } else {
            lore.add(hide ? "<red>隱藏<gray>：不撿，換成金幣" : "<green>會撿<gray>：自動撿進背包");
            if (filter.uniqueRule(unique.id()) != LootFilter.UniqueRule.FOLLOW) {
                lore.add("<dark_gray>（跟「" + group.displayName() + "」這一組的設定不一樣）");
            }
            lore.add("");
            lore.add("<yellow>點一下：" + (hide ? "改成會撿" : "隱藏"));
        }
        final Material material = base == null ? Material.PAPER : Menu.material(base.iconMaterial(), Material.PAPER);
        final ItemStack stack = icon(material, (hide ? "<red>✘ " : "<green>✔ ") + "<#af6025>" + ItemRenderer.escape(unique.name()), lore);
        return hide ? offMark(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            onClose();
            back.run();
            return;
        }
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        if (slot >= 0 && slot < tabCount()) {
            tab = slot;
            page = 0;
            redraw();
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            page += slot == SLOT_NEXT ? 1 : -1;
            final int pages = Math.max(1, (uniques().size() + PER_PAGE - 1) / PER_PAGE);
            page = Math.floorMod(page, pages);
            redraw();
            return;
        }
        if (slot == SLOT_SEARCH) {
            if (click.isRightClick()) {
                query = "";
                page = 0;
                redraw();
                return;
            }
            plugin.dialogs().form(player, "<dark_gray>搜尋傳奇", List.of("<gray>輸入傳奇名稱的一部分"),
                    List.of(new tw.exilecore.gui.Dialogs.Text("q", "名稱", query, 20)), "搜尋", answers -> {
                        query = answers.get("q").trim();
                        page = 0;
                        open();
                    }, this::open);
            return;
        }
        if (stale()) {
            return;
        }
        if (slot == SLOT_CLEAR) {
            if (click != ClickType.SHIFT_LEFT || filter.uniqueRules().isEmpty()) {
                return;
            }
            final LootFilter next = filter.copy();
            next.clearUniqueRules();
            apply(next);
            return;
        }
        final UniqueItem unique = shown.get(slot);
        if (unique == null || service.uniqueGroup(unique) == LootFilter.UniqueGroup.CHASE) {
            return;
        }
        if (!click.isLeftClick() && !click.isRightClick()) {
            return;
        }
        // 會撿 ↔ 隱藏：跟分組一樣的就不另外記（分組改了會跟著變）
        final LootFilter next = filter.copy();
        final boolean groupHides = next.hidesUniques(service.uniqueGroup(unique));
        final boolean hideNow = !hidden(unique);
        next.setUniqueRule(unique.id(), hideNow == groupHides ? LootFilter.UniqueRule.FOLLOW
                : hideNow ? LootFilter.UniqueRule.HIDE : LootFilter.UniqueRule.ALWAYS);
        apply(next);
    }

    private void apply(final LootFilter next) {
        filter = next;
        service.setFilter(player, next);
        changed = true;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        redraw();
    }
}
