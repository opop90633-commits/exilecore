package tw.exilecore.salvage;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 拆解台（拆解師 NPC）：上面 4 列是你的背包（前 3 列＝背包、第 4 列＝快捷列），點裝備選起來，
 * 再按「拆解」把選中的全部拆成金幣與品質通貨；地圖也能拆（金幣＋照品質給星盤刻刀），寶石（含未切割）與藥劑也能拆，只給金幣。身上穿的、副手與飾品欄位不會出現在這裡。
 * 第 5 列是快速選擇（稀有度、未鑑定、比你低幾級、不是你這類的武器／這系的防具、寶石／藥劑／護符、全部），
 * 第 6 列是拆解前的清單（總共拿到多少金幣與通貨、有沒有該再想一下的東西）、拆解、反選、清除。
 * 傳奇（含未鑑定的）不會被快速選擇選到，拆解前還要 Shift＋左鍵確認；珠寶、地圖「全部可以拆的」會選（2026-10）。
 * 稀有不分等級，快速選擇都會選到（想留的先右鍵鎖定）。
 * 藥劑欄（快捷欄 1、2，照 flasks.yml）裡的藥劑不能拆：不會被點選、也不會被快速選擇選到。
 * 拆解前會再核對每一格還是同一件物品（裝備看物品 UUID，寶石與藥劑看內容與數量），中途換過位置的不會被拆。
 * 左下的「傳奇三換一」打開 {@link UniqueRecipeMenu}（重複的傳奇三件換一件；salvage.yml 的 unique_recipe）。
 */
public final class SalvageMenu extends Menu {

    private static final int MIRROR_SLOTS = 36;
    private static final int SLOT_SELECT_NORMAL = 36;
    private static final int SLOT_SELECT_MAGIC = 37;
    private static final int SLOT_SELECT_RARE = 38;
    private static final int SLOT_SELECT_UNIDENTIFIED = 39;
    private static final int SLOT_SELECT_LOW_LEVEL = 40;
    private static final int SLOT_SELECT_OTHER_WEAPONS = 41;
    private static final int SLOT_SELECT_OTHER_ARMOUR = 42;
    private static final int SLOT_SELECT_GEMS_FLASKS = 43;
    private static final int SLOT_SELECT_ALL = 44;
    private static final int SLOT_SUMMARY = 45;
    private static final int SLOT_RECIPE = 46;
    private static final int SLOT_INVERT = 47;
    private static final int SLOT_CONFIRM = 49;
    private static final int SLOT_CLEAR = 51;
    private static final int SLOT_LOCK_HELP = 52;
    private static final int SLOT_CLOSE = 53;
    /** 「比你低幾級」的選項。 */
    private static final int[] LEVEL_GAPS = {10, 20, 30};

    /** 背包格 → 選中時那件物品的身分（裝備 = UUID；寶石、藥劑 = 內容＋數量，見 {@link #identity}）。 */
    private final Map<Integer, String> selected = new LinkedHashMap<>();
    private final SalvageConfig config;
    /** 「比你低幾級」目前選的差距。 */
    private int levelGap = LEVEL_GAPS[1];

    /** 從商人那裡打開的（2026-10）：右下角是「返回商人」；null = 回選單。 */
    private final Runnable backToVendor;

    public SalvageMenu(final ExileCore plugin, final Player player) {
        this(plugin, player, null);
    }

    public SalvageMenu(final ExileCore plugin, final Player player, final Runnable backToVendor) {
        super(plugin, player, 6, "<dark_gray>拆解台");
        this.config = plugin.salvage();
        this.backToVendor = backToVendor;
    }

    @Override
    protected ItemStack closeButton() {
        if (backToVendor == null) {
            return super.closeButton();
        }
        return uiIcon("nav_back", Material.ARROW, "<white>← 返回商人", "<gray>回到商人的畫面", "<yellow>左鍵：返回");
    }

    @Override
    protected void goBack() {
        if (backToVendor == null) {
            super.goBack();
        } else {
            backToVendor.run();
        }
    }

    /** 選單的格子 → 背包格（0–26 對背包 9–35，27–35 對快捷列 0–8）。 */
    static int inventorySlot(final int menuSlot) {
        return menuSlot < 27 ? menuSlot + 9 : menuSlot - 27;
    }

    @Override
    protected void draw() {
        final PlayerInventory inventory = player.getInventory();
        // 已經不在原位的選擇清掉
        final Iterator<Map.Entry<Integer, String>> iterator = selected.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Integer, String> entry = iterator.next();
            if (!entry.getValue().equals(identity(entry.getKey(), inventory.getItem(entry.getKey())))) {
                iterator.remove();
            }
        }
        for (int slot = 0; slot < MIRROR_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(inventorySlot(slot));
            if (stack == null || stack.isEmpty()) {
                set(slot, null);
                continue;
            }
            if (protectedFlask(inventorySlot(slot), stack)) {
                final ItemStack copy = stack.clone();
                final int hotbar = inventorySlot(slot) + 1;
                copy.editMeta(meta -> {
                    final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(ItemRenderer.line(""));
                    lore.add(ItemRenderer.line("<#ff7777>🧪 藥劑欄（快捷欄 " + hotbar + "）：不會被拆"));
                    lore.add(ItemRenderer.line("<dark_gray>要拆的話先移到別格"));
                    meta.lore(lore);
                });
                set(slot, copy);
                continue;
            }
            final Optional<ItemData> data = plugin.items().read(stack);
            if (data.isPresent() && data.get().locked()) {
                final ItemStack copy = stack.clone();
                copy.editMeta(meta -> {
                    final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(ItemRenderer.line(""));
                    lore.add(ItemRenderer.line("<#ffd27f>🔒 已鎖定：不會被拆解"));
                    lore.add(ItemRenderer.line("<yellow>右鍵：解除鎖定"));
                    meta.lore(lore);
                });
                set(slot, copy);
                continue;
            }
            if (data.isPresent()) {
                set(slot, preview(stack, data.get(), selected.containsKey(inventorySlot(slot))));
                continue;
            }
            final long gold = otherGold(stack);
            if (gold > 0) {
                set(slot, preview(stack, gold, selected.containsKey(inventorySlot(slot))));
                continue;
            }
            set(slot, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>不能拆解", "<dark_gray>裝備、地圖、寶石、藥劑、護符、通貨可以拆"));
        }
        for (int slot = MIRROR_SLOTS; slot < 54; slot++) {
            set(slot, filler());
        }
        drawQuickSelects();
        set(SLOT_SUMMARY, summaryIcon());
        if (config.recipe().enabled()) {
            set(SLOT_RECIPE, recipeIcon());
        }
        set(SLOT_INVERT, icon(Material.HOPPER, "<white>反選", "<gray>選的變成不選、沒選的變成選（只看可以拆的）",
                "<dark_gray>傳奇、藥劑欄的藥劑不會被反選選到", "", "<yellow>左鍵：反選"));
        set(SLOT_CONFIRM, confirmIcon());
        set(SLOT_CLEAR, icon(Material.MILK_BUCKET, "<gray>清除選擇", "<yellow>左鍵：全部取消"));
        set(SLOT_LOCK_HELP, icon(Material.TRIPWIRE_HOOK, "<#ffd27f>🔒 鎖定", "<gray>右鍵任何一件裝備＝鎖定：不會被拆解、上架、交易",
                "<gray>再右鍵一次解除；也可以用 /lock", "<dark_gray>想留的裝備先鎖起來，之後用快速選擇就不怕拆錯"));
        set(SLOT_CLOSE, closeButton());
    }

    /** 「傳奇三換一」按鈕：背包裡有幾件可以放。 */
    private ItemStack recipeIcon() {
        final UniqueRecipe.Settings recipe = config.recipe();
        int eligible = 0;
        for (int slot = 0; slot < MIRROR_SLOTS; slot++) {
            final Optional<ItemData> data = plugin.items().read(player.getInventory().getItem(slot));
            if (data.isPresent() && data.get().rarity() == Rarity.UNIQUE
                    && UniqueRecipe.problem(data.get(), plugin.items().repository().unique(data.get().unique())) == null) {
                eligible++;
            }
        }
        return icon(Material.SMITHING_TABLE, "<#af6025>" + recipe.title(),
                "<gray>放 " + recipe.inputs() + " 件傳奇，換一件隨機傳奇（重複的、用不到的）",
                recipe.excludeInputs() ? "<dark_gray>不會換到放進去的那幾種" : "<dark_gray>換到哪一件是隨機的",
                "<dark_gray>追逐級、極境專屬不能放；有極低的機率換到追逐級（跟小怪掉的一樣）",
                "",
                eligible >= recipe.inputs() ? "<gray>背包裡可以放的傳奇：<white>" + eligible + "<gray> 件"
                        : "<gray>背包裡可以放的傳奇：<white>" + eligible + "<gray> 件<dark_gray>（要 " + recipe.inputs() + " 件）",
                "",
                "<yellow>左鍵：打開");
    }

    /** 第 5 列：快速選擇。每顆都寫著會選到幾件、現在是不是已經全選了（再按一次取消）。 */
    private void drawQuickSelects() {
        final int level = plugin.players().get(player).level();
        quick(SLOT_SELECT_NORMAL, Material.WHITE_DYE, "<white>普通裝備", matches(Pick.NORMAL), "", null);
        quick(SLOT_SELECT_MAGIC, Material.BLUE_DYE, "<#8888ff>魔法裝備", matches(Pick.MAGIC), "", null);
        quick(SLOT_SELECT_RARE, Material.YELLOW_DYE, "<#ffff77>稀有裝備", matches(Pick.RARE),
                "<dark_gray>不分等級，背包裡的稀有都選（想留的先右鍵鎖定）", null);
        quick(SLOT_SELECT_UNIDENTIFIED, Material.SPYGLASS, "<#ff5555>未鑑定的裝備", matches(Pick.UNIDENTIFIED),
                "<dark_gray>未鑑定的裝備、珠寶都選（未鑑定的傳奇不選，要拆請一件一件點）", null);
        quick(SLOT_SELECT_LOW_LEVEL, Material.EXPERIENCE_BOTTLE, "<white>比你低 " + levelGap + " 級以上的裝備", matches(Pick.LOW_LEVEL),
                "<dark_gray>你 " + level + " 級 → 物品等級 " + Math.max(0, level - levelGap - 1) + " 以下的普通、魔法、稀有",
                "<yellow>右鍵：改成 " + LEVEL_GAPS[(index(levelGap) + 1) % LEVEL_GAPS.length] + " 級");
        final String weapon = weaponClassName();
        quick(SLOT_SELECT_OTHER_WEAPONS, Material.BOW, "<white>不是你這類的武器", matches(Pick.OTHER_WEAPONS),
                weapon == null ? "<dark_gray>你沒裝武器：不知道哪種是你的，先在角色面板裝上主武器" : "<dark_gray>你的武器：<white>" + weapon + "</white>，其他種類的武器都選（雙手武器的人連盾一起）", null);
        final List<String> families = armourFamilyNames();
        quick(SLOT_SELECT_OTHER_ARMOUR, Material.LEATHER_CHESTPLATE, "<white>不是你這系的防具", matches(Pick.OTHER_ARMOUR),
                families.isEmpty() ? "<dark_gray>你沒穿防具：不知道哪系是你的" : "<dark_gray>你身上：<white>" + String.join("、", families) + "</white>，其他系的防具與盾都選", null);
        quick(SLOT_SELECT_GEMS_FLASKS, Material.AMETHYST_SHARD, "<#7fd4ff>寶石、藥劑、護符", matches(Pick.GEMS_FLASKS), "<dark_gray>只給金幣", null);
        quick(SLOT_SELECT_ALL, Material.CHEST, "<#f5b942>全部可以拆的", matches(Pick.ALL),
                "<dark_gray>含珠寶、地圖；傳奇、鎖定的、藥劑欄的藥劑不算", null);
    }

    private static int index(final int gap) {
        for (int i = 0; i < LEVEL_GAPS.length; i++) {
            if (LEVEL_GAPS[i] == gap) {
                return i;
            }
        }
        return 0;
    }

    private void quick(final int slot, final Material material, final String name, final Map<Integer, String> matching, final String note,
                       final String extra) {
        final boolean all = !matching.isEmpty() && selected.entrySet().containsAll(matching.entrySet());
        final List<String> lore = new ArrayList<>();
        lore.add(matching.isEmpty() ? "<dark_gray>背包裡沒有這種" : all ? "<green>✔ 這 " + matching.size() + " 件都選了" : "<gray>會選到 <white>" + matching.size() + "<gray> 件");
        if (!note.isEmpty()) {
            lore.add(note);
        }
        lore.add("");
        lore.add(matching.isEmpty() ? "<dark_gray>—" : all ? "<yellow>左鍵：取消這些" : "<yellow>左鍵：選起來（再按一次取消）");
        if (extra != null) {
            lore.add(extra);
        }
        final ItemStack icon = icon(material, name, lore);
        set(slot, all ? glowing(icon) : icon);
    }

    /** 快速選擇的種類。 */
    private enum Pick { NORMAL, MAGIC, RARE, UNIDENTIFIED, LOW_LEVEL, OTHER_WEAPONS, OTHER_ARMOUR, GEMS_FLASKS, ALL }

    /**
     * 背包裡符合這種快速選擇、而且可以直接拆（不是傳奇、鎖定、藥劑欄的藥劑；地圖只有「全部」選）的格子。
     * 傳奇（含未鑑定的）一律不選。
     */
    private Map<Integer, String> matches(final Pick pick) {
        final PlayerInventory inventory = player.getInventory();
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final int level = state.level();
        final Set<String> weaponTags = state.weaponTags();
        final Set<String> families = armourFamilies();
        final Map<Integer, String> matching = new LinkedHashMap<>();
        for (int slot = 0; slot < MIRROR_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            final String identity = identity(slot, stack);
            if (identity == null) {
                continue;
            }
            final Optional<ItemData> read = plugin.items().read(stack);
            if (read.isEmpty()) {
                if (identity.startsWith(CURRENCY_ID)) {
                    continue; // 通貨：要一疊一疊點（免得把要用的通貨拆掉）
                }
                if (pick == Pick.GEMS_FLASKS || pick == Pick.ALL) {
                    matching.put(slot, identity);
                }
                continue;
            }
            final ItemData data = read.get();
            if (needsCare(data)) {
                continue; // 傳奇：要一件一件點（2026-10：「未鑑定」也不選未鑑定的傳奇）
            }
            if (isMap(data) && pick != Pick.ALL) {
                continue; // 地圖只有「全部可以拆的」會選（2026-10），稀有度那幾顆只選裝備
            }
            final ItemBase base = plugin.items().repository().base(data.base());
            final Set<String> tags = base == null ? Set.of() : base.tags();
            final boolean ok = switch (pick) {
                case NORMAL -> data.rarity() == Rarity.NORMAL;
                case MAGIC -> data.rarity() == Rarity.MAGIC;
                case RARE -> data.rarity() == Rarity.RARE;
                case UNIDENTIFIED -> !data.identified();
                case LOW_LEVEL -> data.ilvl() < level - levelGap;
                case OTHER_WEAPONS -> !weaponTags.isEmpty() && otherWeapon(tags, weaponTags);
                case OTHER_ARMOUR -> !families.isEmpty() && otherArmour(tags, families);
                case GEMS_FLASKS -> false;
                case ALL -> true;
            };
            if (ok) {
                matching.put(slot, identity);
            }
        }
        return matching;
    }

    /** 要特別想一下的：傳奇（快速選擇不選、拆解前要確認）。2026-10：珠寶、地圖當成一般的（「全部可以拆的」會選）。 */
    private boolean needsCare(final ItemData data) {
        return data.rarity() == Rarity.UNIQUE; // 2026-10：地圖不用再確認（「全部可以拆的」會選）
    }

    /** 這一格是藥劑欄（flasks.yml 的快捷欄，預設 1、2）而且放的是藥劑：保護起來不能拆。 */
    private boolean protectedFlask(final int inventorySlot, final ItemStack stack) {
        return plugin.flasks() != null && plugin.flasks().config().kindAt(inventorySlot) != null && plugin.flasks().read(stack).isPresent();
    }

    private static boolean otherWeapon(final Set<String> tags, final Set<String> mine) {
        if (tags.contains("weapon")) {
            // 2026-10：跟掉落過濾器同一套：這把的武器種類有任何一個不是手上的，就算別種（權杖同時是錘和權杖）
            for (final String tag : tags) {
                if (tw.exilecore.loot.LootFilter.WEAPON_CLASSES.contains(tag) && !mine.contains(tag)) {
                    return true;
                }
            }
            return false;
        }
        return tags.contains("shield") && mine.contains("two_hand");
    }

    private static boolean otherArmour(final Set<String> tags, final Set<String> mine) {
        if (!tags.contains("armour") && !tags.contains("shield")) {
            return false;
        }
        boolean family = false;
        for (final String tag : tags) {
            if (tw.exilecore.loot.LootFilter.ARMOUR_FAMILIES.contains(tag)) {
                family = true;
                if (mine.contains(tag)) {
                    return false;
                }
            }
        }
        return family;
    }

    /** 身上防具的系。 */
    private Set<String> armourFamilies() {
        final Set<String> families = new java.util.HashSet<>();
        for (final ItemStack worn : tw.exilecore.character.Gear.armour(plugin.players().get(player))) {
            if (worn == null || worn.isEmpty()) {
                continue;
            }
            final Optional<ItemData> data = plugin.items().read(worn);
            final ItemBase base = data.isEmpty() ? null : plugin.items().repository().base(data.get().base());
            if (base != null) {
                for (final String tag : base.tags()) {
                    if (tw.exilecore.loot.LootFilter.ARMOUR_FAMILIES.contains(tag)) {
                        families.add(tag);
                    }
                }
            }
        }
        return families;
    }

    private List<String> armourFamilyNames() {
        final List<String> names = new ArrayList<>();
        final Set<String> families = armourFamilies();
        if (families.contains("str_armour")) {
            names.add("力量系");
        }
        if (families.contains("dex_armour")) {
            names.add("敏捷系");
        }
        if (families.contains("int_armour")) {
            names.add("智慧系");
        }
        return names;
    }

    /** 手上武器的種類名稱（沒拿 null）。 */
    private String weaponClassName() {
        final Set<String> tags = plugin.players().get(player).weaponTags();
        final String primary = tw.exilecore.loot.LootFilter.primaryWeaponClass(tags);
        for (final String tag : primary == null ? List.<String>of() : List.of(primary)) {
            {
                final String name = switch (tag) {
                    case "sword" -> "劍";
                    case "axe" -> "斧";
                    case "mace" -> "錘";
                    case "dagger" -> "匕首";
                    case "claw" -> "爪";
                    case "wand" -> "法杖";
                    case "staff" -> "長杖";
                    case "bow" -> "弓";
                    case "sceptre" -> "權杖";
                    case "crossbow" -> "弩";
                    case "quarterstaff" -> "長棍";
                    case "spear" -> "矛";
                    case "talisman" -> "獸魂符";
                    default -> tag;
                };
                return name + (tags.contains("two_hand") ? "（雙手）" : "");
            }
        }
        return null;
    }

    /**
     * 這一格物品的身分（換過東西就對不上）：裝備是物品 UUID；寶石是內容＋數量；藥劑是基底＋充能；
     * 其他不能拆的（含鎖定的、藥劑欄裡的藥劑）是 null。
     */
    private String identity(final int inventorySlot, final ItemStack stack) {
        if (stack == null || stack.isEmpty() || protectedFlask(inventorySlot, stack)) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            return data.get().locked() ? null : data.get().uuid();
        }
        if (plugin.skills() != null) {
            final Optional<tw.exilecore.skill.GemData> gem = plugin.skills().gems().read(stack);
            if (gem.isPresent()) {
                return "gem:" + gem.get().encode() + ":" + stack.getAmount();
            }
        }
        if (plugin.flasks() != null) {
            final Optional<tw.exilecore.flask.FlaskService.FlaskData> flask = plugin.flasks().read(stack);
            if (flask.isPresent()) {
                return "flask:" + flask.get().base() + ":" + flask.get().charges();
            }
            if (plugin.flasks().charms() != null && plugin.flasks().charms().isCharm(stack)) {
                return "charm:" + plugin.flasks().charms().name(stack) + ":" + stack.hashCode(); // 2026-10：護符可以拆
            }
        }
        // 通貨（2026-10）：整疊拆成金幣。沒有參考價值的（鑑定卷軸這種跟 NPC 免費領的）照樣能拆，但是 0 金幣（SalvageConfig.goldForCurrency）
        final Optional<CurrencyType> currency = plugin.currencies().read(stack);
        if (currency.isPresent()) {
            return CURRENCY_ID + currency.get().id() + ":" + stack.getAmount();
        }
        return null;
    }

    /** 通貨那一格的身分開頭（快速選擇、反選不會選到通貨，要一疊一疊點）。 */
    private static final String CURRENCY_ID = "cur:";

    /** 寶石（整疊）或藥劑拆解可得的金幣；不是這兩種回傳 0。 */
    private long otherGold(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (plugin.skills() != null) {
            final Optional<tw.exilecore.skill.GemData> gem = plugin.skills().gems().read(stack);
            if (gem.isPresent()) {
                final tw.exilecore.skill.GemData.Kind kind = gem.get().kind();
                final boolean support = kind == tw.exilecore.skill.GemData.Kind.SUPPORT || kind == tw.exilecore.skill.GemData.Kind.UNCUT_SUPPORT;
                if (plugin.loot() != null && plugin.monsterModule() != null) {
                    // 2026-10：跟被掉落過濾換的金幣一樣（照角色等級的怪物算，跟過濾器上寫的一樣）
                    return plugin.loot().goldEstimate(player, support ? tw.exilecore.monster.DropTable.Kind.SUPPORT_GEM
                            : tw.exilecore.monster.DropTable.Kind.SKILL_GEM, null, 0) * stack.getAmount();
                }
                return config.goldForGem(support, gem.get().level()) * stack.getAmount();
            }
        }
        if (plugin.flasks() != null) {
            final Optional<tw.exilecore.flask.FlaskService.FlaskData> flask = plugin.flasks().read(stack);
            if (flask.isPresent()) {
                final tw.exilecore.flask.FlaskBase base = plugin.flasks().config().base(flask.get().base());
                if (plugin.loot() != null && plugin.monsterModule() != null) {
                    return plugin.loot().goldEstimate(player, tw.exilecore.monster.DropTable.Kind.FLASK, null, 0); // 2026-10：跟過濾一樣
                }
                return config.goldForFlask(base == null ? 1 : base.level());
            }
            if (plugin.flasks().charms() != null && plugin.flasks().charms().isCharm(stack)) {
                if (plugin.loot() != null && plugin.monsterModule() != null) {
                    return plugin.loot().goldEstimate(player, tw.exilecore.monster.DropTable.Kind.FLASK, null, 0);
                }
                return config.goldForFlask(plugin.flasks().charms().level(stack));
            }
        }
        final Optional<CurrencyType> currency = plugin.currencies().read(stack);
        if (currency.isPresent()) {
            final long value = plugin.economy() == null ? 0 : plugin.economy().config().value(currency.get().id());
            return config.goldForCurrency(value, stack.getAmount());
        }
        return 0;
    }

    /** 寶石、藥劑的預覽：只有金幣。 */
    private ItemStack preview(final ItemStack stack, final long gold, final boolean chosen) {
        final ItemStack copy = stack.clone();
        final List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format(Locale.ROOT, "<gray>拆解可得：<#f5b942>%,d 金幣", gold)
                + (stack.getAmount() > 1 ? " <dark_gray>（整疊 " + stack.getAmount() + " 個）" : ""));
        lines.add(chosen ? "<green>✔ 已選擇（再點一次取消）" : "<yellow>左鍵：選擇拆解");
        return decorate(copy, lines, chosen);
    }

    private ItemStack preview(final ItemStack stack, final ItemData data, final boolean chosen) {
        final ItemStack copy = stack.clone();
        final ItemBase base = plugin.items().repository().base(data.base());
        final List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format(Locale.ROOT, "<gray>拆解可得：<#f5b942>%,d 金幣", config.gold(base, data)));
        final String currencyId = data.starter() ? null : config.currencyFor(base);
        final CurrencyType currency = currencyId == null ? null : plugin.currencies().repository().get(currencyId);
        if (data.starter()) {
            lines.add("<dark_gray>　　　　新手裝備拆解沒有東西");
        }
        if (currency != null) {
            final int guaranteed = config.guaranteedCurrency(data);
            final int chance = (int) Math.round(config.currencyChance(data.rarity()) * 100);
            lines.add("<gray>　　　　" + ItemRenderer.escape(currency.name())
                    + (guaranteed > 0 ? " ×" + guaranteed + "，" : " ") + chance + "% 機率再 1 個");
        }
        final String care = careNote(data);
        if (care != null) {
            lines.add(care);
        }
        if (data.rarity() == Rarity.UNIQUE && config.recipe().enabled()) {
            lines.add("<dark_gray>重複的傳奇可以拿去左下的「" + config.recipe().title() + "」");
        }
        lines.add(chosen ? "<green>✔ 已選擇（再點一次取消）" : "<yellow>左鍵：選擇拆解");
        lines.add("<dark_gray>右鍵：鎖定（之後不會被誤拆）");
        return decorate(copy, lines, chosen);
    }

    /** 為什麼這件快速選擇不會選、拆解前要確認。 */
    private String careNote(final ItemData data) {
        if (data.rarity() == Rarity.UNIQUE) {
            return "<#af6025>⚠ 傳奇：快速選擇不會選它，拆解前要再確認";
        }
        return null;
    }

    /** 說明加在原本的說明後面；選起來的加外框（沒資源包就換成綠色玻璃片）。 */
    private ItemStack decorate(final ItemStack copy, final List<String> lines, final boolean chosen) {
        copy.editMeta(meta -> {
            final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (final String line : lines) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(lore);
            if (chosen) {
                final Component name = meta.hasDisplayName() ? meta.displayName() : null;
                if (name != null) {
                    meta.displayName(ItemRenderer.line("<green>✔ ").append(name));
                }
            }
        });
        if (!chosen) {
            return copy;
        }
        if (frames(copy)) {
            return highlight(copy); // 選起來的：外框
        }
        // 畫不出外框（沒套用資源包）：換成綠色玻璃片（名稱、說明照舊；傳奇本來就會發光，只靠發光分不出選了沒）
        final ItemStack pane = ItemStack.of(Material.LIME_STAINED_GLASS_PANE);
        final ItemMeta source = copy.getItemMeta();
        pane.editMeta(meta -> {
            if (source.hasDisplayName()) {
                meta.displayName(source.displayName());
            }
            meta.lore(source.lore());
        });
        return pane;
    }

    /** 拆解前的清單：總共幾件、會拿到多少金幣與通貨、有幾件要特別確認。 */
    private final class Summary {
        int count;
        long gold;
        int care;
        final Map<String, Integer> guaranteed = new LinkedHashMap<>();
        final Map<String, Integer> chances = new LinkedHashMap<>();
        final Map<Rarity, Integer> byRarity = new java.util.EnumMap<>(Rarity.class);
        int others;

        Summary() {
            final PlayerInventory inventory = player.getInventory();
            for (final Map.Entry<Integer, String> entry : selected.entrySet()) {
                final ItemStack stack = inventory.getItem(entry.getKey());
                if (!entry.getValue().equals(identity(entry.getKey(), stack))) {
                    continue;
                }
                count++;
                final Optional<ItemData> read = plugin.items().read(stack);
                if (read.isEmpty()) {
                    gold += otherGold(stack);
                    others++;
                    continue;
                }
                final ItemData data = read.get();
                final ItemBase base = plugin.items().repository().base(data.base());
                gold += config.gold(base, data);
                byRarity.merge(data.rarity(), 1, Integer::sum);
                if (needsCare(data)) {
                    care++;
                }
                final String currencyId = data.starter() ? null : config.currencyFor(base);
                if (currencyId != null) {
                    final int sure = config.guaranteedCurrency(data);
                    if (sure > 0) {
                        guaranteed.merge(currencyId, sure, Integer::sum);
                    }
                    if (config.currencyChance(data.rarity()) > 0) {
                        chances.merge(currencyId, 1, Integer::sum);
                    }
                }
            }
        }

        List<String> lines() {
            final List<String> lines = new ArrayList<>();
            final List<String> kinds = new ArrayList<>();
            for (final Rarity rarity : Rarity.values()) {
                final Integer n = byRarity.get(rarity);
                if (n != null) {
                    kinds.add("<" + rarity.color() + ">" + rarity.displayName() + " " + n);
                }
            }
            if (others > 0) {
                kinds.add("<#7fd4ff>寶石／藥劑 " + others);
            }
            lines.add("<gray>選了 <white>" + count + "<gray> 件" + (kinds.isEmpty() ? "" : "：" + String.join("<gray>、", kinds)));
            lines.add(String.format(Locale.ROOT, "<gray>會拿到：<#f5b942>%,d 金幣", gold));
            final java.util.Set<String> ids = new java.util.LinkedHashSet<>(guaranteed.keySet());
            ids.addAll(chances.keySet());
            for (final String id : ids) {
                final CurrencyType type = plugin.currencies().repository().get(id);
                final String name = type == null ? id : type.name();
                final int sure = guaranteed.getOrDefault(id, 0);
                final int maybe = chances.getOrDefault(id, 0);
                lines.add("<gray>　" + ItemRenderer.escape(name) + (sure > 0 ? " <white>×" + sure : "")
                        + (maybe > 0 ? " <dark_gray>（另外 " + maybe + " 件各有機率再 1 個）" : ""));
            }
            if (care > 0) {
                lines.add("<red>⚠ 有 " + care + " 件傳奇：拆解要 Shift＋左鍵確認");
            }
            return lines;
        }
    }

    private ItemStack summaryIcon() {
        if (selected.isEmpty()) {
            final List<String> help = new ArrayList<>(List.of(
                    "<gray>1. 點上面的裝備，或用下面一排快速選擇",
                    "<gray>2. 看這裡會拿到多少、有沒有不該拆的",
                    "<gray>3. 按右邊的「拆解」",
                    "",
                    "<gray>拆解＝金幣＋品質通貨（武器礪鋒石、防具甲冑殘片、地圖星盤刻刀）",
                    "<gray>寶石、藥劑、護符只有金幣；飾品與珠寶只有金幣",
                    "<dark_gray>身上穿的、副手、飾品欄位不會出現在這裡；想留的先右鍵鎖定",
                    "<dark_gray>藥劑欄（快捷欄 1、2）的藥劑不會被拆"));
            if (config.recipe().enabled()) {
                help.add("<#af6025>重複的傳奇：右邊的「" + config.recipe().title() + "」");
            }
            return icon(Material.BOOK, "<white>怎麼用", help);
        }
        final List<String> lines = new Summary().lines();
        lines.add("");
        lines.add("<dark_gray>滑到每一件上可以看它各自拆到什麼");
        return icon(Material.BOOK, "<white>拆解清單", lines);
    }

    private ItemStack confirmIcon() {
        if (selected.isEmpty()) {
            return icon(Material.ANVIL, "<gray>拆解", "<gray>先點上面的裝備（或下面的快速選擇）選要拆的",
                    "<dark_gray>背包裡的裝備才會出現（身上穿的不會）");
        }
        final Summary summary = new Summary();
        final List<String> lore = new ArrayList<>();
        lore.add(String.format(Locale.ROOT, "<gray>金幣 <#f5b942>%,d</#f5b942>＋品質通貨（左邊的清單有細項）", summary.gold));
        lore.add("<red>拆掉就沒了，確定再按");
        lore.add("");
        if (summary.care > 0) {
            lore.add("<red>⚠ 含 " + summary.care + " 件傳奇");
            lore.add("<yellow>Shift＋左鍵：確定拆解");
        } else {
            lore.add("<yellow>左鍵：拆解");
        }
        return glowing(icon(Material.ANVIL, "<#f5b942>拆解 " + summary.count + " 件", lore));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < MIRROR_SLOTS) {
            if (click.isRightClick()) {
                toggleLock(inventorySlot(slot));
            } else {
                toggle(inventorySlot(slot));
            }
        } else if (slot == SLOT_SELECT_NORMAL) {
            quickSelect(Pick.NORMAL);
        } else if (slot == SLOT_SELECT_MAGIC) {
            quickSelect(Pick.MAGIC);
        } else if (slot == SLOT_SELECT_RARE) {
            quickSelect(Pick.RARE);
        } else if (slot == SLOT_SELECT_UNIDENTIFIED) {
            quickSelect(Pick.UNIDENTIFIED);
        } else if (slot == SLOT_SELECT_LOW_LEVEL) {
            if (click.isRightClick()) {
                levelGap = LEVEL_GAPS[(index(levelGap) + 1) % LEVEL_GAPS.length];
            } else {
                quickSelect(Pick.LOW_LEVEL);
            }
        } else if (slot == SLOT_SELECT_OTHER_WEAPONS) {
            quickSelect(Pick.OTHER_WEAPONS);
        } else if (slot == SLOT_SELECT_OTHER_ARMOUR) {
            quickSelect(Pick.OTHER_ARMOUR);
        } else if (slot == SLOT_SELECT_GEMS_FLASKS) {
            quickSelect(Pick.GEMS_FLASKS);
        } else if (slot == SLOT_SELECT_ALL) {
            quickSelect(Pick.ALL);
        } else if (slot == SLOT_RECIPE && config.recipe().enabled()) {
            new UniqueRecipeMenu(plugin, player).open();
            return;
        } else if (slot == SLOT_INVERT) {
            invert();
        } else if (slot == SLOT_CONFIRM) {
            if (!selected.isEmpty() && new Summary().care > 0 && click != ClickType.SHIFT_LEFT) {
                player.sendActionBar(plugin.text().parse("<red>選的裡面有傳奇：要 Shift＋左鍵才會拆"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            salvage();
        } else if (slot == SLOT_CLEAR) {
            selected.clear();
        } else if (slot == SLOT_CLOSE) {
            goBack();
            return;
        } else {
            return;
        }
        redraw();
    }

    /** 快速選擇：全部選起來；已經全選了就全部取消。 */
    private void quickSelect(final Pick pick) {
        final Map<Integer, String> matching = matches(pick);
        if (matching.isEmpty()) {
            player.sendActionBar(plugin.text().parse("<gray>背包裡沒有這種可以拆的"));
            return;
        }
        if (selected.entrySet().containsAll(matching.entrySet())) {
            for (final Integer slot : matching.keySet()) {
                selected.remove(slot);
            }
        } else {
            selected.putAll(matching);
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    /** 反選（只看可以直接拆的）。 */
    private void invert() {
        final Map<Integer, String> all = matches(Pick.ALL);
        final Map<Integer, String> next = new LinkedHashMap<>();
        for (final Map.Entry<Integer, String> entry : all.entrySet()) {
            if (!selected.containsKey(entry.getKey())) {
                next.put(entry.getKey(), entry.getValue());
            }
        }
        // 要確認的那些（傳奇、珠寶…）如果本來就選著，留著
        for (final Map.Entry<Integer, String> entry : selected.entrySet()) {
            final Optional<ItemData> data = plugin.items().read(player.getInventory().getItem(entry.getKey()));
            if (data.isPresent() && needsCare(data.get())) {
                next.put(entry.getKey(), entry.getValue());
            }
        }
        selected.clear();
        selected.putAll(next);
    }

    /** 右鍵：鎖定或解除鎖定一件裝備（鎖著的不會被拆）。 */
    private void toggleLock(final int inventorySlot) {
        final ItemStack stack = player.getInventory().getItem(inventorySlot);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return;
        }
        final boolean locked = !data.get().locked();
        data.get().setLocked(locked);
        plugin.items().update(stack, data.get());
        if (locked) {
            selected.remove(inventorySlot);
        }
        player.sendActionBar(plugin.text().parse(locked ? "<#ffd27f>🔒 已鎖定，拆解台不會收它" : "<gray>已解除鎖定"));
        player.playSound(player.getLocation(), locked ? Sound.BLOCK_CHAIN_PLACE : Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.2f);
    }

    private void toggle(final int inventorySlot) {
        if (selected.remove(inventorySlot) != null) {
            return;
        }
        final ItemStack stack = player.getInventory().getItem(inventorySlot);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent() && data.get().locked()) {
            player.sendActionBar(plugin.text().parse("<#ffd27f>🔒 已鎖定的裝備不能拆解（右鍵解除鎖定）"));
            return;
        }
        if (protectedFlask(inventorySlot, stack)) {
            player.sendActionBar(plugin.text().parse("<#ff7777>藥劑欄（快捷欄 " + (inventorySlot + 1) + "）的藥劑不會被拆：要拆先移到別格"));
            return;
        }
        final String identity = identity(inventorySlot, stack);
        if (identity == null) {
            return;
        }
        selected.put(inventorySlot, identity);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    private void salvage() {
        if (selected.isEmpty()) {
            return;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return; // 沒角色收不了金幣，不能拆
        }
        final PlayerInventory inventory = player.getInventory();
        int count = 0;
        long gold = 0;
        final Map<String, Integer> currencies = new LinkedHashMap<>();
        for (final Map.Entry<Integer, String> entry : selected.entrySet()) {
            final ItemStack stack = inventory.getItem(entry.getKey());
            if (!entry.getValue().equals(identity(entry.getKey(), stack))) {
                continue; // 這格已經換成別的東西（或是藥劑欄的藥劑）
            }
            final Optional<ItemData> data = plugin.items().read(stack);
            if (data.isEmpty()) {
                // 寶石（整疊）、藥劑：只有金幣
                gold += otherGold(stack);
                // 未切割寶石算通貨（2026-10）：拆掉的記進流水帳的消耗
                plugin.currencies().read(stack).ifPresent(type ->
                        plugin.economy().ledger().consumed(type.id(), tw.exilecore.economy.EconomyLedger.SALVAGE, stack.getAmount()));
                inventory.setItem(entry.getKey(), null);
                count++;
                continue;
            }
            final ItemBase base = plugin.items().repository().base(data.get().base());
            final SalvageConfig.Reward reward = config.reward(base, data.get(), plugin.items().random());
            inventory.setItem(entry.getKey(), null);
            count++;
            gold += reward.gold();
            if (reward.currencyId() != null) {
                currencies.merge(reward.currencyId(), reward.currencyAmount(), Integer::sum);
            }
        }
        selected.clear();
        if (count == 0) {
            return;
        }
        if (gold > 0) {
            plugin.gold().earn(player, gold, tw.exilecore.economy.EconomyLedger.SALVAGE);
        }
        final List<String> parts = new ArrayList<>();
        parts.add(String.format(Locale.ROOT, "<#f5b942>金幣 +%,d</#f5b942>", gold));
        for (final Map.Entry<String, Integer> entry : currencies.entrySet()) {
            final CurrencyType type = plugin.currencies().repository().get(entry.getKey());
            if (type == null) {
                continue;
            }
            give(type, entry.getValue());
            plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.SALVAGE, entry.getValue());
            parts.add(ItemRenderer.escape(type.name()) + " ×" + entry.getValue());
        }
        player.sendMessage(plugin.text().prefixed("<gray>拆解了 <white>" + count + "</white> 件：" + String.join("<gray>、", parts)));
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.8f, 1.0f);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
    }

    /** 給通貨；背包放不下的掉在腳下（只有自己看得到、撿得到）。 */
    private void give(final CurrencyType type, final int amount) {
        int remaining = amount;
        while (remaining > 0) {
            final int batch = Math.min(remaining, type.stack());
            for (final ItemStack rest : player.getInventory().addItem(plugin.currencies().create(type, batch)).values()) {
                plugin.personalDrops().dropForever(player, rest);
            }
            remaining -= batch;
        }
    }

    /** 地圖：稀有度那幾顆快速選擇不會選到，「全部可以拆的」會選（2026-10）。 */
    private boolean isMap(final ItemData data) {
        final tw.exilecore.item.ItemBase base = plugin.items().repository().base(data.base());
        return base != null && base.isMap();
    }
}
