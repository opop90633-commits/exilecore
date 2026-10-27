package tw.exilecore.flask;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 護符（2026-10）：裝進角色面板的護符欄就生效（不佔背包），條件成立時自動用掉充能觸發
 * （被凍結、點燃、感電、流血、中毒、暈眩時解除並短暫免疫；被某種元素擊中時抗性提高；擊殺稀有怪物時稀有度提高…）。
 * 跟藥劑一樣殺怪充能、回營地與碰到檢查點補滿。護符欄的格數：基本 {@code base_slots}，加上裝備的「+x 護符欄位」（charm.slots），
 * 最多 {@link PlayerState#CHARM_SLOTS} 格；存在 {@link PlayerState#jewellery()} 的 {@link PlayerState#CHARM_SLOT} 起那幾格。
 * 舊版放在背包最上面一排（第 9–11 格）的護符，第一次碰到時自動搬進護符欄。設定在 flasks.yml 的 charms 區塊。
 */
public final class CharmService implements Listener {

    /** 屬性：多幾格護符欄。 */
    public static final String STAT_SLOTS = "charm.slots";

    /** 觸發條件（2026-10-26：加上 curse 被詛咒、slow 受到緩速，照 PoE2 的淨化護符、銀護符）。 */
    static final Set<String> TRIGGERS = Set.of("freeze", "ignite", "shock", "chill", "bleed", "poison", "stun", "electrocute",
            "hit_fire", "hit_cold", "hit_lightning", "hit_chaos", "hit_physical", "hit_unique", "kill_rare", "low_life", "curse", "slow");

    /** 2026-10-26：開關（FLAT 1）：免疫詛咒（PoE2 淨化護符的 Grants Immunity to Curses）。 */
    public static final String FLAG_CURSE_IMMUNE = "flag.curse_immune";

    /** 一種護符（retired：2026-10-26 拿掉的護符，舊物品照常能用，不掉落、不列在清單）。 */
    public record CharmBase(String id, String name, String trigger, boolean cure, int level, double duration, int charges, int perUse,
                     List<Modifier> effects, Material material, String description, boolean retired) {

        public CharmBase(final String id, final String name, final String trigger, final boolean cure, final int level, final double duration,
                         final int charges, final int perUse, final List<Modifier> effects, final Material material, final String description) {
            this(id, name, trigger, cure, level, duration, charges, perUse, effects, material, description, false);
        }
    }

    record CharmData(String base, int charges) {
    }

    private final ExileCore plugin;
    private final NamespacedKey key;
    private final Map<String, CharmBase> bases = new LinkedHashMap<>();
    /** 舊版的護符欄（背包最上面一排的前三格）：裡面的護符搬進角色面板。 */
    private static final int[] LEGACY_SLOTS = {9, 10, 11};
    /** 舊版背包裡的護符搬過了沒（角色進度）。 */
    private static final String PROGRESS_MIGRATED = "charm.panel";
    private int baseSlots = 2;
    private double dropChance = 0.25;

    CharmService(final ExileCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "charm");
    }

    // ---- 設定 ----

    void load(final Path dataFolder) throws IOException {
        bases.clear();
        final Path file = dataFolder.resolve(FlaskConfig.FILE);
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        if (!root.has("charms")) {
            return;
        }
        final YamlNode node = root.node("charms");
        // inventory_slots（舊版：護符放在背包的哪幾格）不再使用：護符改裝在角色面板
        baseSlots = Math.max(0, node.integer("base_slots", 0)); // 2026-10-26 照 PoE2：角色本身 0 格，只有腰帶給（這次之前預設 2）
        dropChance = Math.max(0, Math.min(1, node.decimal("drop_chance", 0.25)));
        final YamlNode section = node.node("bases");
        for (final String id : section.keys()) {
            final YamlNode b = section.node(id);
            final String trigger = b.requireString("trigger").trim().toLowerCase(Locale.ROOT);
            if (!TRIGGERS.contains(trigger)) {
                throw new IllegalArgumentException(b.path() + ".trigger：不認得「" + trigger + "」（" + String.join("、", TRIGGERS) + "）");
            }
            final List<Modifier> effects = new ArrayList<>();
            for (final YamlNode effect : b.list("effects")) {
                effects.add(tw.exilecore.item.AffixMod.parse(effect).toModifier(Map.of(), "護符 " + b.requireString("name")));
            }
            final int charges = b.integer("charges", 40);
            final int perUse = b.integer("per_use", 20);
            if (charges < 1 || perUse < 1 || perUse > charges) {
                throw new IllegalArgumentException(b.path() + "：1 ≤ per_use ≤ charges");
            }
            Material material = Material.matchMaterial(b.string("icon", "AMETHYST_SHARD"));
            if (material == null || !material.isItem()) {
                material = Material.AMETHYST_SHARD;
            }
            bases.put(id, new CharmBase(id, b.requireString("name"), trigger, b.bool("cure", false), Math.max(1, b.integer("level", 1)),
                    Math.max(0.5, b.decimal("duration", 4)), charges, perUse, List.copyOf(effects), material, b.string("description", ""),
                    b.bool("retired", false)));
        }
    }

    int size() {
        return bases.size();
    }

    /** 現役的護符基底（2026-10-24：管理網頁的發放清單；2026-10-26：拿掉的 retired 不列，用 {@link #base(String)} 還是找得到）。 */
    public java.util.Collection<CharmBase> bases() {
        final List<CharmBase> active = new ArrayList<>();
        for (final CharmBase base : bases.values()) {
            if (!base.retired()) {
                active.add(base);
            }
        }
        return java.util.Collections.unmodifiableList(active);
    }

    /** 這個代號的護符基底（沒有回傳 null）。 */
    public CharmBase base(final String id) {
        return id == null ? null : bases.get(id.trim().toLowerCase(Locale.ROOT));
    }

    // ---- 物品 ----

    Optional<CharmData> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        final String text = stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (text == null) {
            return Optional.empty();
        }
        final int split = text.lastIndexOf(';');
        try {
            return split <= 0 ? Optional.empty()
                    : Optional.of(new CharmData(text.substring(0, split), Integer.parseInt(text.substring(split + 1).trim())));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 所有護符的名稱（2026-10-20：快捷欄的增益倒數要把字收進資源包）。 */
    public java.util.List<String> names() {
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (final CharmBase base : bases.values()) {
            names.add(base.name());
        }
        return names;
    }

    public boolean isCharm(final ItemStack stack) {
        return read(stack).isPresent();
    }

    /** 這個護符的需求等級（不是護符回傳 1）。 */
    public int level(final ItemStack stack) {
        final CharmData data = read(stack).orElse(null);
        final CharmBase base = data == null ? null : bases.get(data.base());
        return base == null ? 1 : base.level();
    }

    /** 這個護符的名字（不是護符回傳 null）。 */
    public String name(final ItemStack stack) {
        final CharmData data = read(stack).orElse(null);
        final CharmBase base = data == null ? null : bases.get(data.base());
        return base == null ? null : base.name();
    }

    public ItemStack create(final CharmBase base, final int charges) {
        return create(base, charges, Rarity.NORMAL, base.level());
    }

    /** 做一顆護符（2026-10：護符是物品引擎的基底，魔法的帶一前綴一後綴的 PoE2 護符詞綴）。 */
    public ItemStack create(final CharmBase base, final int charges, final Rarity rarity, final int itemLevel) {
        final ItemBase itemBase = plugin.items() == null ? null : plugin.items().repository().base(base.id());
        if (itemBase == null || !itemBase.isCharm()) {
            final ItemStack stack = ItemStack.of(base.material());
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

    /** 這顆護符的物品資料（詞綴）；舊版沒有資料的第一次碰到時補上普通的資料。沒有物品引擎回傳 null。 */
    private ItemData itemData(final ItemStack stack, final CharmBase base) {
        if (plugin.items() == null) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            return data.get();
        }
        final ItemBase itemBase = plugin.items().repository().base(base.id());
        if (itemBase == null || !itemBase.isCharm()) {
            return null;
        }
        final ItemData fresh = plugin.items().generator().generate(itemBase, Rarity.NORMAL, base.level(), plugin.items().random());
        plugin.items().update(stack, fresh);
        return fresh;
    }

    /** 這顆護符詞綴算出來的數值（沒有詞綴、沒有物品引擎都是 {@link FlaskMods#NONE}）。 */
    FlaskMods mods(final ItemStack stack, final CharmBase base) {
        final ItemData data = itemData(stack, base);
        return data == null ? FlaskMods.NONE : FlaskMods.of(plugin.items().repository(), data);
    }

    private int maxCharges(final ItemStack stack, final CharmBase base) {
        return mods(stack, base).maxCharges(base.charges());
    }

    private void writeCharges(final ItemStack stack, final CharmBase base, final int charges) {
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + Math.max(0, charges)));
    }

    private void render(final ItemStack stack, final CharmBase base, final int charges) {
        final ItemData data = itemData(stack, base);
        if (data == null) {
            renderLegacy(stack, base, charges);
            return;
        }
        final int clamped = Math.max(0, Math.min(FlaskMods.of(plugin.items().repository(), data).maxCharges(base.charges()), charges));
        writeCharges(stack, base, clamped);
        plugin.items().rerender(stack, data);
    }

    /** 物品引擎畫完詞綴之後的補畫：護符的說明放在詞綴前面、充能條。 */
    void decorate(final ItemStack stack, final ItemData data) {
        final CharmBase base = bases.get(data.base());
        if (base == null) {
            return;
        }
        final FlaskMods mods = FlaskMods.of(plugin.items().repository(), data);
        final int max = mods.maxCharges(base.charges());
        final int charges = Math.max(0, Math.min(max, read(stack).map(CharmData::charges).orElse(max)));
        final List<tw.exilecore.stats.Modifier> effects = effectsOf(base, affixModifiers(data));
        stack.editMeta(meta -> {
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(base, mods, charges, effects)) {
                lore.add(ItemRenderer.line(line));
            }
            final List<Component> affixLines = meta.lore();
            if (affixLines != null) {
                lore.addAll(affixLines);
            }
            meta.lore(tw.exilecore.core.text.TextWrap.lore(lore)); // 2026-10-26：長行自動換行
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + charges);
        });
        chargeBar(stack, max, charges);
    }

    private static void chargeBar(final ItemStack stack, final int max, final int charges) {
        stack.setData(DataComponentTypes.MAX_DAMAGE, Math.max(1, max));
        stack.setData(DataComponentTypes.DAMAGE, Math.max(0, Math.min(max, max - charges)));
        // 原版的耐久度、屬性那幾行（英文）不要顯示
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.DAMAGE, DataComponentTypes.MAX_DAMAGE, DataComponentTypes.ATTRIBUTE_MODIFIERS,
                        DataComponentTypes.UNBREAKABLE).build());
    }

    /** 沒有物品引擎（測試、模組沒啟用）時的畫法。 */
    private void renderLegacy(final ItemStack stack, final CharmBase base, final int charges) {
        final int clamped = Math.max(0, Math.min(base.charges(), charges));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#c9a0ff>" + ItemRenderer.escape(base.name())));
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(base, FlaskMods.NONE, clamped, base.effects())) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(tw.exilecore.core.text.TextWrap.lore(lore)); // 2026-10-26：長行自動換行
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + clamped);
        });
        chargeBar(stack, base.charges(), clamped);
    }

    private List<String> lore(final CharmBase base, final FlaskMods mods, final int charges, final List<tw.exilecore.stats.Modifier> effects) {
        final List<String> lines = new ArrayList<>();
        lines.add("<gray>" + triggerText(base.trigger()) + "：自動觸發" + (base.cure() ? switch (base.trigger()) {
            case "curse" -> "，解除身上的詛咒";
            case "slow" -> "，解除身上的緩速";
            default -> "，解除那個異常狀態";
        } : ""));
        final double seconds = base.duration() * mods.duration();
        final String number = mods.duration() != 1 ? "<#8888ff>" : "<white>";
        if (!effects.isEmpty()) {
            lines.add(String.format(Locale.ROOT, "<gray>之後 %s%s</%s> 秒內：", number, trim(seconds), mods.duration() != 1 ? "#8888ff" : "white"));
            for (final String effect : tw.exilecore.skill.ModifierText.describeAll(effects)) {
                lines.add("<#8fd3ff>　" + effect);
            }
        }
        if (mods.guard() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>觸發時獲得 <#8888ff>%d</#8888ff> 點守護（%s 秒內先替你吃掉傷害）", Math.round(mods.guard()), trim(seconds)));
        }
        if (mods.lifeOnUse() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>觸發時回復 <#8888ff>%d</#8888ff> 生命", Math.round(mods.lifeOnUse())));
        }
        if (mods.manaOnUse() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>觸發時回復 <#8888ff>%d</#8888ff> 魔力", Math.round(mods.manaOnUse())));
        }
        if (!base.description().isBlank()) {
            lines.add("<gray>" + ItemRenderer.escape(base.description()));
        }
        final int max = mods.maxCharges(base.charges());
        final int perUse = mods.perUse(base.perUse());
        lines.add("<gray>充能 <white>" + charges + "</white>／" + (mods.maxCharges() != 1 ? "<#8888ff>" : "<white>") + max
                + (mods.maxCharges() != 1 ? "</#8888ff>" : "</white>") + "，每次用 " + (mods.chargesUsed() != 1 ? "<#8888ff>" : "<white>") + perUse
                + (mods.chargesUsed() != 1 ? "</#8888ff>" : "</white>"));
        lines.add("<gray>需求：等級 <white>" + base.level());
        lines.add("");
        // 2026-10-26 照 PoE2：護符欄由腰帶給（這次之前寫「基本 2 格、+1 護符欄位可以多開」）
        lines.add("<gray>裝進角色面板的護符欄才會生效（不佔背包）：" + (baseSlots > 0 ? "基本 " + baseSlots + " 格，" : ""));
        lines.add("<gray>護符欄由腰帶給（每條腰帶 1–3 格），最多 " + PlayerState.CHARM_SLOTS + " 格");
        lines.add("<gray>殺怪充能；回營地、碰到檢查點、重生時補滿");
        lines.add("<yellow>右鍵：裝進護符欄<gray>（或在角色面板點它）");
        return lines;
    }

    private static String triggerText(final String trigger) {
        return switch (trigger) {
            case "freeze" -> "被凍結時";
            case "ignite" -> "被點燃時";
            case "shock" -> "感電時";
            case "chill" -> "被冰緩時";
            case "bleed" -> "流血時";
            case "poison" -> "中毒時";
            case "stun" -> "被重暈時";
            case "electrocute" -> "被電擊時";
            case "hit_fire" -> "受到火焰擊中時";
            case "hit_cold" -> "受到冰冷擊中時";
            case "hit_lightning" -> "受到閃電擊中時";
            case "hit_chaos" -> "受到混沌擊中時";
            case "hit_physical" -> "受到物理擊中時";
            case "hit_unique" -> "被傳奇怪物或 Boss 擊中時";
            case "kill_rare" -> "擊殺稀有或傳奇怪物時";
            case "low_life" -> "被擊中後生命低於一半時";
            case "curse" -> "被詛咒時";
            case "slow" -> "受到緩速時";
            default -> trigger;
        };
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private void setCharges(final PlayerInventory inventory, final int slot, final CharmBase base, final int charges) {
        final ItemStack stack = inventory.getItem(slot);
        if (stack == null) {
            return;
        }
        render(stack, base, charges);
        inventory.setItem(slot, stack);
    }

    /** 護符欄第 index 格的充能改成 charges（存在角色資料裡）。 */
    private void setCharges(final PlayerState state, final int index, final CharmBase base, final int charges) {
        final ItemStack stack = state.jewellery(PlayerState.CHARM_SLOT + index);
        if (stack == null) {
            return;
        }
        final ItemStack copy = stack.clone();
        render(copy, base, charges);
        state.setJewellery(PlayerState.CHARM_SLOT + index, copy);
    }

    // ---- 護符欄（角色面板） ----

    /** 這個玩家現在開著幾格護符欄（基本格數加裝備的「+x 護符欄位」，最多 {@link PlayerState#CHARM_SLOTS}）。 */
    public int activeCount(final Player player) {
        final double extra = plugin.players().sheet(player).get(STAT_SLOTS);
        return (int) Math.max(0, Math.min(PlayerState.CHARM_SLOTS, baseSlots + Math.round(extra)));
    }

    /** 2026-10-27（塗油限定 巨著）：開著的前 open 格裡裝了幾個護符。 */
    static int filled(final PlayerState state, final int open) {
        int filled = 0;
        for (int i = 0; i < open; i++) {
            final ItemStack stack = charmAt(state, i);
            filled += stack == null || stack.isEmpty() ? 0 : 1;
        }
        return filled;
    }

    /** 護符欄第 index 格裝的護符（沒有是 null）。 */
    public static ItemStack charmAt(final PlayerState state, final int index) {
        return state.jewellery(PlayerState.CHARM_SLOT + index);
    }

    /**
     * 把背包第 inventorySlot 格的護符裝進護符欄：第一個開著的空格；都滿了換掉第一格（換下來的放回那一格）。
     *
     * @return 錯誤訊息；成功回傳 null
     */
    public String equip(final Player player, final int inventorySlot) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return "先建立角色（/char）";
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(inventorySlot);
        final CharmData data = read(stack).orElse(null);
        final CharmBase base = data == null ? null : bases.get(data.base());
        if (base == null) {
            return "這不是護符";
        }
        final int open = activeCount(player);
        if (open <= 0) {
            return "沒有開著的護符欄（護符欄由腰帶給：先裝一條腰帶）"; // 2026-10-26 照 PoE2
        }
        int target = 0;
        for (int i = 0; i < open; i++) {
            if (charmAt(state, i) == null) {
                target = i;
                break;
            }
        }
        final ItemStack previous = charmAt(state, target);
        final ItemStack single = stack.clone();
        single.setAmount(1);
        state.setJewellery(PlayerState.CHARM_SLOT + target, single);
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
            inventory.setItem(inventorySlot, stack);
            if (previous != null) {
                giveBack(player, state, previous);
            }
        } else {
            inventory.setItem(inventorySlot, previous);
        }
        plugin.players().markDirty(player);
        return null;
    }

    /** 拿在手上右鍵：裝進護符欄。不是護符回傳 false（照原本的右鍵）。 */
    public boolean equipFromHand(final Player player, final ItemStack stack, final org.bukkit.inventory.EquipmentSlot hand) {
        if (!isCharm(stack) || hand != org.bukkit.inventory.EquipmentSlot.HAND) {
            return false;
        }
        final String name = name(stack);
        final String problem = equip(player, player.getInventory().getHeldItemSlot());
        if (problem != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        } else {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c9a0ff>" + ItemRenderer.escape(name) + "<gray> 裝進護符欄（角色面板 /panel）"));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
        return true;
    }

    /** 護符欄第 index 格卸下回背包；背包滿了回傳錯誤訊息。 */
    public String unequip(final Player player, final int index) {
        final PlayerState state = plugin.players().get(player);
        final ItemStack charm = charmAt(state, index);
        if (charm == null) {
            return null;
        }
        if (!player.getInventory().addItem(charm.clone()).isEmpty()) {
            return "背包滿了";
        }
        state.setJewellery(PlayerState.CHARM_SLOT + index, null);
        plugin.players().markDirty(player);
        return null;
    }

    /** 換下來的護符放回背包；放不下就放隨身背包，再不行掉在腳邊。 */
    private void giveBack(final Player player, final PlayerState state, final ItemStack stack) {
        if (player.getInventory().addItem(stack).isEmpty()) {
            return;
        }
        if (state.bag() != null && state.bag().usable() && state.bag().add(stack)) {
            return;
        }
        plugin.personalDrops().dropForever(player, stack);
    }

    /** 舊版放在背包最上面一排（第 9–11 格）的護符搬進護符欄（每個角色只搬一次）。 */
    private void migrateLegacy(final Player player, final PlayerState state) {
        if (state.progress().containsKey(PROGRESS_MIGRATED)) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        int moved = 0;
        for (final int slot : LEGACY_SLOTS) {
            final ItemStack stack = inventory.getItem(slot);
            if (!isCharm(stack)) {
                continue;
            }
            for (int i = 0; i < PlayerState.CHARM_SLOTS; i++) {
                if (charmAt(state, i) == null) {
                    state.setJewellery(PlayerState.CHARM_SLOT + i, stack);
                    inventory.setItem(slot, null);
                    moved++;
                    break;
                }
            }
        }
        plugin.characters().setProgress(player, PROGRESS_MIGRATED, "1");
        if (moved > 0) {
            plugin.players().markDirty(player);
            player.sendMessage(plugin.text().prefixed("<#c9a0ff>護符改成裝在角色面板的護符欄（不佔背包）：<gray>背包裡的 " + moved
                    + " 個護符已經搬過去了（/panel 看）"));
        }
    }

    private boolean eligible(final Player player) {
        return !bases.isEmpty() && plugin.flasks() != null && plugin.flasks().eligible(player);
    }

    /**
     * 觸發：同一種護符只用護符欄裡排最前面、充能夠的那一個（用乾了才輪到下一個同種的）；不同種的護符各自觸發。
     * 2026-10：不解除異常的護符（受到擊中、擊殺稀有、低血）效果還在的時候不再觸發，不會一直扣充能；
     * 所以裝兩個一樣的不會疊兩倍，而是一個用乾了接著用另一個，拉長能觸發的次數。
     */
    void trigger(final Player player, final String trigger) {
        if (!eligible(player)) {
            return;
        }
        if (tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneKeys.FLAG_OASIS)) {
            return; // 2026-10-26 照 PoE2 基石：幻景綠洲——無法使用護符
        }
        final PlayerState state = plugin.players().get(player);
        final int level = state.level();
        final int open = activeCount(player);
        final Set<String> fired = new HashSet<>();
        Set<String> chained = null;
        for (int index = 0; index < open; index++) {
            final ItemStack charm = charmAt(state, index);
            final CharmData data = read(charm).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base == null || !base.trigger().equals(trigger) || base.level() > level || fired.contains(base.id())) {
                continue;
            }
            final FlaskMods mods = mods(charm, base);
            final List<tw.exilecore.stats.Modifier> effects = effectsOf(base, affixModifiers(charm)); // 2026-10-25：傳奇護符照自己的效果
            final double usedGlobal = plugin.players().sheet(player).increased(tw.exilecore.stats.StatKeys.CHARM_CHARGES_USED, java.util.Set.of());
            final int perUse = Math.max(1, (int) Math.round(mods.perUse(base.perUse()) * Math.max(0, 1 + usedGlobal)));
            // 2026-10-26（昇華・心靈護符，PoE2）：充能不足時可以改消耗 25% 最大魔力來觸發
            final double manaShare = tw.exilecore.ascendancy.AscendancyEffects.charmManaShare(player);
            final boolean byMana = data.charges() < perUse && manaShare > 0 && state.mana() >= state.maxMana() * manaShare / 100.0 && state.maxMana() > 0;
            if (data.charges() < perUse && !byMana) {
                continue;
            }
            if (!base.cure() && !effects.isEmpty() && plugin.skills() != null && plugin.skills().buffActive(player, "charm." + base.id())) {
                fired.add(base.id()); // 效果還在：這一種先不觸發（後面同種的也不用）
                continue;
            }
            fired.add(base.id());
            final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
            final double free = Math.max(0, Math.min(1, sheet.get(tw.exilecore.stats.StatKeys.CHARM_FREE_CHANCE) / 100.0));
            if (byMana) {
                state.setMana(state.mana() - state.maxMana() * manaShare / 100.0);
            } else if (free <= 0 || plugin.items().random().nextDouble() >= free) {
                setCharges(state, index, base, data.charges() - perUse);
            }
            if (base.cure() && plugin.ailments() != null) {
                final AilmentType type = ailmentOf(trigger);
                if (type != null) {
                    plugin.ailments().cure(player, type);
                }
                if ("slow".equals(trigger)) {
                    clearSlows(player); // 2026-10-26：銀護符：身上的緩速解掉
                }
            }
            if (base.cure() && "curse".equals(trigger) && plugin.combat() != null) {
                plugin.combat().debuffs().removeCurses(player.getUniqueId()); // 2026-10-26：淨化護符：身上的詛咒解掉
            }
            final long now = plugin.getServer().getCurrentTick();
            state.markRecent(tw.exilecore.stats.Recently.CHARM, now); // 「最近用過護符」
            // 持續時間：護符自己的詞綴 × 全域的「護符效果持續時間」
            final int ticks = (int) Math.round(base.duration() * mods.duration()
                    * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.StatKeys.CHARM_DURATION, java.util.Set.of())) * 20);
            // 2026-10-26 照 PoE2 天賦樹：「你身上的護符增加 x% 效果」：增益的數值、守護的量放大（開關不放大）
            // 2026-10-27 照 PoE2（塗油限定 巨著）：每個開著但空著的護符欄再增加 x%
            final double charmEffect = sheet.increased(tw.exilecore.stats.TreeEffectKeys.CHARM_EFFECT, java.util.Set.of())
                    + tw.exilecore.stats.AnointKeys.emptyCharmEffect(sheet.flat(tw.exilecore.stats.AnointKeys.CHARM_EFFECT_PER_EMPTY, java.util.Set.of()),
                    open, filled(state, open));
            if (!effects.isEmpty() && plugin.skills() != null) {
                final List<tw.exilecore.stats.Modifier> applied = charmEffect == 0 ? effects : new java.util.ArrayList<>(effects);
                if (charmEffect != 0) {
                    applied.addAll(tw.exilecore.stats.TreeEffectKeys.amplify(effects, charmEffect, base.name()));
                }
                plugin.skills().buff(player, "charm." + base.id(), base.name(), ticks, applied);
            }
            // 護符詞綴：守護、回復生命／魔力
            if (mods.guard() > 0) {
                state.addGuard(mods.guard() * Math.max(0, 1 + charmEffect), now + ticks);
            }
            if (mods.lifeOnUse() > 0) {
                state.setLife(Math.min(state.maxLife(), state.life() + mods.lifeOnUse() * Math.max(0, 1 + charmEffect)));
            }
            if (mods.manaOnUse() > 0) {
                state.setMana(Math.min(state.maxMana(), state.mana() + mods.manaOnUse() * Math.max(0, 1 + charmEffect)));
            }
            // 2026-10-24（傳奇護符：岳息、瓦拉可之吼）：使用時獲得戰星
            if (plugin.combat() != null && plugin.combat().charges() != null) {
                for (final Map.Entry<String, Integer> grant : grants(charm).entrySet()) {
                    plugin.combat().charges().grant(player, grant.getKey(), grant.getValue());
                }
            }
            // 2026-10-26 照 PoE2 天賦：護符觸發時回復最大魔力的 x%；x% 機率再觸發另一個護符（不消耗充能）
            final double charmMana = sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.CHARM_MANA_ON_USE, java.util.Set.of());
            if (charmMana > 0) {
                state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * charmMana / 100.0));
            }
            if (chained == null && plugin.items().random().nextDouble() * 100 < sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.CHARM_CHAIN,
                    java.util.Set.of())) {
                chained = fired;
            }
            state.notice("<#c9a0ff>" + ItemRenderer.escape(base.name()) + "觸發", now + 40);
            plugin.players().display(player, state);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.4f);
        }
        if (chained != null) {
            freeOther(player, state, chained);
        }
    }

    /** 2026-10-26 照 PoE2 天賦：再觸發一個（還沒觸發過、效果不在的）護符，不消耗充能。 */
    private void freeOther(final Player player, final PlayerState state, final Set<String> skip) {
        for (int index = 0; index < activeCount(player); index++) {
            final ItemStack charm = charmAt(state, index);
            final CharmData data = read(charm).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base == null || skip.contains(base.id()) || base.cure() || base.level() > state.level()) {
                continue;
            }
            final List<tw.exilecore.stats.Modifier> effects = effectsOf(base, affixModifiers(charm));
            if (effects.isEmpty() || plugin.skills() == null || plugin.skills().buffActive(player, "charm." + base.id())) {
                continue;
            }
            final FlaskMods mods = mods(charm, base);
            final int ticks = (int) Math.round(base.duration() * mods.duration() * Math.max(0.1, 1 + plugin.players().sheet(player)
                    .increased(tw.exilecore.stats.StatKeys.CHARM_DURATION, java.util.Set.of())) * 20);
            plugin.skills().buff(player, "charm." + base.id(), base.name(), ticks, effects);
            state.notice("<#c9a0ff>" + ItemRenderer.escape(base.name()) + "也觸發了（不消耗充能）", plugin.getServer().getCurrentTick() + 40);
            plugin.players().display(player, state);
            return;
        }
    }

    /** 傳奇護符「使用時獲得一顆 X」（2026-10-24）：charm.local.grant.代號，FLAT 顆數。 */
    public static final String GRANT = "charm.local.grant.";

    /** 這顆護符使用時給的戰星（戰星代號 → 顆數）。 */
    private Map<String, Integer> grants(final ItemStack stack) {
        final Map<String, Integer> map = new LinkedHashMap<>();
        for (final tw.exilecore.stats.Modifier modifier : affixModifiers(stack)) {
            if (modifier.stat().startsWith(GRANT)) {
                map.merge(modifier.stat().substring(GRANT.length()), (int) Math.round(modifier.value()), Integer::sum);
            }
        }
        return map;
    }

    /** 護符上每一條詞綴的修飾器（傳奇的詞綴也是詞綴）。 */
    private List<tw.exilecore.stats.Modifier> affixModifiers(final ItemStack stack) {
        if (stack == null || plugin.items() == null) {
            return List.of();
        }
        final ItemData data = plugin.items().read(stack).orElse(null);
        return affixModifiers(data);
    }

    /**
     * 2026-10-25（照 PoE2：瓦拉可之吼的「+25% 閃電抗性」就是它的護符效果）：生效期間的效果——
     * 護符上有不是 flask.local／charm.local 的詞綴（傳奇護符的「+25% 冰冷抗性」這種）就用那些取代基底的效果，沒有就照基底。
     */
    private static List<tw.exilecore.stats.Modifier> effectsOf(final CharmBase base, final List<tw.exilecore.stats.Modifier> affixMods) {
        final List<tw.exilecore.stats.Modifier> own = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier modifier : affixMods) {
            if (!modifier.stat().startsWith("flask.local.") && !modifier.stat().startsWith("charm.local.")) {
                own.add(modifier);
            }
        }
        return own.isEmpty() ? base.effects() : own;
    }

    private List<tw.exilecore.stats.Modifier> affixModifiers(final ItemData data) {
        if (data == null || plugin.items() == null) {
            return List.of();
        }
        final List<tw.exilecore.stats.Modifier> list = new ArrayList<>();
        for (final tw.exilecore.item.RolledAffix rolled : data.affixes()) {
            final tw.exilecore.item.Affix affix = plugin.items().repository().affix(rolled.id());
            if (affix == null) {
                continue;
            }
            for (final tw.exilecore.item.AffixMod mod : affix.mods()) {
                list.add(mod.toModifier(rolled.vars(), affix.id()));
            }
        }
        return list;
    }

    /**
     * 2026-10-24（傳奇護符的「+25% 冰冷抗性」這種）：裝在護符欄的護符上，不是 flask.local／charm.local 的詞綴進角色屬性表。
     * 一般護符的詞綴都是只作用在那一顆上的，所以只有傳奇的會用到。不看開了幾格（算開幾格要讀屬性表，會繞回來）。
     */
    public List<tw.exilecore.stats.Modifier> globalModifiers(final Player player, final PlayerState state) {
        // 2026-10-25：照 PoE2 改成「護符生效期間」才有（effectsOf 併進觸發時的增益），裝著不再常駐；留著這個來源給 FlaskModule 註冊
        return List.of();
    }

    private static AilmentType ailmentOf(final String trigger) {
        return switch (trigger) {
            case "freeze" -> AilmentType.FREEZE;
            case "ignite" -> AilmentType.IGNITE;
            case "shock" -> AilmentType.SHOCK;
            case "chill" -> AilmentType.CHILL;
            case "bleed" -> AilmentType.BLEED;
            case "poison" -> AilmentType.POISON;
            case "stun" -> AilmentType.STUN;
            case "electrocute" -> AilmentType.ELECTROCUTE;
            default -> null;
        };
    }

    /** 2026-10-26：詛咒的免疫檢查掛上戰鬥模組了沒（戰鬥模組可能比藥劑模組晚啟用，所以在 tick 裡掛）。 */
    private boolean curseHooked;

    /** 2026-10-26：這個玩家身上有沒有緩速（冰緩、癱瘓、阻礙、減速地面、Boss 的減速藥水效果）。 */
    private boolean slowed(final Player player) {
        return plugin.ailments().slowed(player) || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
    }

    /** 2026-10-26：解掉身上的緩速（銀護符觸發、效果期間）。 */
    private void clearSlows(final Player player) {
        plugin.ailments().clearSlows(player);
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)) {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        }
    }

    /** 每幾個 tick 看一次：身上有異常狀態（2026-10-26：詛咒、緩速）就觸發對應的護符。 */
    void tick() {
        if (bases.isEmpty() || plugin.ailments() == null) {
            return;
        }
        if (!curseHooked && plugin.combat() != null && plugin.combat().debuffs() != null) {
            curseHooked = true;
            plugin.combat().debuffs().setCurseImmunity(id -> {
                final Player target = plugin.getServer().getPlayer(id);
                return target != null && plugin.players() != null && tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(target), FLAG_CURSE_IMMUNE);
            });
        }
        final Set<String> wanted = new HashSet<>();
        for (final CharmBase base : bases.values()) {
            if (ailmentOf(base.trigger()) != null || "curse".equals(base.trigger()) || "slow".equals(base.trigger())) {
                wanted.add(base.trigger());
            }
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!eligible(player)) {
                continue;
            }
            migrateLegacy(player, plugin.players().get(player));
            if (!hasAnyCharm(player)) {
                continue;
            }
            final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
            for (final String trigger : wanted) {
                if ("slow".equals(trigger)) {
                    if (!slowed(player)) {
                        continue;
                    }
                    if (tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_UNSLOWABLE)) {
                        clearSlows(player); // 效果期間：速度不受緩速影響（擋不掉的減速也直接解掉），不再觸發
                    } else {
                        trigger(player, trigger);
                    }
                    continue;
                }
                if ("curse".equals(trigger)) {
                    if (plugin.combat() == null || !plugin.combat().debuffs().cursed(player.getUniqueId(), plugin.getServer().getCurrentTick())) {
                        continue;
                    }
                    if (tw.exilecore.stats.StatKeys.flag(sheet, FLAG_CURSE_IMMUNE)) {
                        plugin.combat().debuffs().removeCurses(player.getUniqueId()); // 效果期間免疫詛咒
                    } else {
                        trigger(player, trigger);
                    }
                    continue;
                }
                if (plugin.ailments().has(player, ailmentOf(trigger))) {
                    trigger(player, trigger);
                }
            }
        }
    }

    private boolean hasAnyCharm(final Player player) {
        final PlayerState state = plugin.players().get(player);
        for (int index = 0; index < PlayerState.CHARM_SLOTS; index++) {
            if (charmAt(state, index) != null) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || event.attacker() instanceof Player || !event.result().hit()
                || event.result().blocked() || !hasAnyCharm(player)) {
            return;
        }
        final var dealt = event.result().dealt();
        if (dealt.get(DamageType.FIRE) > 0) {
            trigger(player, "hit_fire");
        }
        if (dealt.get(DamageType.COLD) > 0) {
            trigger(player, "hit_cold");
        }
        if (dealt.get(DamageType.LIGHTNING) > 0) {
            trigger(player, "hit_lightning");
        }
        if (dealt.get(DamageType.CHAOS) > 0) {
            trigger(player, "hit_chaos");
        }
        if (dealt.get(DamageType.PHYSICAL) > 0) {
            trigger(player, "hit_physical");
        }
        final LivingEntity attacker = event.attacker();
        if (attacker != null && plugin.monsters() != null) {
            plugin.monsters().data(attacker).ifPresent(data -> {
                if (data.rarity() == MonsterRarity.UNIQUE) {
                    trigger(player, "hit_unique");
                }
            });
        }
        final PlayerState state = plugin.players().get(player);
        if (state.maxLife() > 0 && state.life() - event.result().lifeDamage() < state.maxLife() / 2) {
            trigger(player, "low_life");
        }
    }

    // ---- 充能 ----

    void onKill(final Player player, final MonsterRarity rarity, final int charges) {
        if (!eligible(player)) {
            return;
        }
        addCharges(player, charges);
        if (rarity == MonsterRarity.RARE || rarity == MonsterRarity.UNIQUE) {
            trigger(player, "kill_rare");
        }
    }

    void addCharges(final Player player, final int amount) {
        if (amount <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int open = activeCount(player);
        // 2026-10-26 照 PoE2：也算「更多」（精妙符印更多 20% 護符充能）；這次之前只算增加
        final double global = plugin.players().sheet(player).apply(tw.exilecore.stats.StatKeys.CHARM_CHARGES_GAINED, 1.0, java.util.Set.of()) - 1;
        for (int index = 0; index < open; index++) {
            final ItemStack charm = charmAt(state, index);
            final CharmData data = read(charm).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base == null) {
                continue;
            }
            final FlaskMods mods = mods(charm, base);
            final int max = mods.maxCharges(base.charges());
            if (data.charges() < max) {
                int gained = Math.max(1, (int) Math.round(mods.gained(amount) * Math.max(0, 1 + global)));
                if (mods.chargeOnKill() > 0 && plugin.items().random().nextDouble() < mods.chargeOnKill()) {
                    gained++;
                }
                setCharges(state, index, base, Math.min(max, data.charges() + gained));
            }
        }
    }

    /** 每分鐘：護符欄裡有「每分鐘獲得 x 點充能」的護符（或裝備的全域詞綴）自動充能。 */
    void tickPerMinute() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!eligible(player)) {
                continue;
            }
            final PlayerState state = plugin.players().get(player);
            final double global = plugin.players().sheet(player).get(tw.exilecore.stats.StatKeys.CHARM_CHARGES_PER_MINUTE);
            final int open = activeCount(player);
            for (int index = 0; index < open; index++) {
                final ItemStack charm = charmAt(state, index);
                final CharmData data = read(charm).orElse(null);
                final CharmBase base = data == null ? null : bases.get(data.base());
                if (base == null) {
                    continue;
                }
                final FlaskMods mods = mods(charm, base);
                final double perMinute = mods.perMinute() + global;
                final int max = mods.maxCharges(base.charges());
                if (perMinute > 0 && data.charges() < max) {
                    setCharges(state, index, base, Math.min(max, data.charges() + (int) Math.round(perMinute)));
                }
            }
        }
    }

    /** 護符欄與背包裡的護符全部補滿；回傳補了幾個。 */
    int refill(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        int filled = 0;
        final PlayerState state = plugin.players().get(player);
        for (int index = 0; index < PlayerState.CHARM_SLOTS; index++) {
            final ItemStack charm = charmAt(state, index);
            final CharmData data = read(charm).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base == null) {
                continue;
            }
            final int max = maxCharges(charm, base);
            if (data.charges() < max) {
                setCharges(state, index, base, max);
                filled++;
            }
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack charm = inventory.getItem(slot);
            final CharmData data = read(charm).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base == null) {
                continue;
            }
            final int max = maxCharges(charm, base);
            if (data.charges() < max) {
                setCharges(inventory, slot, base, max);
                filled++;
            }
        }
        return filled;
    }

    // ---- 掉落 ----

    /** 藥劑掉落有 drop_chance 的機率換成護符（需求等級 ≤ 怪物等級的隨機一種）；沒有回傳 null。 */
    ItemStack maybeDrop(final int monsterLevel, final RandomGenerator random) {
        if (bases.isEmpty() || random.nextDouble() >= dropChance) {
            return null;
        }
        final List<CharmBase> list = new ArrayList<>();
        for (final CharmBase base : bases.values()) {
            if (base.level() <= monsterLevel && !base.retired()) { // 2026-10-26：拿掉的不掉
                list.add(base);
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        final CharmBase base = list.get(random.nextInt(list.size()));
        // 2026-10：照 PoE2，掉落的護符有機會是魔法的（帶一前綴一後綴，未鑑定）
        final Rarity rarity = random.nextDouble() < FlaskService.DROP_MAGIC_CHANCE ? Rarity.MAGIC : Rarity.NORMAL;
        final ItemStack stack = create(base, Integer.MAX_VALUE, rarity, monsterLevel);
        if (rarity == Rarity.MAGIC && plugin.items() != null) {
            plugin.items().read(stack).ifPresent(data -> {
                data.setIdentified(false);
                plugin.items().update(stack, data);
            });
        }
        return stack;
    }

    /** 指令用：做一個（找不到回傳 null）。 */
    ItemStack create(final String id) {
        final CharmBase base = bases.get(id);
        return base == null ? null : create(base, base.charges());
    }

    java.util.Collection<String> ids() {
        return bases.keySet();
    }
}
