package tw.exilecore.flask;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 藥劑（PoE2）：生命與魔力藥劑放在快捷欄的藥劑欄（預設 1 生命、2 魔力），按那個數字鍵就喝，手上的武器不會換掉。
 * <ul>
 *   <li>藥劑欄只能放對應的那種藥劑；空著的時候放一個空瓶當提示（{@link #isPlaceholder}，拿不走、不能交易），
 *       撿到或買到藥劑時那格是空的就自動放進去</li>
 *   <li>喝一次用掉固定的充能，在幾秒內慢慢回復；回滿了效果就結束（PoE 規則）</li>
 *   <li>殺怪給藥劑欄裡的藥劑充能（稀有度越高越多）；回營地、碰到檢查點、死掉重生時補滿</li>
 *   <li>充能畫在物品的耐久條上，一眼看得出還能喝幾次</li>
 * </ul>
 */
public final class FlaskService {

    /** 2026-10-26：FLAT：藥劑回復有 x% 立即生效（裝備的全域屬性，PoE2 百用束帶的固定詞綴）。 */
    public static final String STAT_INSTANT = "flask.instant";


    /** 進度鍵：新角色的起始藥劑送過了。 */
    public static final String PROGRESS_STARTER = "flask.starter";
    /** 回復多久結算一次（tick）。 */
    static final int TICK_INTERVAL = 2;
    /** 掉落的藥劑是魔法的機率（PoE2 的藥劑掉落大多是普通或魔法）。 */
    static final double DROP_MAGIC_CHANCE = 0.35;

    /** 物品上的資料：基底與目前的充能。 */
    public record FlaskData(String base, int charges) {
    }

    /** 一次喝下去的回復：每 tick 回多少、還剩幾 tick。 */
    private static final class Recovery {
        final FlaskKind kind;
        final double perTick;
        int ticksLeft;

        Recovery(final FlaskKind kind, final double perTick, final int ticksLeft) {
            this.kind = kind;
            this.perTick = perTick;
            this.ticksLeft = ticksLeft;
        }
    }

    private final ExileCore plugin;
    private final FlaskConfig config;
    private final NamespacedKey key;
    private final boolean resourcePack;
    private final Map<UUID, List<Recovery>> active = new HashMap<>();
    /** 玩家 → 藥劑欄 → 上次喝的 tick（滾輪滾過去不會連喝）。 */
    private final Map<UUID, Map<Integer, Long>> lastUse = new HashMap<>();
    /** 玩家 → 上次在哪個檢查點補滿、什麼時候。 */
    private final Map<UUID, String> lastCheckpoint = new HashMap<>();
    private final Map<UUID, Long> lastCheckpointTick = new HashMap<>();

    public FlaskService(final ExileCore plugin, final FlaskConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.key = new NamespacedKey(plugin, "flask");
        this.resourcePack = plugin.getConfig().getBoolean("resource-pack.enabled", false);
    }

    private CharmService charms;

    void setCharms(final CharmService charms) {
        this.charms = charms;
    }

    /** 護符（2026-10）。 */
    public CharmService charms() {
        return charms;
    }

    public FlaskConfig config() {
        return config;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 物品 ----

    public Optional<FlaskData> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        final String text = stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (text == null) {
            return Optional.empty();
        }
        final int split = text.lastIndexOf(';');
        if (split <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FlaskData(text.substring(0, split), Integer.parseInt(text.substring(split + 1).trim())));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    public boolean isFlask(final ItemStack stack) {
        return read(stack).isPresent();
    }

    /** 做一瓶普通的藥劑（充能 charges，超過上限會被夾住）。 */
    public ItemStack create(final FlaskBase base, final int charges) {
        return create(base, charges, Rarity.NORMAL, base.level());
    }

    /**
     * 做一瓶藥劑（2026-10：藥劑是物品引擎的基底，會擲 PoE2 的藥劑詞綴：普通沒有、魔法一前綴一後綴）。
     * 物品資料（詞綴）存在物品引擎的資料裡，充能另外存在藥劑自己的標記（{@link FlaskData}）。
     */
    public ItemStack create(final FlaskBase base, final int charges, final Rarity rarity, final int itemLevel) {
        final ItemBase itemBase = plugin.items() == null ? null : plugin.items().repository().base(base.id());
        if (itemBase == null || !itemBase.isFlask()) {
            final ItemStack stack = ItemStack.of(Material.POTION);
            renderLegacy(stack, base, charges);
            return stack;
        }
        final Rarity effective = rarity == Rarity.RARE || rarity == Rarity.UNIQUE ? Rarity.MAGIC : rarity;
        final ItemData data = plugin.items().generator().generate(itemBase, effective, Math.max(base.level(), itemLevel), plugin.items().random());
        final ItemStack stack = plugin.items().toStack(data);
        writeCharges(stack, base, charges);
        plugin.items().rerender(stack, data);
        return stack;
    }

    /** 這瓶藥劑的物品資料（詞綴）；舊版沒有資料的藥劑第一次碰到時補上普通的資料。沒有物品引擎回傳 null。 */
    ItemData itemData(final ItemStack stack, final FlaskBase base) {
        if (plugin.items() == null) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            return data.get();
        }
        final ItemBase itemBase = plugin.items().repository().base(base.id());
        if (itemBase == null || !itemBase.isFlask()) {
            return null;
        }
        final ItemData fresh = plugin.items().generator().generate(itemBase, Rarity.NORMAL, base.level(), plugin.items().random());
        plugin.items().update(stack, fresh);
        return fresh;
    }

    /** 這瓶藥劑詞綴算出來的數值（沒有詞綴、沒有物品引擎都是 {@link FlaskMods#NONE}）。 */
    public FlaskMods mods(final ItemStack stack) {
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        return base == null ? FlaskMods.NONE : mods(stack, base);
    }

    private FlaskMods mods(final ItemStack stack, final FlaskBase base) {
        final ItemData data = itemData(stack, base);
        return data == null ? FlaskMods.NONE : FlaskMods.of(plugin.items().repository(), data);
    }

    /** 這瓶每次用掉的充能：藥劑自己的詞綴 × 全域的「藥劑用掉的充能」（腰帶）。 */
    private int perUse(final Player player, final FlaskBase base, final FlaskMods mods) {
        final double global = plugin.players().sheet(player).increased(tw.exilecore.stats.StatKeys.FLASK_CHARGES_USED, java.util.Set.of());
        return Math.max(1, (int) Math.round(mods.perUse(base.perUse()) * Math.max(0, 1 + global)));
    }

    /** 這瓶的充能上限（基底 × 詞綴）。 */
    public int maxCharges(final ItemStack stack, final FlaskBase base) {
        return mods(stack, base).maxCharges(base.charges());
    }

    /** 把充能寫進藥劑自己的標記（不重畫）。 */
    private void writeCharges(final ItemStack stack, final FlaskBase base, final int charges) {
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + Math.max(0, charges)));
    }

    /** 依資料重畫：名稱、說明、充能條（耐久條）、顏色。 */
    private void render(final ItemStack stack, final FlaskBase base, final int charges) {
        final ItemData data = itemData(stack, base);
        if (data == null) {
            renderLegacy(stack, base, charges);
            return;
        }
        final int clamped = Math.max(0, Math.min(FlaskMods.of(plugin.items().repository(), data).maxCharges(base.charges()), charges));
        writeCharges(stack, base, clamped);
        plugin.items().rerender(stack, data); // 物品引擎畫詞綴，再呼叫 decorate 補藥劑的說明與充能條
    }

    /**
     * 物品引擎畫完詞綴之後的補畫（{@link ItemRenderer#setFlaskDecorator}）：藥劑的說明放在詞綴前面、充能條、藥水顏色、
     * 原版藥水的那幾行藏起來。護符交給護符服務。
     */
    void decorate(final ItemStack stack, final ItemData data) {
        final FlaskBase base = config.base(data.base());
        if (base == null) {
            if (charms != null) {
                charms.decorate(stack, data);
            }
            return;
        }
        final FlaskMods mods = FlaskMods.of(plugin.items().repository(), data);
        final int max = mods.maxCharges(base.charges());
        final int charges = Math.max(0, Math.min(max, read(stack).map(FlaskData::charges).orElse(max)));
        stack.editMeta(meta -> {
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(base, mods, charges, data.identified(), FlaskMods.always(plugin.items().repository(), data))) {
                lore.add(ItemRenderer.line(line));
            }
            final List<Component> affixLines = meta.lore();
            if (affixLines != null) {
                lore.addAll(affixLines);
            }
            meta.lore(tw.exilecore.core.text.TextWrap.lore(lore)); // 2026-10-26：長行自動換行
            meta.setMaxStackSize(1);
            if (resourcePack && base.model() != null) {
                final NamespacedKey model = NamespacedKey.fromString(base.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + charges);
        });
        potionLook(stack, base, max, charges);
    }

    /** 藥水顏色、充能條、藏起原版的那幾行（資料元件放在 editMeta 之後設定，才不會被 ItemMeta 寫回時蓋掉）。 */
    private static void potionLook(final ItemStack stack, final FlaskBase base, final int max, final int charges) {
        stack.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents()
                .customColor(base.kind() == FlaskKind.LIFE ? Color.fromRGB(0xD7263D) : Color.fromRGB(0x2F6BFF)).build());
        stack.unsetData(DataComponentTypes.CONSUMABLE); // 原版的喝藥水不要：我們用數字鍵喝
        // 充能畫成耐久條：滿的時候不顯示
        stack.setData(DataComponentTypes.MAX_DAMAGE, Math.max(1, max));
        stack.setData(DataComponentTypes.DAMAGE, Math.max(0, Math.min(max, max - charges)));
        // 原版藥水的「無效果」那行不要顯示
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.POTION_CONTENTS, DataComponentTypes.ATTRIBUTE_MODIFIERS,
                        DataComponentTypes.DAMAGE, DataComponentTypes.MAX_DAMAGE, DataComponentTypes.UNBREAKABLE).build());
    }

    /** 沒有物品引擎（測試、模組沒啟用）時的畫法：只有基底的說明。 */
    private void renderLegacy(final ItemStack stack, final FlaskBase base, final int charges) {
        final int clamped = Math.max(0, Math.min(base.charges(), charges));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<white>" + ItemRenderer.escape(base.name())));
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(base, FlaskMods.NONE, clamped, true, false)) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(tw.exilecore.core.text.TextWrap.lore(lore)); // 2026-10-26：長行自動換行
            meta.setMaxStackSize(1);
            if (resourcePack && base.model() != null) {
                final NamespacedKey model = NamespacedKey.fromString(base.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + clamped);
        });
        potionLook(stack, base, base.charges(), clamped);
    }

    private List<String> lore(final FlaskBase base, final FlaskMods mods, final int charges, final boolean identified, final boolean always) {
        final List<String> lines = new ArrayList<>();
        final String color = base.kind().color();
        final int max = mods.maxCharges(base.charges());
        final int perUse = mods.perUse(base.perUse());
        final double amount = mods.recoveryAmount(base.recovery(), false);
        final double seconds = mods.recoveryTime(base.duration());
        final boolean boosted = identified && !mods.isNone();
        final String number = boosted ? "<#8888ff>" : "<white>";
        if (mods.instant() >= 1) {
            lines.add(String.format(Locale.ROOT, "<gray>立即回復 <%s>%d</%s> %s", color, Math.round(amount), color, base.kind().displayName()));
        } else {
            lines.add(String.format(Locale.ROOT, "<gray>在 %s%s</%s> 秒內回復 <%s>%d</%s> %s", number, trim(seconds),
                    boosted ? "#8888ff" : "white", color, Math.round(amount), color, base.kind().displayName()));
            if (mods.instant() > 0) {
                lines.add(String.format(Locale.ROOT, "<gray>其中 <#8888ff>%d%%</#8888ff> 立即回復", Math.round(mods.instant() * 100)));
            }
        }
        if (mods.recoveryLow() != 1) {
            lines.add(String.format(Locale.ROOT, "<gray>%s低於 %d%% 時回復 <#8888ff>%d</#8888ff>", base.kind().displayName(),
                    Math.round(FlaskMods.LOW_FRACTION * 100), Math.round(mods.recoveryAmount(base.recovery(), true))));
        }
        if (always) {
            // 2026-10-30 照 PoE2（拉維安加之泉）：不能喝，放在藥劑欄就一直照回復速度回復
            lines.add(String.format(Locale.ROOT, "<gray>放在藥劑欄時每秒回復 <%s>%s</%s> %s<dark_gray>（不花充能）", color,
                    trim(constantPerSecond(amount, seconds)), color, base.kind().displayName()));
            lines.add("<gray>需求：等級 <white>" + base.level());
            return lines;
        }
        lines.add("<gray>充能 <white>" + charges + "</white>／" + number + max + "</" + (boosted ? "#8888ff" : "white") + ">，每次用 "
                + number + perUse + "</" + (boosted ? "#8888ff" : "white") + ">（還能喝 <white>" + charges / perUse + "</white> 次）");
        lines.add("<gray>需求：等級 <white>" + base.level());
        lines.add("");
        // 2026-10：藥劑欄的位置玩家可以選（1–8 任選兩格），說明不寫死是哪個數字鍵
        lines.add("<gray>放在快捷欄的" + base.kind().displayName() + "藥劑欄，按那一格的數字鍵喝（手上的武器不會換掉）");
        lines.add("<gray>殺怪充能；回營地、碰到檢查點、重生時補滿");
        lines.add("<dark_gray>" + base.kind().displayName() + "回滿了效果就結束");
        return lines;
    }

    // ---- 2026-10：藥劑欄的位置照玩家選的快捷列排法（HotbarLayout：1–8 任選兩格） ----

    private tw.exilecore.character.HotbarLayout layout(final Player player) {
        return player == null ? tw.exilecore.character.HotbarLayout.CLASSIC : tw.exilecore.character.HotbarLayout.of(plugin.players().get(player));
    }

    /** 這個玩家的兩格藥劑欄（生命、魔力；快捷欄索引）。 */
    public int[] slots(final Player player) {
        return layout(player).flaskSlots(config.slots());
    }

    /** 這個玩家這種藥劑放哪一格。 */
    public int slotFor(final Player player, final FlaskKind kind) {
        return layout(player).flaskSlot(kind == FlaskKind.LIFE ? 0 : 1, config.slots());
    }

    /** 這個玩家的這一格是哪一種藥劑欄（不是回傳 null）。 */
    public FlaskKind kindAt(final Player player, final int hotbarIndex) {
        final int index = layout(player).flaskIndex(hotbarIndex, config.slots());
        return index < 0 ? null : index == 0 ? FlaskKind.LIFE : FlaskKind.MANA;
    }

    public boolean isFlaskSlot(final Player player, final int hotbarIndex) {
        return kindAt(player, hotbarIndex) != null;
    }

    /** 這個玩家這種藥劑是數字鍵幾。 */
    public String slotKey(final Player player, final FlaskKind kind) {
        return String.valueOf(slotFor(player, kind) + 1);
    }

    /**
     * 換快捷列排法（設定 →「按鍵」；只能在營地換，技能圖示沒有擺出來時才不會亂）：
     * 藥劑（含空瓶）搬到新的藥劑欄，新藥劑欄原本的東西搬到空出來的舊藥劑欄（放不下就收進背包）。回傳有沒有換。
     */
    public boolean switchLayout(final Player player, final tw.exilecore.character.HotbarLayout target) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || target == null) {
            return false;
        }
        final int[] before = slots(player);
        final int[] after = target.flaskSlots(config.slots());
        if (before[0] == after[0] && before[1] == after[1]) {
            return false;
        }
        final PlayerInventory inventory = player.getInventory();
        final int[] from = slots(player);
        final ItemStack[] flasks = {inventory.getItem(from[0]), inventory.getItem(from[1])};
        final String encoded = target.encode();
        if (encoded != null) {
            plugin.characters().setProgress(player, tw.exilecore.character.HotbarLayout.PROGRESS, encoded);
        } else {
            plugin.characters().removeProgress(player, tw.exilecore.character.HotbarLayout.PROGRESS);
        }
        final int[] to = slots(player);
        final List<ItemStack> displaced = new ArrayList<>();
        for (final int slot : to) {
            if (slot != from[0] && slot != from[1]) {
                final ItemStack there = inventory.getItem(slot);
                if (!vacant(there) && !isPlaceholder(there)) {
                    displaced.add(there);
                }
            }
        }
        for (final int slot : from) {
            inventory.setItem(slot, null);
        }
        for (int i = 0; i < 2; i++) {
            inventory.setItem(to[i], flasks[i] == null || flasks[i].isEmpty() ? null : flasks[i]);
        }
        for (final ItemStack item : displaced) {
            ItemStack left = item;
            for (final int slot : from) {
                if (left != null && slot != to[0] && slot != to[1] && vacant(inventory.getItem(slot))) {
                    inventory.setItem(slot, left);
                    left = null;
                }
            }
            if (left != null) {
                final ItemStack rest = plugin.loot() != null ? plugin.loot().stow(player, state, left)
                        : inventory.addItem(left).values().stream().findFirst().orElse(null);
                if (rest != null && !rest.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        }
        ensureSlots(player);
        plugin.players().markDirty(player);
        if (plugin.characters().gear() != null) {
            plugin.characters().gear().resync(player);
        }
        return true;
    }

    /** 「1（生命）、2（魔力）」。 */
    public String slotText(final Player player) {
        return slotKey(player, FlaskKind.LIFE) + "（生命）、" + slotKey(player, FlaskKind.MANA) + "（魔力）";
    }

    private static Player holder(final PlayerInventory inventory) {
        return inventory.getHolder() instanceof Player player ? player : null;
    }

    /** 「1（生命）、2（魔力）」（設定檔的經典排法；記錄用）。 */
    String slotText() {
        return slotKey(FlaskKind.LIFE) + "（生命）、" + slotKey(FlaskKind.MANA) + "（魔力）";
    }

    /** 這種藥劑的藥劑欄是數字鍵幾。 */
    String slotKey(final FlaskKind kind) {
        return String.valueOf(config.slotFor(kind) + 1);
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** 改一格上的藥劑充能（會重畫）。回傳改完的充能；不是藥劑回傳 -1。 */
    private int setCharges(final PlayerInventory inventory, final int slot, final int charges) {
        final ItemStack stack = inventory.getItem(slot);
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        if (base == null) {
            return -1;
        }
        final int clamped = Math.max(0, Math.min(maxCharges(stack, base), charges));
        if (clamped != data.charges()) {
            render(stack, base, clamped);
            inventory.setItem(slot, stack);
        }
        return clamped;
    }

    // ---- 喝 ----

    /** 走藥劑系統的玩家：有角色、生存或冒險模式、活著。 */
    public boolean eligible(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final GameMode mode = player.getGameMode();
        return state.isLoaded() && state.hasCharacter() && !player.isDead() && !state.isDead()
                && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE);
    }

    /**
     * 喝藥劑欄某一格的藥劑（數字鍵）。回傳 false 表示那一格放的不是藥劑也不是空瓶（照原版換手上的東西）。
     * 空瓶（藥劑欄是空的）、放錯種類：提示，不喝。
     */
    /**
     * 2026-10-26（天賦樹「生命藥劑沒有使用次數時」）：這種藥劑欄的藥劑還夠不夠喝一次。sheet 給呼叫的人現在的屬性表
     * （不要在屬性表重算中呼叫：藥劑的全域詞綴要讀表）。
     */
    public boolean hasUse(final Player player, final FlaskKind kind, final tw.exilecore.stats.StatSheet sheet) {
        final ItemStack item = player.getInventory().getItem(slotFor(player, kind));
        if (item == null || isPlaceholder(item)) {
            return false;
        }
        final FlaskData data = read(item).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        if (base == null || base.kind() != kind) {
            return false;
        }
        final double global = sheet.increased(tw.exilecore.stats.StatKeys.FLASK_CHARGES_USED, java.util.Set.of());
        return data.charges() >= Math.max(1, (int) Math.round(mods(item, base).perUse(base.perUse()) * Math.max(0, 1 + global)));
    }

    public boolean drink(final Player player, final int hotbarSlot) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack item = inventory.getItem(hotbarSlot);
        final FlaskKind slotKind = kindAt(player, hotbarSlot);
        final PlayerState state = plugin.players().get(player);
        if (isPlaceholder(item)) {
            if (slotKind != null) {
                // 只提示、不出聲：滾輪滾過去也會到這裡
                state.notice("<gray>" + slotKind.displayName() + "藥劑欄是空的：把" + slotKind.displayName() + "藥劑放進快捷欄 "
                        + slotKey(player, slotKind) + "（撿到時會自動放進來）", now() + 40);
                plugin.players().display(player, state);
            }
            return true;
        }
        final FlaskData data = read(item).orElse(null);
        if (data == null) {
            return false;
        }
        final FlaskBase base = config.base(data.base());
        if (base == null) {
            fail(player, state, "不認得的藥劑（flasks.yml 裡沒有 " + data.base() + "）");
            return true;
        }
        if (slotKind != null && base.kind() != slotKind) {
            fail(player, state, base.kind().displayName() + "藥劑要放在快捷欄 " + slotKey(player, base.kind()) + " 才能喝");
            return true;
        }
        final long now = now();
        final Map<Integer, Long> uses = lastUse.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        final Long last = uses.get(hotbarSlot);
        if (last != null && now - last < config.useCooldownTicks()) {
            return true; // 按太快（滾輪滾過去）
        }
        if (state.level() < base.level()) {
            fail(player, state, base.name() + "需要等級 " + base.level());
            return true;
        }
        if (always(item, base)) {
            // 2026-10-30 照 PoE2（拉維安加之泉）：這瓶不能喝，效果一直套用（見 tickConstant）
            fail(player, state, base.name() + "無法使用（效果一直套用中）");
            return true;
        }
        final FlaskMods mods = mods(item, base);
        final int perUse = perUse(player, base, mods);
        if (data.charges() < perUse) {
            fail(player, state, base.name() + "沒有充能了（殺怪充能，回營地或檢查點補滿）");
            return true;
        }
        final boolean life = base.kind() == FlaskKind.LIFE;
        if (life && constantLife(plugin.players().sheet(player))) {
            // 2026-10-28 照 PoE2（綺夢）：無法使用生命藥劑（效果一直套用，見 tickConstant）
            fail(player, state, "無法使用生命藥劑（生命藥劑的效果一直套用中）");
            return true;
        }
        final boolean full = life ? state.life() >= state.maxLife() - 1e-6 : state.mana() >= state.maxMana() - 1e-6;
        if (full) {
            fail(player, state, base.kind().displayName() + "已經是滿的");
            return true;
        }
        uses.put(hotbarSlot, now);
        plugin.players().conditions().onFlask(player, base.kind(), now); // 2026-10-26：天賦樹「最近用過魔力藥劑」「10 秒內用過生命藥劑」
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        // 「x% 機率不消耗充能」（裝備的全域詞綴）
        final double free = Math.max(0, Math.min(1, sheet.get(tw.exilecore.stats.StatKeys.FLASK_FREE_CHANCE) / 100.0));
        if (free <= 0 || plugin.items().random().nextDouble() >= free) {
            setCharges(inventory, hotbarSlot, data.charges() - perUse);
        }
        // 回復量：藥劑自己的詞綴（低血時更多）× 「藥劑回復」的全域加成（任務獎勵、裝備）
        final boolean low = life ? state.life() < state.maxLife() * FlaskMods.LOW_FRACTION : state.mana() < state.maxMana() * FlaskMods.LOW_FRACTION;
        final String recoveryStat = life ? tw.exilecore.stats.StatKeys.FLASK_LIFE_RECOVERY : tw.exilecore.stats.StatKeys.FLASK_MANA_RECOVERY;
        // 2026-10-26：「更多／更少藥劑回復」也算（幻景綠洲更多 30%、青春永駐更少 50%、地圖詞綴）；這次之前只算增加
        final double recovery = mods.recoveryAmount(base.recovery(), low) * Math.max(0, 1 + sheet.increased(recoveryStat, java.util.Set.of()))
                * Math.max(0, sheet.more(recoveryStat, java.util.Set.of()));
        // 回復時間：藥劑自己的「回復速度」× 全域的「藥劑效果持續時間」
        // 2026-10-26 照 PoE2 天賦樹：「增加藥劑生命回復速率」：同樣的量、更短的時間
        // 2026-10-27 照 PoE2：「藥劑魔力回復速率」（flask_mana_recovery_rate_+%）、生命／魔力回復速度（life／mana_recovery_rate_+%，
        // 回復速度管所有隨時間的回復，藥劑也算）也一樣：同樣的量、更短的時間；這次之前只有藥劑生命回復速率
        final double baseSeconds = mods.recoveryTime(base.duration())
                * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.StatKeys.FLASK_DURATION, java.util.Set.of()));
        final int lifeTicks = Math.max(1, (int) Math.round(baseSeconds * 20 / flaskRate(sheet, true)));
        final int manaTicks = Math.max(1, (int) Math.round(baseSeconds * 20 / flaskRate(sheet, false)));
        final int ticks = life ? lifeTicks : manaTicks;
        // 2026-10-26（PoE2 百用束帶：藥劑回復有 20% 立即生效）：裝備的全域「立即回復」跟藥劑自己的詞綴相加
        // 2026-10-26（亡命者背心）：生命藥劑的回復全部立即生效
        final double instant = recovery * (life && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.FLASK_LIFE_INSTANT)
                ? 1.0 : Math.min(1, mods.instant() + Math.max(0, sheet.get(STAT_INSTANT) / 100.0)));
        final double gradual = recovery - instant;
        // 2026-10-26（昇華）：阿茲莫里之酒（生命藥劑也回復魔力、魔力藥劑也回復生命）、血紅之潮（藥劑不會回復生命）
        final boolean noLife = tw.exilecore.ascendancy.AscendancyEffects.flaskNoLife(player);
        final boolean both = tw.exilecore.ascendancy.AscendancyEffects.flaskBoth(player);
        if (instant > 0) {
            if ((life || both) && !noLife) {
                state.setLife(state.life() + instant);
                lifeToEs(state, sheet, instant); // 2026-10-28 照 PoE2（薛朗的背包）
            }
            if (!life || both) {
                state.setMana(state.mana() + instant);
            }
        }
        if (gradual > 0) {
            final List<Recovery> list = active.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>());
            if ((life || both) && !noLife) {
                list.add(new Recovery(FlaskKind.LIFE, gradual / lifeTicks, lifeTicks));
            }
            if (!life || both) {
                list.add(new Recovery(FlaskKind.MANA, gradual / manaTicks, manaTicks));
            }
        }
        // 「生命藥劑的回復也消耗魔力」／「魔力藥劑的回復也消耗生命」：喝的時候一次扣另一種資源
        if (mods.costOther() > 0) {
            final double cost = recovery * mods.costOther();
            if (life) {
                state.setMana(Math.max(0, state.mana() - cost));
            } else {
                state.setLife(Math.max(1, state.life() - cost));
            }
        }
        // 「召喚物也回復 x% 的回復量」
        if (life && mods.minionHeal() > 0 && plugin.skills() != null && plugin.skills().minions() != null) {
            plugin.skills().minions().healOwned(player, recovery * mods.minionHeal());
        }
        if (life && plugin.combat() != null && plugin.combat().uniques() != null) {
            plugin.combat().uniques().lifeFlaskUsed(player, sheet, recovery, ticks); // 2026-10-26（骸骨束帶）：生命藥劑也套用在召喚物身上
        }
        if (life && plugin.skills() != null && plugin.skills().treeMechanics() != null) {
            plugin.skills().treeMechanics().lifeFlaskUsed(player, recovery); // 2026-10-26 照 PoE2 天賦：使用生命藥劑時獲得怒氣、夥伴也回復
        }
        tw.exilecore.combat.KeystoneEffects.flaskUsed(plugin, player, state, sheet, life, recovery, ticks); // 2026-10-26 天賦樹：守護、魔力藥劑回生命、解詛咒
        state.markRecent(tw.exilecore.stats.Recently.FLASK, now); // 「最近喝過藥劑」
        state.markDirty(); // 藥劑效果中的條件（lifeflask／manaflask）
        plugin.players().display(player, state);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 0.7f, 1.2f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.2, 0), 10, 0.3, 0.4, 0.3, 0,
                new Particle.DustOptions(base.kind() == FlaskKind.LIFE ? Color.fromRGB(0xFF4D5E) : Color.fromRGB(0x5C8DFF), 1.1f));
        return true;
    }

    /**
     * 2026-10-27 照 PoE2：藥劑回復得多快（倍率，時間除以它）：藥劑生命／魔力回復速率 ×（生命／魔力）回復速度。
     * 兩種是不同的乘數（PoE2：Flask Life Recovery rate 與 Life Recovery rate 分開乘）。
     */
    public static double flaskRate(final tw.exilecore.stats.StatSheet sheet, final boolean life) {
        final double flask = Math.max(0.1, 1 + sheet.increased(life ? tw.exilecore.stats.KeystoneKeys.FLASK_LIFE_RATE
                : tw.exilecore.stats.TreeEffectKeys.FLASK_MANA_RATE, java.util.Set.of()));
        return flask * tw.exilecore.stats.TreeEffectKeys.recoveryRate(sheet, life ? tw.exilecore.stats.TreeEffectKeys.LIFE_RECOVERY_RATE
                : tw.exilecore.stats.TreeEffectKeys.MANA_RECOVERY_RATE);
    }

    // ---- 2026-10-28 照 PoE2：綺夢（生命藥劑的效果一直套用）、薛朗的背包（藥劑的生命回復也套用在能量護盾） ----

    /** 綺夢：無法使用生命藥劑，非傳奇生命藥劑的效果一直套用。 */
    static boolean constantLife(final tw.exilecore.stats.StatSheet sheet) {
        return sheet != null && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.FLASK_LIFE_CONSTANT);
    }

    /** 薛朗的背包：藥劑回復的生命 amount 也回到能量護盾。 */
    private static void lifeToEs(final PlayerState state, final tw.exilecore.stats.StatSheet sheet, final double amount) {
        if (amount > 0 && sheet != null && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.FLASK_LIFE_TO_ES)) {
            state.setEnergyShield(state.energyShield() + amount);
        }
    }

    /**
     * 綺夢：一瓶生命藥劑每秒回復多少（同一瓶喝下去的回復量 ÷ 回復時間；吃藥劑回復的增加／更多、回復速度，不立即）。
     * 純函式：recovery 是這瓶的回復量（已乘全域的加成），seconds 是回復時間（已除回復速度）。
     */
    static double constantPerSecond(final double recovery, final double seconds) {
        return seconds <= 0 ? 0 : Math.max(0, recovery) / seconds;
    }

    /** 每秒：有綺夢的人，生命藥劑欄那瓶（非傳奇）照它的回復速度一直回生命，不花充能、不立即、只回自己。 */
    private void tickConstant() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!eligible(player)) {
                continue;
            }
            final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
            tickAlways(player, sheet);
            if (!constantLife(sheet)) {
                continue;
            }
            final ItemStack item = player.getInventory().getItem(slotFor(player, FlaskKind.LIFE));
            final FlaskData data = item == null || isPlaceholder(item) ? null : read(item).orElse(null);
            final FlaskBase base = data == null ? null : config.base(data.base());
            if (base == null || base.kind() != FlaskKind.LIFE) {
                continue;
            }
            final PlayerState state = plugin.players().get(player);
            if (state.life() >= state.maxLife() - 1e-6 || tw.exilecore.ascendancy.AscendancyEffects.flaskNoLife(player)) {
                continue;
            }
            final FlaskMods mods = mods(item, base);
            final double recovery = mods.recoveryAmount(base.recovery(), false)
                    * Math.max(0, 1 + sheet.increased(tw.exilecore.stats.StatKeys.FLASK_LIFE_RECOVERY, java.util.Set.of()))
                    * Math.max(0, sheet.more(tw.exilecore.stats.StatKeys.FLASK_LIFE_RECOVERY, java.util.Set.of()));
            final double seconds = mods.recoveryTime(base.duration())
                    * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.StatKeys.FLASK_DURATION, java.util.Set.of())) / flaskRate(sheet, true);
            final double heal = constantPerSecond(recovery, seconds);
            if (heal > 0) {
                state.setLife(state.life() + heal);
                lifeToEs(state, sheet, heal);
                plugin.players().display(player, state);
            }
        }
    }

    // ---- 2026-10-30 照 PoE2：拉維安加之泉（local_flask_always_drinking：無法使用，但會持續套用其效果） ----

    /** 這瓶有沒有「無法使用，但會持續套用其效果」。 */
    boolean always(final ItemStack stack, final FlaskBase base) {
        final ItemData data = itemData(stack, base);
        return data != null && FlaskMods.always(plugin.items().repository(), data);
    }

    /** 這種藥劑欄放的是一直套用的那種（而且等級夠）就回傳那瓶，不然 null。 */
    private ItemStack alwaysSlot(final Player player, final FlaskKind kind) {
        if (player == null || !eligible(player)) {
            return null;
        }
        final ItemStack item = player.getInventory().getItem(slotFor(player, kind));
        final FlaskData data = item == null || isPlaceholder(item) ? null : read(item).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        if (base == null || base.kind() != kind || plugin.players().get(player).level() < base.level() || !always(item, base)) {
            return null;
        }
        return item;
    }

    /** 每秒：藥劑欄裡一直套用的藥劑照它的回復速度回復（不花充能、不立即）。 */
    private void tickAlways(final Player player, final tw.exilecore.stats.StatSheet sheet) {
        for (final FlaskKind kind : FlaskKind.values()) {
            final ItemStack item = alwaysSlot(player, kind);
            if (item == null) {
                continue;
            }
            final boolean life = kind == FlaskKind.LIFE;
            final PlayerState state = plugin.players().get(player);
            if (life ? state.life() >= state.maxLife() - 1e-6 || tw.exilecore.ascendancy.AscendancyEffects.flaskNoLife(player)
                    : state.mana() >= state.maxMana() - 1e-6) {
                continue;
            }
            final FlaskBase base = config.base(read(item).orElseThrow().base());
            final FlaskMods mods = mods(item, base);
            final String stat = life ? tw.exilecore.stats.StatKeys.FLASK_LIFE_RECOVERY : tw.exilecore.stats.StatKeys.FLASK_MANA_RECOVERY;
            final double recovery = mods.recoveryAmount(base.recovery(), false)
                    * Math.max(0, 1 + sheet.increased(stat, java.util.Set.of())) * Math.max(0, sheet.more(stat, java.util.Set.of()));
            final double seconds = mods.recoveryTime(base.duration())
                    * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.StatKeys.FLASK_DURATION, java.util.Set.of())) / flaskRate(sheet, life);
            final double amount = constantPerSecond(recovery, seconds);
            if (amount <= 0) {
                continue;
            }
            if (life) {
                state.setLife(state.life() + amount);
                lifeToEs(state, sheet, amount);
            } else {
                state.setMana(state.mana() + amount);
            }
            plugin.players().display(player, state);
        }
    }

    /** 這個玩家的某一種藥劑效果還在進行中嗎（「藥劑效果中」的條件詞綴）。 */
    public boolean recovering(final Player player, final FlaskKind kind) {
        if (kind == FlaskKind.LIFE && constantLife(plugin.players().sheet(player))) {
            return true; // 2026-10-28 照 PoE2（綺夢）：生命藥劑的效果一直套用
        }
        if (alwaysSlot(player, kind) != null) {
            return true; // 2026-10-30 照 PoE2（拉維安加之泉）：效果一直套用
        }
        final List<Recovery> list = active.get(player.getUniqueId());
        if (list == null) {
            return false;
        }
        for (final Recovery recovery : list) {
            if (recovery.kind == kind && recovery.ticksLeft > 0) {
                return true;
            }
        }
        return false;
    }

    private void fail(final Player player, final PlayerState state, final String message) {
        state.notice("<red>" + ItemRenderer.escape(message), now() + 30);
        plugin.players().display(player, state);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    /** 每 TICK_INTERVAL tick：正在喝的藥劑回復一點；回滿了那一種的效果就結束。每兩秒把空瓶提示補好。 */
    void tick() {
        // 2026-10-27 效能：空瓶提示攤開補（每 2 tick 一小批，每個人一樣每 2 秒一次）；這次之前每 2 秒所有人同一個 tick 一起補
        sweepSlice();
        minuteTicks += TICK_INTERVAL;
        if (minuteTicks % 20 == 0) {
            tickConstant(); // 2026-10-28 照 PoE2（綺夢）：生命藥劑的效果一直套用，每秒一次
        }
        if (minuteTicks >= 1200) {
            minuteTicks = 0;
            tickPerMinute();
            if (charms != null) {
                charms.tickPerMinute();
            }
        }
        final Iterator<Map.Entry<UUID, List<Recovery>>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, List<Recovery>> entry = iterator.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !eligible(player)) {
                iterator.remove();
                continue;
            }
            final PlayerState state = plugin.players().get(player);
            double life = 0;
            double mana = 0;
            for (final Recovery recovery : entry.getValue()) {
                final int steps = Math.min(TICK_INTERVAL, recovery.ticksLeft);
                recovery.ticksLeft -= steps;
                if (recovery.kind == FlaskKind.LIFE) {
                    life += recovery.perTick * steps;
                } else {
                    mana += recovery.perTick * steps;
                }
            }
            if (life > 0) {
                state.setLife(state.life() + life);
                lifeToEs(state, plugin.players().sheet(player), life); // 2026-10-28 照 PoE2（薛朗的背包）
            }
            if (mana > 0) {
                state.setMana(state.mana() + mana);
            }
            final boolean lifeFull = state.life() >= state.maxLife() - 1e-6;
            final boolean manaFull = state.mana() >= state.maxMana() - 1e-6;
            // 2026-10-26（昇華・耐久靈藥）：生命全滿時不移除生命藥劑效果
            final boolean enduring = tw.exilecore.ascendancy.AscendancyEffects.enduringElixirs(player);
            entry.getValue().removeIf(recovery -> recovery.ticksLeft <= 0
                    || (recovery.kind == FlaskKind.LIFE && lifeFull && !enduring) || (recovery.kind == FlaskKind.MANA && manaFull));
            if (life > 0 || mana > 0) {
                plugin.players().display(player, state);
            }
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    // ---- 充能與補滿 ----

    /** 殺怪：藥劑欄裡的藥劑都充能。 */
    /** 只給某一種藥劑充能（2026-10：獵賞這類輔助）。 */
    public void addCharges(final Player player, final int amount, final FlaskKind kind) {
        if (amount <= 0) {
            return;
        }
        addCharges(player, slotFor(player, kind), amount, true);
    }

    /**
     * 2026-10-28 照 PoE2 Devour 的品質（「當作擊殺」獲得藥劑與護符充能）：照殺了一隻這種稀有度的怪物給藥劑與護符充能（不給擊殺的其他效果）。
     */
    public void gainAsKill(final Player player, final tw.exilecore.monster.MonsterRarity rarity) {
        if (!eligible(player)) {
            return;
        }
        final int charges = config.chargesPerKill(rarity);
        addCharges(player, charges);
        if (charms != null) {
            charms.onKill(player, rarity, charges);
        }
    }

    /** 2026-10-26（昇華・御風的混合物）：藥劑欄裡這種藥劑現在有幾點充能（沒有 0）。 */
    public int charges(final Player player, final FlaskKind kind) {
        final int slot = slotFor(player, kind);
        if (slot < 0) {
            return 0;
        }
        final FlaskData data = read(player.getInventory().getItem(slot)).orElse(null);
        return data == null ? 0 : data.charges();
    }

    /** 2026-10-26（昇華・御風的混合物）：用掉這種藥劑 amount 點充能；不夠回傳 false（不扣）。 */
    public boolean spendCharges(final Player player, final FlaskKind kind, final int amount) {
        final int slot = slotFor(player, kind);
        if (slot < 0) {
            return false;
        }
        final PlayerInventory inventory = player.getInventory();
        final FlaskData data = read(inventory.getItem(slot)).orElse(null);
        if (data == null || data.charges() < amount) {
            return false;
        }
        setCharges(inventory, slot, data.charges() - amount);
        return true;
    }

    /** 2026-10-26（昇華・血紅之潮）：這種藥劑充能全滿時，消耗 fraction 的充能；回傳消耗了幾點（沒滿 0）。 */
    public int consumeIfFull(final Player player, final FlaskKind kind, final double fraction) {
        final int slot = slotFor(player, kind);
        if (slot < 0) {
            return 0;
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(slot);
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        if (base == null || base.kind() != kind) {
            return 0;
        }
        final int max = maxCharges(stack, base);
        if (max <= 0 || data.charges() < max) {
            return 0;
        }
        final int used = (int) Math.floor(max * fraction);
        if (used > 0) {
            setCharges(inventory, slot, data.charges() - used);
        }
        return used;
    }

    /** 2026-10-23：藥劑欄裡的藥劑各少 amount 點充能（怪物詞綴「竊瓶的」）。 */
    public void drainCharges(final Player player, final int amount) {
        if (amount <= 0) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        for (final int slot : slots(player)) {
            final FlaskData data = read(inventory.getItem(slot)).orElse(null);
            if (data != null && data.charges() > 0) {
                setCharges(inventory, slot, Math.max(0, data.charges() - amount));
            }
        }
    }

    /** 殺怪：藥劑欄裡的藥劑都充能（藥劑自己的「充能獲得」詞綴、殺怪多一點的機率，與裝備的全域加成都算進去）。 */
    public void addCharges(final Player player, final int amount) {
        if (amount <= 0) {
            return;
        }
        for (final int slot : slots(player)) {
            addCharges(player, slot, amount, true);
        }
    }

    private void addCharges(final Player player, final int slot, final int amount, final boolean kill) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(slot);
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        if (base == null) {
            return;
        }
        final FlaskMods mods = mods(stack, base);
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        final double global = sheet.increased(tw.exilecore.stats.StatKeys.FLASK_CHARGES_GAINED, java.util.Set.of())
                + sheet.increased(base.kind() == FlaskKind.LIFE ? tw.exilecore.stats.StatKeys.FLASK_CHARGES_GAINED_LIFE
                : tw.exilecore.stats.StatKeys.FLASK_CHARGES_GAINED_MANA, java.util.Set.of());
        int gained = Math.max(1, (int) Math.round(mods.gained(amount) * Math.max(0, 1 + global)));
        if (kill && mods.chargeOnKill() > 0 && plugin.items().random().nextDouble() < mods.chargeOnKill()) {
            gained++;
        }
        setCharges(inventory, slot, data.charges() + gained);
    }

    /** 每分鐘：藥劑欄裡有「每分鐘獲得 x 點充能」的藥劑自動充能。 */
    private void tickPerMinute() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!eligible(player)) {
                continue;
            }
            final PlayerInventory inventory = player.getInventory();
            for (final int slot : slots(player)) {
                final ItemStack stack = inventory.getItem(slot);
                final FlaskData data = read(stack).orElse(null);
                final FlaskBase base = data == null ? null : config.base(data.base());
                if (base == null) {
                    continue;
                }
                final double perMinute = mods(stack, base).perMinute()
                        + plugin.players().sheet(player).get(base.kind() == FlaskKind.LIFE ? tw.exilecore.stats.StatKeys.FLASK_CHARGES_PER_MINUTE_LIFE
                        : tw.exilecore.stats.StatKeys.FLASK_CHARGES_PER_MINUTE_MANA);
                if (perMinute > 0) {
                    setCharges(inventory, slot, data.charges() + (int) Math.round(perMinute));
                }
            }
        }
    }

    /** 背包裡的藥劑全部補滿。回傳補了幾瓶。 */
    public int refill(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        int filled = charms == null ? 0 : charms.refill(player);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            final FlaskData data = read(stack).orElse(null);
            final FlaskBase base = data == null ? null : config.base(data.base());
            if (base == null) {
                continue;
            }
            final int max = maxCharges(stack, base);
            if (data.charges() < max) {
                setCharges(inventory, slot, max);
                filled++;
            }
        }
        return filled;
    }

    /** 碰到檢查點：補滿（同一個檢查點一段時間內只補一次，站在旁邊打怪不會一直補）。 */
    public void refillAtCheckpoint(final Player player, final String checkpoint) {
        final UUID uuid = player.getUniqueId();
        final long now = now();
        final Long last = lastCheckpointTick.get(uuid);
        if (checkpoint.equals(lastCheckpoint.get(uuid)) && last != null && now - last < config.checkpointRefillSeconds() * 20L) {
            return;
        }
        lastCheckpoint.put(uuid, checkpoint);
        lastCheckpointTick.put(uuid, now);
        if (refill(player) > 0) {
            final PlayerState state = plugin.players().get(player);
            state.notice("<#7fd4ff>藥劑補滿", now + 40);
            plugin.players().display(player, state);
            player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
    }

    // ---- 角色 ----

    /**
     * 換角色、登入：清掉回復中的效果；新角色（或還沒拿過的角色）送起始藥劑，放進藥劑欄
     * （藥劑欄上原本的東西搬到別的空格）。手上拿的是藥劑欄時換到武器那一格。
     */
    public void onCharacterApplied(final Player player, final PlayerState state) {
        forget(player);
        if (!state.hasCharacter()) {
            return;
        }
        if (!"1".equals(state.progress().get(PROGRESS_STARTER))) {
            giveStarter(player);
            plugin.characters().setProgress(player, PROGRESS_STARTER, "1");
            leaveFlaskSlot(player);
            organize(player);
            plugin.players().markDirty(player); // 東西搬過、手上換了一格：屬性表重算
            plugin.characters().save(player); // 進度記下「送過了」，背包也要馬上存
            return;
        }
        final boolean left = leaveFlaskSlot(player);
        if (organize(player) || left) {
            plugin.players().markDirty(player);
        }
    }

    private void giveStarter(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final List<String> starter = config.starter();
        boolean moved = false;
        boolean bagged = false;
        boolean dropped = false;
        for (final String id : starter) {
            final FlaskBase base = config.base(id);
            final ItemStack flask = create(base, base.charges());
            // 照種類放：生命藥劑放生命藥劑欄、魔力放魔力（那格已經有同一種的就放背包）
            final int slot = slotFor(player, base.kind());
            final ItemStack current = inventory.getItem(slot);
            if (kindOf(current) != base.kind() && makeRoom(inventory, slot)) {
                inventory.setItem(slot, flask);
                continue;
            }
            moved = true;
            for (final ItemStack rest : inventory.addItem(flask).values()) {
                // 背包滿了先放隨身背包（接著可能會傳送回營地，掉在腳邊會留在原地）
                final Bag bag = plugin.players().get(player).bag();
                if (bag != null && bag.add(rest)) {
                    bagged = true;
                    continue;
                }
                plugin.personalDrops().dropForever(player, rest);
                dropped = true;
            }
        }
        final String where = dropped ? "。<yellow>背包滿了，藥劑掉在腳邊，記得撿起來放到快捷欄 " + slotText(player)
                : bagged ? "。<yellow>背包滿了，藥劑放進隨身背包（Shift＋F），記得搬到快捷欄 " + slotText(player)
                : moved ? "。<yellow>快捷欄滿了，藥劑放在背包裡，記得搬到快捷欄 " + slotText(player) : "";
        player.sendMessage(plugin.text().prefixed("<gray>拿到藥劑：放在快捷欄 <white>" + slotText(player)
                + "</white>，按那個數字鍵喝（手上的武器不會換掉）；殺怪充能，回營地或檢查點補滿" + where));
    }

    /** 讓藥劑欄空出來：原本的東西搬到第一個空格（先找快捷欄，再找背包）；空瓶提示直接蓋掉。搬不動回傳 false。 */
    private boolean makeRoom(final PlayerInventory inventory, final int slot) {
        final ItemStack current = inventory.getItem(slot);
        if (current == null || current.isEmpty() || isPlaceholder(current)) {
            return true;
        }
        final boolean held = inventory.getHeldItemSlot() == slot;
        for (int target = 0; target < 36; target++) {
            if (target == slot || isFlaskSlot(holder(inventory), target)) {
                continue;
            }
            final ItemStack there = inventory.getItem(target);
            if (there == null || there.isEmpty()) {
                inventory.setItem(target, current);
                inventory.setItem(slot, null);
                if (held && target < 9) {
                    inventory.setHeldItemSlot(target); // 原本拿著的武器搬到哪就拿哪一格
                }
                return true;
            }
        }
        return false;
    }

    /** 手上拿的是藥劑欄：換到第一個不是藥劑欄的格子（優先有武器的那格）。回傳有沒有換。 */
    public boolean leaveFlaskSlot(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        if (!isFlaskSlot(player, inventory.getHeldItemSlot())) {
            return false;
        }
        int fallback = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (isFlaskSlot(player, slot)) {
                continue;
            }
            if (fallback < 0) {
                fallback = slot;
            }
            if (plugin.items().isEquipment(inventory.getItem(slot))) {
                inventory.setHeldItemSlot(slot);
                return true;
            }
        }
        if (fallback >= 0) {
            inventory.setHeldItemSlot(fallback);
            return true;
        }
        return false;
    }

    // ---- 藥劑欄：空瓶提示、自動放進去 ----

    /** 空瓶提示的標記（值是 life／mana）。 */
    private static final NamespacedKey SLOT_KEY = Objects.requireNonNull(NamespacedKey.fromString("exilecore:flask_slot"));
    /** 空瓶的樣子：原版的空玻璃瓶（介面資源包把它畫成紅色／藍色的瓶子輪廓，看 custom_model_data 的字串 0）。 */
    private static final Key BOTTLE = Key.key("minecraft", "glass_bottle");

    /** 這是藥劑欄的空瓶提示嗎（不是真的東西：拿不走、不能交易、上架、丟掉）。 */
    public static boolean isPlaceholder(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(SLOT_KEY, PersistentDataType.STRING);
    }

    /** 空瓶提示是哪一種藥劑欄的（life／mana；不是空瓶回傳 null）。 */
    private static String placeholderKind(final ItemStack stack) {
        return isPlaceholder(stack) ? stack.getPersistentDataContainer().get(SLOT_KEY, PersistentDataType.STRING) : null;
    }

    /** 做一個空瓶提示（放在空著的藥劑欄）。 */
    ItemStack placeholder(final Player player, final FlaskKind kind) {
        final ItemStack stack = ItemStack.of(Material.PAPER); // 紙沒有右鍵的用途（空玻璃瓶右鍵水會裝水）
        final String key = slotKey(player, kind);
        final String color = kind.color();
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<" + color + ">" + kind.displayName() + "藥劑欄（空的）"));
            meta.lore(List.of(
                    ItemRenderer.line("<gray>把<" + color + ">" + kind.displayName() + "藥劑</" + color + ">放這裡，按 <white>" + key
                            + "</white> 喝"),
                    ItemRenderer.line("<gray>撿到或買到" + kind.displayName() + "藥劑時，這格空著就會自動放進來"),
                    ItemRenderer.line("<dark_gray>這格只能放" + kind.displayName() + "藥劑")));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(SLOT_KEY, PersistentDataType.STRING, kind.key());
        });
        stack.setData(DataComponentTypes.ITEM_MODEL, BOTTLE);
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(kind.key()).build());
        return stack;
    }

    /** 這一格（快捷欄索引）是藥劑欄的話，做一個它的空瓶提示；不是藥劑欄回傳 null。 */
    public ItemStack placeholderFor(final Player player, final int hotbarIndex) {
        final FlaskKind kind = hotbarIndex >= 0 && hotbarIndex < 9 ? kindAt(player, hotbarIndex) : null;
        return kind == null ? null : placeholder(player, kind);
    }

    /** 這個角色的等級喝得了這瓶嗎（自動放進藥劑欄只放喝得了的）。 */
    private boolean usable(final Player player, final FlaskBase base) {
        return base != null && plugin.players().get(player).level() >= base.level();
    }

    /** 這件東西是哪一種藥劑（不是藥劑、或 flasks.yml 裡沒有這種回傳 null）。 */
    public FlaskKind kindOf(final ItemStack stack) {
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        return base == null ? null : base.kind();
    }

    /** 藥劑欄是空的（沒東西或只有空瓶提示）。 */
    private static boolean vacant(final ItemStack stack) {
        return stack == null || stack.isEmpty() || isPlaceholder(stack);
    }

    private final java.util.Set<UUID> ensureQueued = new java.util.HashSet<>();
    private int minuteTicks;

    /** 下一個 tick 把空瓶提示補好（同一個 tick 只排一次）。 */
    public void ensureSoon(final Player player) {
        if (ensureQueued.add(player.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                ensureQueued.remove(player.getUniqueId());
                if (player.isOnline()) {
                    ensureSlots(player);
                }
            });
        }
    }

    /**
     * 空瓶提示：空著的藥劑欄放空瓶、種類不對的空瓶換掉；其他地方（含游標）跑出來的空瓶刪掉。
     * 只管走藥劑系統的玩家（創造模式的管理員不動）。回傳有沒有改東西。
     */
    public boolean ensureSlots(final Player player) {
        if (!eligible(player)) {
            return false;
        }
        final PlayerInventory inventory = player.getInventory();
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack item = inventory.getItem(slot);
            final FlaskKind kind = slot < 9 ? kindAt(player, slot) : null;
            if (kind == null) {
                if (isPlaceholder(item)) {
                    inventory.setItem(slot, null);
                    changed = true;
                }
            } else if (item == null || item.isEmpty() || isPlaceholder(item) && !kind.key().equals(placeholderKind(item))) {
                inventory.setItem(slot, placeholder(player, kind));
                changed = true;
            }
        }
        if (isPlaceholder(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            changed = true;
        }
        return changed;
    }

    /**
     * 藥劑欄整理一次（登入、換角色）：放錯的東西（別的物品、另一種藥劑）搬到背包，藥劑欄空著就從背包挑最好的那瓶放進去，
     * 還空著的放空瓶。以前兩格都可以放任何東西，舊角色第一次登入會整理成這個樣子。回傳有沒有改東西。
     */
    boolean organize(final Player player) {
        if (!eligible(player)) {
            return false;
        }
        final PlayerInventory inventory = player.getInventory();
        final List<String> moved = new ArrayList<>();
        for (final FlaskKind kind : FlaskKind.values()) {
            final int slot = slotFor(player, kind);
            final ItemStack item = inventory.getItem(slot);
            if (vacant(item) || kindOf(item) == kind) {
                continue;
            }
            if (moveOut(player, slot)) {
                moved.add(plainName(item));
            }
        }
        final boolean placed = autoPlace(player, false);
        final boolean marked = ensureSlots(player);
        if (!moved.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<gray>快捷欄 <white>" + slotText(player) + "</white> 是藥劑欄，只能放對應的藥劑："
                    + "原本放在那裡的 <white>" + ItemRenderer.escape(String.join("、", moved)) + "</white> 搬到背包了"));
        }
        return !moved.isEmpty() || placed || marked;
    }

    /**
     * 把藥劑欄上放錯的東西搬走：另一種藥劑放到它自己的藥劑欄（空著的話），其他照撿東西的順序收（主背包→隨身背包，
     * 開著的那一頁不動）。搬不動回傳 false（留在原地）。
     */
    private boolean moveOut(final Player player, final int slot) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack item = inventory.getItem(slot);
        inventory.setItem(slot, null);
        final ItemStack rest;
        if (plugin.loot() != null) {
            rest = plugin.loot().stow(player, plugin.players().get(player), item);
        } else {
            ItemStack left = item;
            for (int target = 9; target < 36 && left != null; target++) {
                if (vacant(inventory.getItem(target))) {
                    inventory.setItem(target, left);
                    left = null;
                }
            }
            rest = left;
        }
        if (rest != null && !rest.isEmpty()) {
            inventory.setItem(slot, rest); // 放不下：留在原地
            return false;
        }
        return true;
    }

    private static String plainName(final ItemStack stack) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stack.effectiveName());
    }

    /**
     * 撿到、買到一瓶藥劑：那一種的藥劑欄空著就直接放進去（照 PoE）。放進去了回傳 true（呼叫端就不用再收進背包）。
     */
    public boolean equipIfEmpty(final Player player, final ItemStack stack) {
        if (!canEquip(player, stack)) {
            return false;
        }
        final FlaskKind kind = kindOf(stack);
        player.getInventory().setItem(slotFor(player, kind), stack.asOne());
        announcePlaced(player, kind);
        return true;
    }

    /** 這瓶藥劑可以直接放進空著的藥劑欄嗎（等級喝得了的才放）。 */
    public boolean canEquip(final Player player, final ItemStack stack) {
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        return base != null && stack.getAmount() == 1 && eligible(player) && usable(player, base)
                && vacant(player.getInventory().getItem(slotFor(player, base.kind())));
    }

    /**
     * 藥劑欄空著的話，從背包（主背包、快捷欄其他格）挑最好的那瓶放進去：等級喝得了的裡面需求等級最高的，
     * 一樣的話充能多的。回傳有沒有放。
     */
    public boolean autoPlace(final Player player, final boolean announce) {
        if (!eligible(player)) {
            return false;
        }
        final PlayerInventory inventory = player.getInventory();
        boolean placed = false;
        for (final FlaskKind kind : FlaskKind.values()) {
            final int slot = slotFor(player, kind);
            if (!vacant(inventory.getItem(slot))) {
                continue;
            }
            int best = -1;
            int bestLevel = -1;
            int bestCharges = -1;
            for (int source = 0; source < 36; source++) {
                if (isFlaskSlot(player, source)) {
                    continue;
                }
                final ItemStack item = inventory.getItem(source);
                final FlaskData data = read(item).orElse(null);
                final FlaskBase base = data == null ? null : config.base(data.base());
                if (base == null || base.kind() != kind || item.getAmount() != 1 || !usable(player, base)) {
                    continue;
                }
                if (base.level() > bestLevel || base.level() == bestLevel && data.charges() > bestCharges) {
                    best = source;
                    bestLevel = base.level();
                    bestCharges = data.charges();
                }
            }
            if (best >= 0) {
                inventory.setItem(slot, inventory.getItem(best));
                inventory.setItem(best, null);
                placed = true;
                if (announce) {
                    announcePlaced(player, kind);
                }
            }
        }
        return placed;
    }

    private void announcePlaced(final Player player, final FlaskKind kind) {
        final PlayerState state = plugin.players().get(player);
        state.notice("<" + kind.color() + ">" + kind.displayName() + "藥劑放進快捷欄 " + slotKey(player, kind) + "<gray>（按 " + slotKey(player, kind) + " 喝）",
                now() + 60);
        plugin.players().display(player, state);
    }

    /** 每兩秒：線上玩家的空瓶提示補好（萬一有哪裡漏掉，例如交易、上架把藥劑欄的藥劑拿走了）。 */
    void sweep() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            ensureSlots(player);
        }
    }

    /** 2026-10-27：一輪 2 秒（40 tick ÷ TICK_INTERVAL 次）。 */
    private final tw.exilecore.core.Staggered sweepRound = new tw.exilecore.core.Staggered(40 / TICK_INTERVAL);

    /** 這一次（每 TICK_INTERVAL tick）補一小批人的空瓶提示。 */
    private void sweepSlice() {
        final List<UUID> online = new ArrayList<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        for (final UUID id : sweepRound.next(online)) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                ensureSlots(player);
            }
        }
    }

    /** 登出、死亡、換角色：回復中的效果清掉。 */
    public void forget(final Player player) {
        active.remove(player.getUniqueId());
        lastUse.remove(player.getUniqueId());
    }

    /** 登出：連檢查點的紀錄一起清。 */
    void quit(final Player player) {
        forget(player);
        lastCheckpoint.remove(player.getUniqueId());
        lastCheckpointTick.remove(player.getUniqueId());
    }

    // ---- 掉落 ----

    /** 怪物掉落的一瓶藥劑（滿充能）；沒有能掉的回傳 null。 */
    public ItemStack createDrop(final int monsterLevel, final RandomGenerator random) {
        final ItemStack charm = charms == null ? null : charms.maybeDrop(monsterLevel, random);
        if (charm != null) {
            return charm;
        }
        final FlaskBase base = config.rollDrop(monsterLevel, random);
        if (base == null) {
            return null;
        }
        // 2026-10：照 PoE2，掉落的藥劑有機會是魔法的（帶一前綴一後綴，未鑑定）
        final Rarity rarity = random.nextDouble() < DROP_MAGIC_CHANCE ? Rarity.MAGIC : Rarity.NORMAL;
        final ItemStack stack = create(base, Integer.MAX_VALUE, rarity, monsterLevel);
        if (rarity == Rarity.MAGIC && plugin.items() != null) {
            plugin.items().read(stack).ifPresent(data -> {
                data.setIdentified(false);
                plugin.items().update(stack, data);
            });
        }
        return stack;
    }
}
