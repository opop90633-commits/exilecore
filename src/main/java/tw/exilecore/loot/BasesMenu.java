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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 掉落過濾：基底清單（2026-11-03 照站長，取代「只撿的基底」）。每一種基底（武器、防具、盾與箭袋、飾品、珠寶、遺時星晶、
 * 劫盜特殊項鍊、地圖）各自是顯示／隱藏／必撿：隱藏的絕對不撿（有 T1 詞綴也不撿）、必撿的有好詞綴門檻以上就一律撿（門檻關＝全部撿）、
 * 顯示的照過濾器其他的規則。
 * 左鍵顯示 ↔ 隱藏、右鍵顯示 ↔ 必撿；分頁標籤 Shift＋右鍵整類隱藏／顯示。傳奇不受影響（照傳奇分組）。每個角色各自設定，關閉時存檔。
 */
final class BasesMenu extends Menu {

    private enum Category {
        ALL("全部", Material.CHEST),
        SET("已設定", Material.BARRIER),
        WEAPON("武器", Material.IRON_SWORD),
        ARMOUR("防具", Material.IRON_CHESTPLATE),
        OFFHAND("盾、箭袋", Material.SHIELD),
        JEWELLERY("飾品", Material.GOLD_NUGGET),
        JEWEL("珠寶", Material.AMETHYST_CLUSTER),
        MAP("地圖", Material.FILLED_MAP);

        private final String title;
        private final Material icon;

        Category(final String title, final Material icon) {
            this.title = title;
            this.icon = icon;
        }

        boolean matches(final ItemBase base) {
            return switch (this) {
                case ALL, SET -> true;
                case WEAPON -> base.isWeapon();
                case ARMOUR -> base.isArmour() && !base.isShield();
                case OFFHAND -> base.isShield() || base.isQuiver();
                case JEWELLERY -> base.isJewellery();
                case JEWEL -> base.isJewel();
                case MAP -> base.isMap();
            };
        }

        /** 可以整類隱藏／顯示的分頁（全部、已設定不行）。 */
        boolean bulk() {
            return this != ALL && this != SET;
        }

        static Category parse(final String name) {
            try {
                return valueOf(name);
            } catch (final IllegalArgumentException | NullPointerException e) {
                return ALL;
            }
        }
    }

    private static final int PER_PAGE = 36;
    private static final int SLOT_SEARCH = 8;
    private static final int SLOT_INFO = 46;
    private static final int SLOT_CLEAR = 49;

    private final LootService service;
    private final Runnable back;
    private final long characterId;
    private final Map<Integer, ItemBase> shown = new HashMap<>();
    private LootFilter filter;
    private Category category;
    private String query = "";
    private int page;
    private boolean changed;

    BasesMenu(final ExileCore plugin, final Player player, final LootService service, final String category, final Runnable back) {
        super(plugin, player, 6, "<dark_gray>掉落過濾：基底清單");
        this.service = service;
        this.back = back;
        this.category = Category.parse(category);
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

    /** 這一頁列的基底（藥劑、護符不是基底清單管的）。 */
    private List<ItemBase> bases() {
        final List<ItemBase> list = new ArrayList<>();
        final String needle = query.toLowerCase(Locale.ROOT);
        for (final ItemBase base : plugin.items().repository().bases()) {
            if (base.isFlaskLike() || !category.matches(base)) {
                continue;
            }
            if (category == Category.SET && filter.baseRule(base.id()) == LootFilter.BaseRule.SHOW) {
                continue;
            }
            if (!needle.isEmpty() && !base.name().toLowerCase(Locale.ROOT).contains(needle) && !base.id().contains(needle)) {
                continue;
            }
            list.add(base);
        }
        // 跟商人賭博一樣的排序（VendorService.bases）：欄位 → 同一類（照資料檔的順序）排在一起 →
        // 沒有固定詞綴的在前 → 傷害種類少的在前 → 暴擊、攻速 → 防禦
        final Map<String, Integer> familyOrder = new HashMap<>();
        for (final ItemBase base : plugin.items().repository().bases()) {
            familyOrder.putIfAbsent(base.family(), familyOrder.size());
        }
        list.sort(Comparator.comparing((ItemBase base) -> base.slot().ordinal())
                .thenComparingInt(base -> familyOrder.getOrDefault(base.family(), Integer.MAX_VALUE))
                .thenComparing(base -> base.implicit() != null)
                .thenComparingInt(base -> base.damage().size())
                .thenComparingDouble(ItemBase::crit).thenComparingDouble(ItemBase::aps)
                .thenComparingDouble(base -> base.armour() + base.evasion() + base.energyShield())
                .thenComparing(ItemBase::id));
        return list;
    }

    @Override
    protected void draw() {
        frame();
        shown.clear();
        final Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            final Category each = categories[i];
            final String count = each == Category.SET ? "（" + filter.baseRules().size() + "）" : "";
            final List<String> about = new ArrayList<>();
            about.add("<gray>只看" + each.title);
            if (each.bulk()) {
                about.add("<yellow>Shift＋右鍵：這一類" + (anyShown(each) ? "全部隱藏" : "全部改成顯示"));
            }
            set(i, tab(each.icon, each.title + count, each == category, about));
        }
        set(SLOT_SEARCH, icon(Material.SPYGLASS, "<white>搜尋基底",
                query.isEmpty() ? "<gray>輸入名稱的一部分" : "<gray>現在：<white>" + ItemRenderer.escape(query),
                "", "<yellow>左鍵：輸入", query.isEmpty() ? "" : "<yellow>右鍵：清掉搜尋"));
        final List<ItemBase> list = bases();
        final int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int slot = 9;
        for (final ItemBase base : list.subList(page * PER_PAGE, Math.min(list.size(), (page + 1) * PER_PAGE))) {
            shown.put(slot, base);
            set(slot, entry(base));
            slot++;
        }
        if (list.isEmpty()) {
            set(22, icon(Material.GRAY_DYE, "<gray>沒有符合的基底",
                    category == Category.SET ? "<dark_gray>還沒有隱藏或必撿的基底：到其他分頁點一下" : "<dark_gray>換個分頁或搜尋看看"));
        }
        if (pages > 1) {
            set(SLOT_PREV, pageButton(false, page, pages));
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        set(SLOT_BACK, backButton("掉落過濾"));
        set(SLOT_INFO, icon(Material.BOOK, "<white>基底清單",
                "<gray>每一種基底各自設定：",
                "<white>顯示</white><gray>：照過濾器其他的規則（稀有度、好詞綴、等級差、同類同系）",
                "<red>隱藏</red><gray>：絕對不撿，有 T1 詞綴也不撿（換成金幣）",
                "<#f5b942>必撿</#f5b942><gray>：有「保留好詞綴」門檻以上詞綴的一律撿（不看稀有度、等級差、同類同系）",
                filter.rules().keepAffixTier() > 0 ? "<dark_gray>　現在門檻 T" + filter.rules().keepAffixTier() + "；沒有這麼好詞綴的不撿"
                        : "<dark_gray>　現在門檻是關：必撿的全部撿",
                "<dark_gray>傳奇不受影響（照過濾器的傳奇分組）；藥劑、護符在「寶石與藥劑」分頁",
                "",
                "<yellow>左鍵：顯示 ↔ 隱藏　右鍵：顯示 ↔ 必撿",
                "<yellow>分頁標籤 Shift＋右鍵：整類隱藏／顯示",
                "",
                "<gray>現在：<red>隱藏 " + filter.hiddenBases().size() + " 種<gray>、<#f5b942>必撿 " + filter.alwaysBases().size() + " 種"));
        if (!filter.baseRules().isEmpty()) {
            set(SLOT_CLEAR, icon(Material.LAVA_BUCKET, "<red>全部清掉", "<gray>把所有基底改回顯示（" + filter.baseRules().size() + " 種有設定）",
                    "", "<yellow>Shift＋左鍵：全部清掉"));
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    /** 這一類有沒有任何一種是顯示的（整類切換用）。 */
    private boolean anyShown(final Category which) {
        for (final ItemBase base : plugin.items().repository().bases()) {
            if (!base.isFlaskLike() && which.matches(base) && filter.baseRule(base.id()) != LootFilter.BaseRule.HIDE) {
                return true;
            }
        }
        return false;
    }

    private ItemStack entry(final ItemBase base) {
        final LootFilter.BaseRule rule = filter.baseRule(base.id());
        final String only = onlySource(base); // 2026-11-04 照站長：裂隙指環、精煉裂隙指環、淵印指環寫實際掉的地方（這次之前一律寫只在劫盜掉）
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + base.slot().displayName() + (base.isMap() ? "" : "・掉落等級 " + base.dropLevel()) + (only == null ? "" : "<dark_gray>・只在" + only + "掉"));
        lore.add(switch (rule) {
            case SHOW -> "<white>顯示<gray>：照過濾器其他的規則撿";
            case HIDE -> "<red>隱藏<gray>：絕對不撿（有好詞綴也不撿）";
            case ALWAYS -> filter.rules().keepAffixTier() > 0 && !base.isMap()
                    ? "<#f5b942>必撿<gray>：有 T" + filter.rules().keepAffixTier() + " 以上詞綴的一律撿" : "<#f5b942>必撿<gray>：一律撿";
        });
        lore.add("");
        lore.add("<yellow>左鍵：" + (rule == LootFilter.BaseRule.HIDE ? "改成顯示" : "隱藏"));
        lore.add("<yellow>右鍵：" + (rule == LootFilter.BaseRule.ALWAYS ? "改成顯示" : "必撿"));
        final String prefix = switch (rule) {
            case SHOW -> "<white>";
            case HIDE -> "<red>✘ ";
            case ALWAYS -> "<#f5b942>★ ";
        };
        // 2026-11-05 照站長「過濾那邊的基底圖示都是原版的圖案」：跟圖鑑一樣用基底的圖（Menu.baseIcon：普通裝備的簡圖 plain:種類）；這次之前直接用原版材質
        final ItemStack stack = Menu.baseIcon(base, prefix + ItemRenderer.escape(base.name()), lore);
        return switch (rule) {
            case SHOW -> stack;
            case HIDE -> offMark(stack);
            case ALWAYS -> highlight(stack);
        };
    }

    /**
     * 2026-11-04：這種基底只在哪裡掉（一般掉落沒有的才寫）：地圖事件（map_events.yml 的 ring）→「裂隙」「深淵裂縫」，
     * 事件巔峰的指定基底（maps.yml 的 extra base）→ 那張巔峰的名字，星脈深坑的專屬基底（2026-11-07）→「星脈深坑」，其他劫盜專屬的 →「劫盜」；一般掉落的回傳 null。
     */
    private String onlySource(final ItemBase base) {
        final var repository = plugin.items().repository();
        if (!repository.heistOnlyBase(base.id())) {
            return null;
        }
        final List<String> where = new ArrayList<>();
        if (plugin.maps() != null) {
            final tw.exilecore.map.MapEventConfig events = plugin.maps().eventConfig();
            for (final tw.exilecore.map.MapEventConfig.Kind kind : tw.exilecore.map.MapEventConfig.Kind.values()) {
                final tw.exilecore.map.MapEventConfig.EventBase setting = events.eventBase(kind);
                if (setting != null && setting.base().equals(base.id())) {
                    where.add(switch (kind) {
                        case BREACH -> "裂隙";
                        case ABYSS -> "深淵裂縫";
                        case SHRINE -> "神龕";
                        case STRONGBOX -> "寶箱";
                        case EXILE -> "流寇";
                        case SAFE -> "保險箱";
                    });
                }
            }
            if (base.id().equals(events.vaalDrops().baseId())) {
                where.add("星脈深坑"); // 2026-11-07 照站長：祭紋華袍只在深坑小怪掉
            }
            final tw.exilecore.map.MapConfig maps = plugin.maps().config();
            for (final tw.exilecore.map.MapConfig.MapDef map : maps.pinnacles()) {
                final tw.exilecore.map.MapConfig.Pinnacle pinnacle = maps.pinnacle(map.id());
                if (pinnacle == null || pinnacle.drops() == null) {
                    continue;
                }
                for (final tw.exilecore.map.MapConfig.ExtraDrop extra : pinnacle.drops().extra()) {
                    if ("base".equals(extra.kind()) && extra.id().equals(base.id())) {
                        where.add(map.name() + "的 Boss");
                    }
                }
            }
        }
        if (base.dropWeight() > 0 || where.isEmpty()) {
            where.add("劫盜");
        }
        return String.join("、", where);
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
        if (slot >= 0 && slot < Category.values().length) {
            final Category picked = Category.values()[slot];
            if (click == ClickType.SHIFT_RIGHT && picked.bulk() && !stale()) {
                // 整類隱藏／顯示：有顯示的就全部隱藏，不然全部改回顯示（必撿的也會被改）
                final boolean hide = anyShown(picked);
                final LootFilter next = filter.copy();
                for (final ItemBase base : plugin.items().repository().bases()) {
                    if (!base.isFlaskLike() && picked.matches(base)) {
                        next.setBaseRule(base.id(), hide ? LootFilter.BaseRule.HIDE : LootFilter.BaseRule.SHOW);
                    }
                }
                apply(next);
                return;
            }
            category = picked;
            page = 0;
            redraw();
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            page += slot == SLOT_NEXT ? 1 : -1;
            final int pages = Math.max(1, (bases().size() + PER_PAGE - 1) / PER_PAGE);
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
            plugin.dialogs().form(player, "<dark_gray>搜尋基底", List.of("<gray>輸入基底名稱的一部分（例如：劍、弓、戒指、星晶）"),
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
            if (click != ClickType.SHIFT_LEFT || filter.baseRules().isEmpty()) {
                return;
            }
            final LootFilter next = filter.copy();
            next.clearBaseRules();
            apply(next);
            return;
        }
        final ItemBase base = shown.get(slot);
        if (base == null) {
            return;
        }
        final LootFilter next = filter.copy();
        if (click.isLeftClick()) {
            next.toggleBaseHidden(base.id());
        } else if (click.isRightClick()) {
            next.toggleBaseAlways(base.id());
        } else {
            return;
        }
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
