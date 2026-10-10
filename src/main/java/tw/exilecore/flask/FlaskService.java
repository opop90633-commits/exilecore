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
import tw.exilecore.item.ItemRenderer;

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

    /** 進度鍵：新角色的起始藥劑送過了。 */
    public static final String PROGRESS_STARTER = "flask.starter";
    /** 回復多久結算一次（tick）。 */
    static final int TICK_INTERVAL = 2;

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

    /** 做一瓶藥劑（充能 charges，超過上限會被夾住）。 */
    public ItemStack create(final FlaskBase base, final int charges) {
        final ItemStack stack = ItemStack.of(Material.POTION);
        render(stack, base, charges);
        return stack;
    }

    /** 依資料重畫：名稱、說明、充能條（耐久條）、顏色。 */
    private void render(final ItemStack stack, final FlaskBase base, final int charges) {
        final int clamped = Math.max(0, Math.min(base.charges(), charges));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<white>" + ItemRenderer.escape(base.name())));
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(base, clamped)) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(lore);
            meta.setMaxStackSize(1);
            if (resourcePack && base.model() != null) {
                final NamespacedKey model = NamespacedKey.fromString(base.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, base.id() + ";" + clamped);
        });
        // 資料元件放在 editMeta 之後設定，才不會被 ItemMeta 寫回時蓋掉
        stack.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents()
                .customColor(base.kind() == FlaskKind.LIFE ? Color.fromRGB(0xD7263D) : Color.fromRGB(0x2F6BFF)).build());
        stack.unsetData(DataComponentTypes.CONSUMABLE); // 原版的喝藥水不要：我們用數字鍵喝
        // 充能畫成耐久條：滿的時候不顯示
        stack.setData(DataComponentTypes.MAX_DAMAGE, base.charges());
        stack.setData(DataComponentTypes.DAMAGE, base.charges() - clamped);
        // 原版藥水的「無效果」那行不要顯示
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.POTION_CONTENTS, DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
    }

    private List<String> lore(final FlaskBase base, final int charges) {
        final List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "<gray>在 <white>%s</white> 秒內回復 <%s>%d</%s> %s",
                trim(base.duration()), base.kind().color(), Math.round(base.recovery()), base.kind().color(), base.kind().displayName()));
        lines.add("<gray>充能 <white>" + charges + "</white>／" + base.charges() + "，每次用 <white>" + base.perUse()
                + "</white>（還能喝 <white>" + charges / base.perUse() + "</white> 次）");
        lines.add("<gray>需求：等級 <white>" + base.level());
        lines.add("");
        final String key = slotKey(base.kind());
        lines.add("<gray>放在快捷欄 <white>" + key + "</white>（" + base.kind().displayName() + "藥劑欄），按 <white>" + key
                + "</white> 喝（手上的武器不會換掉）");
        lines.add("<gray>殺怪充能；回營地、碰到檢查點、重生時補滿");
        lines.add("<dark_gray>" + base.kind().displayName() + "回滿了效果就結束");
        return lines;
    }

    /** 「1（生命）、2（魔力）」。 */
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
        final int clamped = Math.max(0, Math.min(base.charges(), charges));
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
    public boolean drink(final Player player, final int hotbarSlot) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack item = inventory.getItem(hotbarSlot);
        final FlaskKind slotKind = config.kindAt(hotbarSlot);
        final PlayerState state = plugin.players().get(player);
        if (isPlaceholder(item)) {
            if (slotKind != null) {
                // 只提示、不出聲：滾輪滾過去也會到這裡
                state.notice("<gray>" + slotKind.displayName() + "藥劑欄是空的：把" + slotKind.displayName() + "藥劑放進快捷欄 "
                        + slotKey(slotKind) + "（撿到時會自動放進來）", now() + 40);
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
            fail(player, state, base.kind().displayName() + "藥劑要放在快捷欄 " + slotKey(base.kind()) + " 才能喝");
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
        if (data.charges() < base.perUse()) {
            fail(player, state, base.name() + "沒有充能了（殺怪充能，回營地或檢查點補滿）");
            return true;
        }
        final boolean full = base.kind() == FlaskKind.LIFE ? state.life() >= state.maxLife() - 1e-6 : state.mana() >= state.maxMana() - 1e-6;
        if (full) {
            fail(player, state, base.kind().displayName() + "已經是滿的");
            return true;
        }
        uses.put(hotbarSlot, now);
        setCharges(inventory, hotbarSlot, data.charges() - base.perUse());
        final int ticks = Math.max(1, (int) Math.round(base.duration() * 20));
        // 回復量吃「藥劑回復」的加成（任務獎勵）
        final String recoveryStat = base.kind() == FlaskKind.LIFE ? tw.exilecore.stats.StatKeys.FLASK_LIFE_RECOVERY
                : tw.exilecore.stats.StatKeys.FLASK_MANA_RECOVERY;
        final double recovery = base.recovery() * Math.max(0, 1 + plugin.players().sheet(player).increased(recoveryStat, java.util.Set.of()));
        active.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>())
                .add(new Recovery(base.kind(), recovery / ticks, ticks));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 0.7f, 1.2f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.2, 0), 10, 0.3, 0.4, 0.3, 0,
                new Particle.DustOptions(base.kind() == FlaskKind.LIFE ? Color.fromRGB(0xFF4D5E) : Color.fromRGB(0x5C8DFF), 1.1f));
        return true;
    }

    private void fail(final Player player, final PlayerState state, final String message) {
        state.notice("<red>" + ItemRenderer.escape(message), now() + 30);
        plugin.players().display(player, state);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    /** 每 TICK_INTERVAL tick：正在喝的藥劑回復一點；回滿了那一種的效果就結束。每兩秒把空瓶提示補好。 */
    void tick() {
        sweepTicks += TICK_INTERVAL;
        if (sweepTicks >= 40) {
            sweepTicks = 0;
            sweep();
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
            }
            if (mana > 0) {
                state.setMana(state.mana() + mana);
            }
            final boolean lifeFull = state.life() >= state.maxLife() - 1e-6;
            final boolean manaFull = state.mana() >= state.maxMana() - 1e-6;
            entry.getValue().removeIf(recovery -> recovery.ticksLeft <= 0
                    || (recovery.kind == FlaskKind.LIFE && lifeFull) || (recovery.kind == FlaskKind.MANA && manaFull));
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
        final PlayerInventory inventory = player.getInventory();
        final int slot = config.slotFor(kind);
        final FlaskData data = read(inventory.getItem(slot)).orElse(null);
        if (data != null) {
            setCharges(inventory, slot, data.charges() + amount);
        }
    }

    public void addCharges(final Player player, final int amount) {
        if (amount <= 0) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        for (final int slot : config.slots()) {
            final FlaskData data = read(inventory.getItem(slot)).orElse(null);
            if (data != null) {
                setCharges(inventory, slot, data.charges() + amount);
            }
        }
    }

    /** 背包裡的藥劑全部補滿。回傳補了幾瓶。 */
    public int refill(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        int filled = charms == null ? 0 : charms.refill(player);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final FlaskData data = read(inventory.getItem(slot)).orElse(null);
            final FlaskBase base = data == null ? null : config.base(data.base());
            if (base != null && data.charges() < base.charges()) {
                setCharges(inventory, slot, base.charges());
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
            final int slot = config.slotFor(base.kind());
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
        final String where = dropped ? "。<yellow>背包滿了，藥劑掉在腳邊，記得撿起來放到快捷欄 " + slotText()
                : bagged ? "。<yellow>背包滿了，藥劑放進隨身背包（Shift＋F），記得搬到快捷欄 " + slotText()
                : moved ? "。<yellow>快捷欄滿了，藥劑放在背包裡，記得搬到快捷欄 " + slotText() : "";
        player.sendMessage(plugin.text().prefixed("<gray>拿到藥劑：放在快捷欄 <white>" + slotText()
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
            if (target == slot || config.isFlaskSlot(target)) {
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
        if (!config.isFlaskSlot(inventory.getHeldItemSlot())) {
            return false;
        }
        int fallback = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (config.isFlaskSlot(slot)) {
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
    ItemStack placeholder(final FlaskKind kind) {
        final ItemStack stack = ItemStack.of(Material.PAPER); // 紙沒有右鍵的用途（空玻璃瓶右鍵水會裝水）
        final String key = slotKey(kind);
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
    public ItemStack placeholderFor(final int hotbarIndex) {
        final FlaskKind kind = hotbarIndex >= 0 && hotbarIndex < 9 ? config.kindAt(hotbarIndex) : null;
        return kind == null ? null : placeholder(kind);
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
    private int sweepTicks;

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
            final FlaskKind kind = slot < 9 ? config.kindAt(slot) : null;
            if (kind == null) {
                if (isPlaceholder(item)) {
                    inventory.setItem(slot, null);
                    changed = true;
                }
            } else if (item == null || item.isEmpty() || isPlaceholder(item) && !kind.key().equals(placeholderKind(item))) {
                inventory.setItem(slot, placeholder(kind));
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
            final int slot = config.slotFor(kind);
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
            player.sendMessage(plugin.text().prefixed("<gray>快捷欄 <white>" + slotText() + "</white> 是藥劑欄，只能放對應的藥劑："
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
        player.getInventory().setItem(config.slotFor(kind), stack.asOne());
        announcePlaced(player, kind);
        return true;
    }

    /** 這瓶藥劑可以直接放進空著的藥劑欄嗎（等級喝得了的才放）。 */
    public boolean canEquip(final Player player, final ItemStack stack) {
        final FlaskData data = read(stack).orElse(null);
        final FlaskBase base = data == null ? null : config.base(data.base());
        return base != null && stack.getAmount() == 1 && eligible(player) && usable(player, base)
                && vacant(player.getInventory().getItem(config.slotFor(base.kind())));
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
            final int slot = config.slotFor(kind);
            if (!vacant(inventory.getItem(slot))) {
                continue;
            }
            int best = -1;
            int bestLevel = -1;
            int bestCharges = -1;
            for (int source = 0; source < 36; source++) {
                if (config.isFlaskSlot(source)) {
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
        state.notice("<" + kind.color() + ">" + kind.displayName() + "藥劑放進快捷欄 " + slotKey(kind) + "<gray>（按 " + slotKey(kind) + " 喝）",
                now() + 60);
        plugin.players().display(player, state);
    }

    /** 每兩秒：線上玩家的空瓶提示補好（萬一有哪裡漏掉，例如交易、上架把藥劑欄的藥劑拿走了）。 */
    void sweep() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            ensureSlots(player);
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
        return base == null ? null : create(base, base.charges());
    }
}
