package tw.exilecore.vendor;

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
import tw.exilecore.item.Rarity;
import tw.exilecore.loot.LootFilter;
import tw.exilecore.salvage.SalvageMenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 商人的視窗：「購買」分頁是這個角色的一批貨（左鍵買），「賭博」分頁是這個等級賭得到的基底（左鍵賭一件），
 * 「補給」分頁用金幣買藥劑與未切割寶石（價格固定，跟著劇情進度解鎖）。
 * 最下面一列：分頁、馬上換一批（購買）或上一頁／下一頁（賭博的基底超過 45 種時）、賣東西（拆解師）、
 * 只看我這類（賭博：只列跟手上武器同類、跟身上防具同系的基底，跟掉落過濾器同一套判斷）、分類（購買、賭博只看某一類，2026-10）。
 * 賭博的基底照裝備欄位排（武器、副手、頭、身、腿、腳、戒指、項鍊、腰帶），同一類排在一起，賭得到傳奇的標 ★。
 */
public final class VendorMenu extends Menu {

    private enum Tab { BUY, GAMBLE, SHOP }

    private static final int SLOT_INFO = 45;
    private static final int SLOT_TAB_BUY = 46;
    private static final int SLOT_TAB_GAMBLE = 47;
    private static final int SLOT_TAB_SHOP = 48;
    private static final int SLOT_REFRESH = 49;
    /** 賭博分頁的上一頁／下一頁（購買分頁的 49 是「馬上換一批」）。 */
    private static final int SLOT_PREVIOUS = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_SELL = 51;
    /** 賭博分頁的「只看我這類」開關（記在角色進度，下次打開還是一樣）。 */
    private static final int SLOT_MINE = 52;
    static final String PROGRESS_GAMBLE_MINE = "vendor.gamble_mine";
    /** 分類（2026-10）：購買、賭博只看某一類（左鍵下一類、右鍵上一類；記在角色進度）。 */
    private static final int SLOT_CATEGORY = 53;
    static final String PROGRESS_CATEGORY = "vendor.category";
    /** 一頁放幾種基底（最後一格是搜尋；2026-10 第二版拿掉「基底等級」那格：基底照物品等級，賭、買都照你的等級）。 */
    private static final int PAGE_SIZE = 44;
    /** 賭博分頁的搜尋（2026-10）。 */
    private static final int SLOT_SEARCH = 44;
    private String query = "";
    /** 補給：藥劑從第 0 格、技能寶石從第 9 格（兩排）、輔助寶石從第 27 格。 */
    private static final int SHOP_FLASKS = 0;
    private static final int SHOP_SKILLS = 9;
    private static final int SHOP_SUPPORTS = 27;
    /** Shift＋左鍵一次買幾個寶石。 */
    private static final int BULK = 5;

    private final VendorService service;
    private Tab tab = Tab.BUY;
    /** 賭博分頁目前這一頁畫面上的基底（格子 → 基底；點的時候用）。 */
    private final List<ItemBase> gambleBases = new ArrayList<>();
    /** 賭博分頁第幾頁（從 0 起）。 */
    private int gamblePage;
    /** 補給畫面上每一格是哪一樣（點的時候用）。 */
    private final java.util.Map<Integer, VendorService.Supply> shownSupplies = new java.util.HashMap<>();
    /** 畫面上這一批貨（點的時候核對：中途換了一批就不買，免得買到沒看過的東西）。 */
    private VendorService.Stock shownStock;
    /** 購買分頁每一格是這批貨的第幾件（有分類時不是照順序）。 */
    private final List<Integer> shownIndices = new ArrayList<>();

    public VendorMenu(final ExileCore plugin, final Player player, final VendorService service) {
        super(plugin, player, 6, "<dark_gray>商人");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, i < 45 ? null : filler());
        }
        final long gold = plugin.gold().balance(player);
        switch (tab) {
            case GAMBLE -> drawGamble(gold);
            case SHOP -> drawShop(gold);
            default -> drawStock(gold);
        }
        set(SLOT_TAB_BUY, tab(Material.EMERALD, "購買", tab == Tab.BUY));
        set(SLOT_TAB_GAMBLE, tab(Material.ENDER_EYE, "賭博", tab == Tab.GAMBLE));
        set(SLOT_TAB_SHOP, tab(Material.GLASS_BOTTLE, "補給", tab == Tab.SHOP));
        if (tab != Tab.SHOP) {
            set(SLOT_CATEGORY, categoryButton());
        }
        set(SLOT_SELL, icon(Material.GRINDSTONE, "<#f5b942>賣東西", "<gray>賣給商人＝拆解：換金幣與品質通貨", "", "<yellow>左鍵：打開拆解台"));
    }

    /** 分頁：目前這一頁有外框；沒套用資源包的人看到沒選的那頁變成灰色（只發光看不出來）。 */
    private ItemStack tab(final Material material, final String name, final boolean selected) {
        if (selected) {
            return highlight(icon(material, "<#f5b942>▶ " + name, "<green>目前這一頁"));
        }
        // 選到的那頁畫得出外框就不用變灰
        return icon(frames(ItemStack.of(material)) ? material : Material.GRAY_DYE, "<gray>" + name, "<yellow>左鍵：切換");
    }

    private void drawStock(final long gold) {
        final VendorService.Stock stock = service.stock(player);
        shownStock = stock;
        shownIndices.clear();
        final VendorService.Category category = category();
        final List<ItemStack> goods = stock.items;
        for (int index = 0; index < goods.size() && shownIndices.size() < PAGE_SIZE; index++) {
            if (category == VendorService.Category.ALL || category.matches(service.slotOf(goods.get(index)))) {
                shownIndices.add(index);
            }
        }
        for (int i = 0; i < shownIndices.size(); i++) {
            final ItemStack item = goods.get(shownIndices.get(i));
            final long price = service.price(item);
            set(i, appendLore(item.clone(), "",
                    String.format(Locale.ROOT, "%s價格：%,d 金幣", gold >= price ? "<#f5b942>" : "<red>", price),
                    "<yellow>左鍵：購買"));
        }
        if (goods.isEmpty()) {
            set(22, icon(Material.GLASS_PANE, "<gray>這一批賣完了", "<dark_gray>等下一批，或花金幣馬上換"));
        } else if (shownIndices.isEmpty()) {
            set(22, icon(Material.GLASS_PANE, "<gray>這一批沒有「" + category.label + "」", "<dark_gray>右下角換分類，或花金幣馬上換一批"));
        }
        final long next = service.nextRefresh(stock);
        set(SLOT_INFO, icon(Material.BOOK, "<white>商人（你有 " + String.format(Locale.ROOT, "%,d", gold) + " 金幣）",
                "<gray>賣你這個等級用得到的裝備（已鑑定）",
                "<gray>每個角色自己一批，升級或每 " + service.config().vendorRefreshMinutes() + " 分鐘換一批",
                "<gray>照裝備欄位排；你職業用的基底比較常出現",
                "<gray>想買低等級的貨：經驗瓶那格換「基底等級」（每一段自己一批）",
                "<gray>分類：<white>" + category.label + "</white> <dark_gray>（右下角換）",
                "<dark_gray>下一批：" + Math.max(1, next / 60_000) + " 分鐘後"));
        final int level = plugin.players().get(player).level();
        set(SLOT_REFRESH, icon(Material.CLOCK, "<#f5b942>馬上換一批",
                String.format(Locale.ROOT, "<gray>花 <#f5b942>%,d 金幣</#f5b942> 換新的貨", service.config().vendorRefreshCost(level)),
                "", "<yellow>左鍵：換"));
    }

    private void drawGamble(final long gold) {
        final int level = plugin.players().get(player).level();
        final long price = service.config().gamblePrice(level);
        gambleBases.clear();
        final boolean mineOnly = gambleMineOnly();
        final LootFilter.Viewer viewer = mineOnly && plugin.loot() != null ? plugin.loot().viewer(player) : null;
        final List<ItemBase> all = new ArrayList<>();
        final Set<String> uniqueCapable = new HashSet<>();
        final java.util.Map<String, Double> uniqueWeights = new java.util.HashMap<>();
        final Set<String> bossBases = new HashSet<>();
        int hiddenByMine = 0;
        final VendorService.Category category = category();
        final List<ItemBase> source = service.bases(level);
        for (final ItemBase base : source) {
            if (!category.matches(base.slot()) || !Menu.matches(query, base.name())) {
                continue;
            }
            if (viewer != null && LootFilter.unlikeMine(base.tags(), viewer, true, true)) {
                hiddenByMine++;
                continue;
            }
            all.add(base);
            final List<UniqueItem> uniques = service.gambleUniques(base, level);
            final double weight = service.uniqueWeight(uniques);
            if (weight > 0) { // 權重設成 0 的就當作賭不到（不畫 ★）
                uniqueCapable.add(base.id());
                uniqueWeights.put(base.id(), weight);
                if (service.bossRate(uniques)) {
                    bossBases.add(base.id());
                }
            }
        }
        // 2026-10 第二版：照 service.bases() 的順序（欄位 → 同一類排在一起），賭得到傳奇的只標 ★ 不往前搬（不然同一類會被拆開）
        final int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        gamblePage = Math.max(0, Math.min(gamblePage, pages - 1));
        final int from = gamblePage * PAGE_SIZE;
        gambleBases.addAll(all.subList(Math.min(from, all.size()), Math.min(from + PAGE_SIZE, all.size())));
        int uniqueBases = 0;
        boolean anyBossBase = false;
        for (final ItemBase base : all) {
            if (uniqueCapable.contains(base.id())) {
                uniqueBases++;
            }
            anyBossBase |= bossBases.contains(base.id());
        }
        for (int i = 0; i < gambleBases.size(); i++) {
            final ItemBase base = gambleBases.get(i);
            final boolean unique = uniqueCapable.contains(base.id());
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>" + base.slot().displayName() + "　<dark_gray>買下來才知道是什麼（未鑑定）");
            lore.addAll(baseLines(base, level));
            final double weight = uniqueWeights.getOrDefault(base.id(), 0.0);
            lore.add("<gray>魔法 " + percent(Rarity.MAGIC, weight) + "、稀有 " + percent(Rarity.RARE, weight)
                    + (unique && weight > 0 ? "、<#af6025>傳奇 " + percent(Rarity.UNIQUE, weight) : "")); // 2026-10-30：只有追逐級的基底不寫「傳奇 0%」（追逐級的機率在下一行）
            lore.addAll(chaseLines(base, level));
            lore.add(String.format(Locale.ROOT, "%s價格：%,d 金幣", gold >= price ? "<#f5b942>" : "<red>", price));
            lore.add("");
            lore.add("<yellow>左鍵：賭一件");
            lore.add("<yellow>Shift＋左鍵：一直賭到背包上面三排滿（金幣不夠就停）");
            // 2026-10-24：跟掉落的裝備一樣在格子裡畫簡圖（Menu.baseIcon）
            final ItemStack icon = baseIcon(base, (unique ? "<#af6025>★ " : "") + "<white>未鑑定的" + ItemRenderer.escape(base.name()), lore);
            set(i, unique ? highlight(icon) : icon); // 賭得到傳奇的：外框（沒資源包：發光）
        }
        final int lowest = service.lowestGambleUniqueLevel();
        final String uniqueLine;
        if (uniqueBases > 0) {
            uniqueLine = "<#af6025>★ <gray>名字有 ★ 的 " + uniqueBases + " 種基底有機會賭出傳奇";
        } else if (lowest > level) {
            uniqueLine = "<dark_gray>傳奇要 Lv." + lowest + " 以上才賭得到";
        } else {
            uniqueLine = "<dark_gray>目前沒有賭得到的傳奇";
        }
        final List<String> info = new ArrayList<>(List.of(
                String.format(Locale.ROOT, "<gray>一件 %,d 金幣（依你的等級）", price),
                "<gray>物品等級＝你的等級，拿去鑑定師鑑定",
                "<gray>基底數值、詞綴都照物品等級",
                uniqueLine));
        if (anyBossBase && plugin.maps() != null) {
            // 地圖 Boss 也會掉的傳奇：賭的機率照 Boss 校準，比打 Boss 低（只有列出來的基底裡有這種傳奇才寫）
            info.add("<dark_gray>地圖 Boss 也會掉的傳奇比較難賭：一次 " + percent(Rarity.UNIQUE, service.config().gambleBossUnique())
                    + "（打地圖 Boss 是 " + Math.round(plugin.maps().config().drops().bossUniqueChance() * 100) + "%）");
        }
        final double chase = tw.exilecore.item.ItemGenerator.chaseChance();
        if (chase > 0) {
            info.add("<#ff6ad5>✦ <gray>追逐級傳奇也賭得到：基底對的話一次 " + tinyPercent(chase) + "<dark_gray>（跟小怪掉落、僥倖石一樣，"
                    + "滑過基底看是哪一件）");
        }
        info.add("<gray>照裝備欄位排：武器、副手、頭、身、腿、腳、戒指、項鍊、腰帶");
        info.add("<gray>同一類有好幾種變化（暴擊、攻速、固定詞綴不同），滑過去看");
        info.add("<gray>分類：<white>" + category.label + "</white> <dark_gray>（右下角換）");
        if (mineOnly) {
            info.add("<gray>只看我這類：" + (hiddenByMine > 0 ? "藏了 <white>" + hiddenByMine + "</white> 種跟你不同類的" : "沒有要藏的")
                    + "　<dark_gray>右下角可以關");
        }
        if (pages > 1) {
            info.add("");
            info.add("<gray>共 " + all.size() + " 種基底，第 <white>" + (gamblePage + 1) + "</white>／" + pages + " 頁　<dark_gray>下面的箭頭翻頁");
        }
        set(SLOT_INFO, icon(Material.BOOK, "<white>賭博（你有 " + String.format(Locale.ROOT, "%,d", gold) + " 金幣）", info));
        if (pages > 1) {
            // 翻頁：沒有上一頁／下一頁時變灰（還是看得到自己在第幾頁）
            final boolean hasPrevious = gamblePage > 0;
            final boolean hasNext = gamblePage < pages - 1;
            set(SLOT_PREVIOUS, pageButton(hasPrevious, Material.ARROW, "上一頁", "已經是第一頁", pages));
            set(SLOT_NEXT, pageButton(hasNext, Material.SPECTRAL_ARROW, "下一頁", "已經是最後一頁", pages));
        } else {
            set(SLOT_REFRESH, filler());
        }
        if (all.isEmpty()) {
            set(22, icon(Material.GLASS_PANE, "<gray>沒有能賭的基底", mineOnly && hiddenByMine > 0
                    ? "<dark_gray>「只看我這類」把全部藏起來了，右下角關掉" : "<dark_gray>這個等級沒有東西可以賭"));
        }
        set(SLOT_MINE, mineButton(mineOnly, viewer));
        set(SLOT_SEARCH, searchButton(query, "基底"));
    }

    /** 賭博列一個基底的說明（2026-10 第二版：同一類有好幾種變化，照這個物品等級寫出基底數值與固定詞綴）。 */
    private List<String> baseLines(final ItemBase base, final int itemLevel) {
        final List<String> lines = new ArrayList<>();
        final tw.exilecore.item.ItemRepository repository = plugin.items().repository();
        final tw.exilecore.item.ItemData sample = new tw.exilecore.item.ItemData(base.id(), Rarity.NORMAL, itemLevel);
        final tw.exilecore.item.ItemStats.Snapshot snap = tw.exilecore.item.ItemStats.snapshot(repository, base, sample);
        if (!snap.damage().isEmpty()) {
            final StringBuilder damage = new StringBuilder();
            for (final java.util.Map.Entry<tw.exilecore.stats.DamageType, double[]> entry : snap.damage().entrySet()) {
                damage.append(damage.isEmpty() ? "" : "、").append(entry.getKey().displayName()).append(' ')
                        .append(Math.round(entry.getValue()[0])).append('–').append(Math.round(entry.getValue()[1]));
            }
            lines.add("<gray>" + damage + "　<dark_gray>暴擊 " + snap.crit() + "%・每秒 " + snap.aps() + " 次");
        }
        final List<String> defence = new ArrayList<>();
        if (snap.armour() > 0) {
            defence.add("護甲 " + Math.round(snap.armour()));
        }
        if (snap.evasion() > 0) {
            defence.add("閃避 " + Math.round(snap.evasion()));
        }
        if (snap.energyShield() > 0) {
            defence.add("能量護盾 " + Math.round(snap.energyShield()));
        }
        if (snap.block() > 0) {
            defence.add("格擋 " + Math.round(snap.block()) + "%");
        }
        if (!defence.isEmpty()) {
            lines.add("<gray>" + String.join("・", defence));
        }
        final String granted = plugin.items().renderer().grantedLabel(base.id(), itemLevel); // 2026-10-28 照站長：賭博也寫賦予的技能
        if (granted != null) {
            lines.add("<gray>賦予技能：<#c9a7ff>" + tw.exilecore.item.ItemRenderer.escape(granted));
        }
        final tw.exilecore.item.Implicit implicit = tw.exilecore.item.ItemStats.implicit(repository, base, sample);
        if (implicit != null) {
            lines.add("<#a0a0ff>" + implicit.rangeText() + " <dark_gray>(固定)"
                    + tw.exilecore.item.AffixChips.miniMessage(tw.exilecore.item.AffixChips.of(implicit.mods())));
        }
        return lines;
    }

    /** 「只看我這類」開關：開著有外框（沒資源包：發光），說明寫現在算哪一類。 */
    private ItemStack mineButton(final boolean on, final LootFilter.Viewer viewer) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>只列跟手上武器同類、跟身上防具同系的基底");
        lore.add("<gray>（跟掉落過濾器「只撿跟我同類的」同一套判斷）");
        if (on && viewer != null) {
            lore.add("");
            lore.add("<gray>手上：" + (viewer.weaponTags().isEmpty() ? "<dark_gray>沒拿武器（武器全列）" : "<white>" + weaponWords(viewer.weaponTags())));
            lore.add("<gray>身上：" + (viewer.armourFamilies().isEmpty() ? "<dark_gray>沒穿防具（防具全列）" : "<white>" + familyWords(viewer.armourFamilies())));
        }
        lore.add("");
        lore.add(on ? "<green>開著</green> <dark_gray>｜ <yellow>左鍵：關掉，看全部" : "<gray>關著</gray> <dark_gray>｜ <yellow>左鍵：只看我這類");
        final ItemStack icon = icon(on ? Material.SPYGLASS : Material.GRAY_DYE, (on ? "<#f5b942>" : "<gray>") + "只看我這類", lore);
        return on ? highlight(icon) : icon;
    }

    private static String weaponWords(final Set<String> tags) {
        final List<String> words = new ArrayList<>();
        for (final String tag : tags) {
            if (LootFilter.WEAPON_CLASSES.contains(tag)) {
                words.add(LootFilter.weaponWord(tag));
            }
        }
        if (tags.contains("two_hand")) {
            words.add("雙手（不列盾）");
        }
        return words.isEmpty() ? "武器" : String.join("、", words);
    }

    private static String familyWords(final Set<String> families) {
        final List<String> words = new ArrayList<>();
        for (final String family : families) {
            words.add(LootFilter.familyWord(family));
        }
        return String.join("、", words);
    }

    private VendorService.Category category() {
        return VendorService.Category.parse(plugin.players().get(player).progress().get(PROGRESS_CATEGORY));
    }

    /** 分類按鈕：列出全部分類，目前這一類標起來。 */
    private ItemStack categoryButton() {
        final VendorService.Category current = category();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>只看某一類裝備（購買、賭博都適用）");
        lore.add("");
        for (final VendorService.Category category : VendorService.Category.values()) {
            lore.add(category == current ? "<#f5b942>▶ " + category.label : "<dark_gray>　" + category.label);
        }
        lore.add("");
        lore.add("<yellow>左鍵：下一類　右鍵：上一類");
        final ItemStack icon = icon(current == VendorService.Category.ALL ? Material.HOPPER : Material.COMPASS,
                "<#f5b942>分類：" + current.label, lore);
        return current == VendorService.Category.ALL ? icon : highlight(icon);
    }

    private boolean gambleMineOnly() {
        return "1".equals(plugin.players().get(player).progress().get(PROGRESS_GAMBLE_MINE));
    }

    /** 翻頁按鈕：能翻才亮，不能翻變灰並說為什麼。 */
    private ItemStack pageButton(final boolean enabled, final Material material, final String name, final String why, final int pages) {
        final String position = "<gray>第 " + (gamblePage + 1) + "／" + pages + " 頁";
        if (!enabled) {
            return icon(Material.GRAY_DYE, "<dark_gray>" + name, position, "<dark_gray>" + why);
        }
        return icon(material, "<white>" + name, position, "<yellow>左鍵：" + name);
    }

    private void drawShop(final long gold) {
        shownSupplies.clear();
        set(SLOT_REFRESH, filler());
        if (!service.shop().enabled()) {
            set(22, icon(Material.BARRIER, "<gray>補給沒有開", "<dark_gray>economy.yml 的 shop.enabled"));
            set(SLOT_INFO, icon(Material.BOOK, "<white>補給"));
            return;
        }
        int flaskSlot = SHOP_FLASKS;
        int skillSlot = SHOP_SKILLS;
        int supportSlot = SHOP_SUPPORTS;
        for (final VendorService.Supply supply : service.supplies(player)) {
            final int slot = switch (supply.kind()) {
                case LIFE_FLASK, MANA_FLASK -> flaskSlot++;
                case UNCUT_SKILL -> skillSlot++;
                case UNCUT_SUPPORT -> supportSlot++;
            };
            if (slot >= 45 || (supply.kind() == VendorService.SupplyKind.UNCUT_SKILL && slot >= SHOP_SUPPORTS)) {
                continue; // 放不下（技能寶石最多兩排）
            }
            shownSupplies.put(slot, supply);
            set(slot, supplyIcon(supply, gold));
        }
        final tw.exilecore.economy.EconomyConfig.Unlock unlock = service.shopUnlock(player);
        set(SLOT_INFO, icon(Material.BOOK, "<white>補給（你有 " + String.format(Locale.ROOT, "%,d", gold) + " 金幣）",
                "<gray>用金幣買藥劑與未切割寶石，價格固定",
                "<gray>打倒劇情區域的 Boss 會解鎖更高等級的寶石",
                "<gray>目前：技能寶石到 <white>Lv." + unlock.skillLevel() + "</white>、輔助寶石到 <white>"
                        + (unlock.supportTier() <= 0 ? "還沒有" : "第 " + unlock.supportTier() + " 階") + "</white>",
                "",
                "<dark_gray>第一排藥劑、中間兩排未切割技能寶石、第四排未切割輔助寶石",
                "<dark_gray>買到的跟掉落的一樣（可以交易）"));
    }

    private ItemStack supplyIcon(final VendorService.Supply supply, final long gold) {
        if (supply.locked() != null) {
            return icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>" + plainName(supply.item()),
                    "<red>" + ItemRenderer.escape(supply.locked()),
                    String.format(Locale.ROOT, "<dark_gray>價格：%,d 金幣", supply.price()));
        }
        final boolean flask = supply.kind() == VendorService.SupplyKind.LIFE_FLASK || supply.kind() == VendorService.SupplyKind.MANA_FLASK;
        final List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(String.format(Locale.ROOT, "%s價格：%,d 金幣", gold >= supply.price() ? "<#f5b942>" : "<red>", supply.price()));
        if (flask) {
            lore.add("<gray>你的等級用得到的最好那一種；藥劑欄空著就直接放進去");
            lore.add("<yellow>左鍵：買一瓶");
        } else {
            lore.add("<yellow>左鍵：買 1 個　<yellow>Shift＋左鍵：買 " + BULK + " 個"
                    + String.format(Locale.ROOT, "（%,d 金幣）", supply.price() * BULK));
        }
        return appendLore(supply.item().clone(), lore.toArray(String[]::new));
    }

    private static String plainName(final ItemStack stack) {
        return ItemRenderer.escape(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(stack.effectiveName()));
    }

    /**
     * 這個基底賭得到的追逐級傳奇，每一件的機率（跟小怪掉落、僥倖石一樣：maps.yml 的 drops.chase_mob_chance，
     * 同一個基底有好幾件時照權重分）；等級不夠的寫幾級起。
     */
    private List<String> chaseLines(final ItemBase base, final int level) {
        final List<UniqueItem> all = service.chaseUniques(base);
        if (all.isEmpty()) {
            return List.of();
        }
        int total = 0;
        for (final UniqueItem unique : all) {
            if (unique.dropLevel() <= level) {
                total += unique.weight();
            }
        }
        final double chance = tw.exilecore.item.ItemGenerator.chaseChance();
        final List<String> lines = new ArrayList<>();
        for (final UniqueItem unique : all) {
            final String name = "<#ff6ad5>✦ 追逐級「" + ItemRenderer.escape(unique.name()) + "」";
            if (unique.dropLevel() > level) {
                lines.add(name + "<dark_gray>：Lv." + unique.dropLevel() + " 起才賭得到");
                continue;
            }
            final double each = total <= 0 ? 0 : chance * unique.weight() / total;
            lines.add(name + "<gray>：" + tinyPercent(each) + (each > 0 ? "<dark_gray>（約 " + oneIn(each) + " 次一件）" : ""));
        }
        return lines;
    }

    /** 很小的百分比（0.0001%）：照位數多留幾位，不會變成 0%。 */
    static String tinyPercent(final double chance) {
        final double percent = chance * 100;
        if (percent <= 0) {
            return "0%";
        }
        if (percent >= 0.1) {
            return String.format(Locale.ROOT, "%.2f%%", percent);
        }
        final int decimals = Math.min(10, (int) Math.ceil(-Math.log10(percent)) + 1);
        return new java.math.BigDecimal(percent).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    /** 1 / chance 寫成「100 萬」「3,000」這種。 */
    static String oneIn(final double chance) {
        final double n = 1 / chance;
        if (n >= 100_000_000) {
            return String.format(Locale.ROOT, "%,.0f 億", n / 100_000_000);
        }
        if (n >= 10_000) {
            return String.format(Locale.ROOT, "%,.0f 萬", n / 10_000);
        }
        return String.format(Locale.ROOT, "%,.0f", n);
    }

    /** 這個基底賭到這個稀有度的機率（uniqueWeight：這個基底的傳奇權重，見 {@link VendorService#uniqueWeight}）。 */
    private String percent(final Rarity rarity, final double uniqueWeight) {
        final double[] odds = service.config().gambleOdds(uniqueWeight);
        final double total = odds[0] + odds[1] + odds[2];
        if (total <= 0) {
            return "0%";
        }
        final double weight = switch (rarity) {
            case MAGIC -> odds[0];
            case RARE -> odds[1];
            case UNIQUE -> odds[2];
            default -> 0;
        };
        final double value = weight * 100.0 / total;
        // 2026-10-31：不到 0.1% 的（地圖 Boss 也會掉的傳奇）照 tinyPercent 寫到看得出來的位數；這次之前四捨五入成「0%」
        if (value > 0 && value < 0.1) {
            return tinyPercent(value / 100.0);
        }
        // 傳奇常常不到 1%：小數才看得出來（0.66%）；整數（2%）與 10% 以上直接寫整數
        if (value <= 0 || value >= 9.95 || Math.abs(value - Math.rint(value)) < 0.05) {
            return Math.round(value) + "%";
        }
        return String.format(Locale.ROOT, value < 1 ? "%.2f%%" : "%.1f%%", value);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_TAB_BUY -> {
                tab = Tab.BUY;
                redraw();
                return;
            }
            case SLOT_TAB_GAMBLE -> {
                tab = Tab.GAMBLE;
                redraw();
                return;
            }
            case SLOT_TAB_SHOP -> {
                tab = Tab.SHOP;
                redraw();
                return;
            }
            case SLOT_REFRESH -> { // 賭博分頁這一格是「上一頁」
                if (tab == Tab.BUY && acceptAction(click)) {
                    result(service.refresh(player), "<green>換了一批新的貨");
                } else if (tab == Tab.GAMBLE && gamblePage > 0) {
                    gamblePage--;
                    redraw();
                }
                return;
            }
            case SLOT_NEXT -> {
                if (tab == Tab.GAMBLE) {
                    gamblePage++; // 畫的時候會夾回最後一頁
                    redraw();
                }
                return;
            }
            case SLOT_SEARCH -> {
                if (tab == Tab.GAMBLE) {
                    searchClick(click, "基底", query, text -> {
                        query = text;
                        gamblePage = 0;
                    });
                    return;
                }
            }
            case SLOT_CATEGORY -> {
                if (tab != Tab.SHOP) {
                    final int direction = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT ? -1 : 1;
                    plugin.characters().setProgress(player, PROGRESS_CATEGORY, category().step(direction).name());
                    gamblePage = 0;
                    redraw();
                }
                return;
            }
            case SLOT_MINE -> {
                if (tab == Tab.GAMBLE) {
                    plugin.characters().setProgress(player, PROGRESS_GAMBLE_MINE, gambleMineOnly() ? "0" : "1");
                    gamblePage = 0;
                    redraw();
                }
                return;
            }
            case SLOT_SELL -> {
                if (plugin.salvage() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>拆解模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    final Tab from = tab;
                    new SalvageMenu(plugin, player, () -> { // 右下角：返回商人（回到原本那一頁）
                        final VendorMenu menu = new VendorMenu(plugin, player, service);
                        menu.tab = from;
                        menu.open();
                    }).open();
                }
                return;
            }
            default -> {
            }
        }
        if (slot >= 45 || !acceptAction(click)) {
            return;
        }
        switch (tab) {
            case GAMBLE -> {
                if (slot < gambleBases.size() && click == ClickType.SHIFT_LEFT) {
                    gambleFill(gambleBases.get(slot));
                } else if (slot < gambleBases.size()) {
                    result(service.gamble(player, gambleBases.get(slot)), "<green>賭到一件未鑑定的" + ItemRenderer.escape(gambleBases.get(slot).name())
                            + "<gray>（拿去鑑定師看看是什麼）");
                }
            }
            case SHOP -> {
                final VendorService.Supply supply = shownSupplies.get(slot);
                if (supply != null) {
                    final int amount = click == ClickType.SHIFT_LEFT ? BULK : 1;
                    result(service.buySupply(player, supply.kind(), supply.level(), amount), "<green>買好了"
                            + (supply.kind() == VendorService.SupplyKind.UNCUT_SKILL || supply.kind() == VendorService.SupplyKind.UNCUT_SUPPORT
                            ? "，放進背包（右鍵寶石使用）" : ""));
                }
            }
            default -> {
                if (shownStock != null && slot < shownIndices.size()) {
                    result(service.buy(player, shownStock, shownIndices.get(slot)), "<green>買好了，放進背包");
                }
            }
        }
    }

    /** 一次最多賭幾件（背包 36 格；防手滑）。 */
    private static final int GAMBLE_FILL_MAX = 36;

    /** Shift＋左鍵（2026-10）：一直賭到背包上面三排滿、金幣不夠或最多 {@value #GAMBLE_FILL_MAX} 件。 */
    private void gambleFill(final ItemBase base) {
        int count = 0;
        String problem = null;
        final List<tw.exilecore.economy.TradeLogRepository.Entry> batch = new ArrayList<>(); // 2026-10：一排只存一次檔
        while (count < GAMBLE_FILL_MAX) {
            // 2026-10：只填背包上面三排（9–35 格），不放快捷列、不放隨身背包
            int free = -1;
            for (int slot = 9; slot < 36; slot++) {
                final ItemStack there = player.getInventory().getItem(slot);
                if (there == null || there.isEmpty()) {
                    free = slot;
                    break;
                }
            }
            if (free < 0) {
                problem = count == 0 ? "背包上面三排沒有空格" : null;
                break;
            }
            problem = service.gamble(player, base, free, 0, batch);
            if (problem != null) {
                break;
            }
            count++;
        }
        service.commitBatch(player, batch, "賭博");
        if (count == 0) {
            result(problem == null ? "背包上面三排沒有空格" : problem, null);
            return;
        }
        result(null, "<green>賭了 " + count + " 件未鑑定的" + ItemRenderer.escape(base.name())
                + (problem != null ? "<gray>（停下來：" + ItemRenderer.escape(problem) + "）" : "<gray>（背包滿了）"));
    }

    private void result(final String problem, final String success) {
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        } else {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(success));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        }
        redraw();
    }
}
