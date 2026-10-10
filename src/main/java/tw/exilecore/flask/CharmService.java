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
import tw.exilecore.item.ItemRenderer;
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

    /** 觸發條件。 */
    static final Set<String> TRIGGERS = Set.of("freeze", "ignite", "shock", "chill", "bleed", "poison", "stun", "electrocute",
            "hit_fire", "hit_cold", "hit_lightning", "hit_chaos", "hit_physical", "hit_unique", "kill_rare", "low_life");

    /** 一種護符。 */
    record CharmBase(String id, String name, String trigger, boolean cure, int level, double duration, int charges, int perUse,
                     List<Modifier> effects, Material material, String description) {
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
        baseSlots = Math.max(0, node.integer("base_slots", 2));
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
                    Math.max(0.5, b.decimal("duration", 4)), charges, perUse, List.copyOf(effects), material, b.string("description", "")));
        }
    }

    int size() {
        return bases.size();
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

    ItemStack create(final CharmBase base, final int charges) {
        final ItemStack stack = ItemStack.of(base.material());
        render(stack, base, charges);
        return stack;
    }

    private void render(final ItemStack stack, final CharmBase base, final int charges) {
        final int clamped = Math.max(0, Math.min(base.charges(), charges));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#c9a0ff>" + ItemRenderer.escape(base.name())));
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(base, clamped)) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(lore);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + clamped);
        });
        stack.setData(DataComponentTypes.MAX_DAMAGE, base.charges());
        stack.setData(DataComponentTypes.DAMAGE, base.charges() - clamped);
        // 原版的耐久度、屬性那幾行（英文）不要顯示
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.DAMAGE, DataComponentTypes.MAX_DAMAGE, DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
    }

    private List<String> lore(final CharmBase base, final int charges) {
        final List<String> lines = new ArrayList<>();
        lines.add("<gray>" + triggerText(base.trigger()) + "：自動觸發" + (base.cure() ? "，解除那個異常狀態" : ""));
        if (!base.effects().isEmpty()) {
            lines.add(String.format(Locale.ROOT, "<gray>之後 <white>%s</white> 秒內：", trim(base.duration())));
            for (final String effect : tw.exilecore.skill.ModifierText.describeAll(base.effects())) {
                lines.add("<#8fd3ff>　" + effect);
            }
        }
        if (!base.description().isBlank()) {
            lines.add("<gray>" + ItemRenderer.escape(base.description()));
        }
        lines.add("<gray>充能 <white>" + charges + "</white>／" + base.charges() + "，每次用 <white>" + base.perUse() + "</white>");
        lines.add("<gray>需求：等級 <white>" + base.level());
        lines.add("");
        lines.add("<gray>裝進角色面板的護符欄才會生效（不佔背包）：基本 " + baseSlots + " 格，");
        lines.add("<gray>裝備的「+1 護符欄位」可以多開，最多 " + PlayerState.CHARM_SLOTS + " 格");
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
            return "沒有開著的護符欄（裝備「+1 護符欄位」的腰帶可以開）";
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
            player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        } else {
            player.sendActionBar(plugin.text().parse("<#c9a0ff>" + ItemRenderer.escape(name) + "<gray> 裝進護符欄（角色面板 /panel）"));
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
        final PlayerState state = plugin.players().get(player);
        final int level = state.level();
        final int open = activeCount(player);
        final Set<String> fired = new HashSet<>();
        for (int index = 0; index < open; index++) {
            final CharmData data = read(charmAt(state, index)).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base == null || !base.trigger().equals(trigger) || data.charges() < base.perUse() || base.level() > level
                    || fired.contains(base.id())) {
                continue;
            }
            if (!base.cure() && !base.effects().isEmpty() && plugin.skills() != null && plugin.skills().buffActive(player, "charm." + base.id())) {
                fired.add(base.id()); // 效果還在：這一種先不觸發（後面同種的也不用）
                continue;
            }
            fired.add(base.id());
            setCharges(state, index, base, data.charges() - base.perUse());
            if (base.cure() && plugin.ailments() != null) {
                final AilmentType type = ailmentOf(trigger);
                if (type != null) {
                    plugin.ailments().cure(player, type);
                }
            }
            if (!base.effects().isEmpty() && plugin.skills() != null) {
                plugin.skills().buff(player, "charm." + base.id(), base.name(), (int) Math.round(base.duration() * 20), base.effects());
            }
            final long now = plugin.getServer().getCurrentTick();
            state.notice("<#c9a0ff>" + ItemRenderer.escape(base.name()) + "觸發", now + 40);
            plugin.players().display(player, state);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.4f);
        }
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

    /** 每幾個 tick 看一次：身上有異常狀態就觸發對應的護符。 */
    void tick() {
        if (bases.isEmpty() || plugin.ailments() == null) {
            return;
        }
        final Set<String> wanted = new HashSet<>();
        for (final CharmBase base : bases.values()) {
            if (ailmentOf(base.trigger()) != null) {
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
            for (final String trigger : wanted) {
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
        for (int index = 0; index < open; index++) {
            final CharmData data = read(charmAt(state, index)).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base != null && data.charges() < base.charges()) {
                setCharges(state, index, base, Math.min(base.charges(), data.charges() + amount));
            }
        }
    }

    /** 護符欄與背包裡的護符全部補滿；回傳補了幾個。 */
    int refill(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        int filled = 0;
        final PlayerState state = plugin.players().get(player);
        for (int index = 0; index < PlayerState.CHARM_SLOTS; index++) {
            final CharmData data = read(charmAt(state, index)).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base != null && data.charges() < base.charges()) {
                setCharges(state, index, base, base.charges());
                filled++;
            }
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final CharmData data = read(inventory.getItem(slot)).orElse(null);
            final CharmBase base = data == null ? null : bases.get(data.base());
            if (base != null && data.charges() < base.charges()) {
                setCharges(inventory, slot, base, base.charges());
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
            if (base.level() <= monsterLevel) {
                list.add(base);
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        final CharmBase base = list.get(random.nextInt(list.size()));
        return create(base, base.charges());
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
