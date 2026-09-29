package tw.exilecore.character;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家狀態的管理：建立、重算屬性表、把我們的生命／魔力／護盾投影到原版的血條／經驗條／吸收血條。
 */
public final class PlayerStateService {

    /** 原版最大血量固定 40（20 顆心），血條只是比例顯示。 */
    public static final double VANILLA_MAX_HEALTH = 40.0;
    /** 護盾受傷後幾 tick 才開始回充。 */
    public static final long ES_RECHARGE_DELAY_TICKS = 40;

    private final ExileCore plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final NamespacedKey levelKey;
    private final NamespacedKey movementKey;

    public PlayerStateService(final ExileCore plugin) {
        this.plugin = plugin;
        this.levelKey = new NamespacedKey(plugin, "level");
        this.movementKey = new NamespacedKey(plugin, "movement_speed");
    }

    public PlayerState get(final Player player) {
        return states.computeIfAbsent(player.getUniqueId(), uuid -> {
            final Integer stored = player.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
            final PlayerState state = new PlayerState(uuid, stored == null ? 1 : stored);
            rebuild(player, state);
            state.restoreAll();
            return state;
        });
    }

    public Optional<PlayerState> find(final UUID uuid) {
        return Optional.ofNullable(states.get(uuid));
    }

    public void remove(final Player player) {
        states.remove(player.getUniqueId());
    }

    public void setLevel(final Player player, final int level) {
        final PlayerState state = get(player);
        state.setLevel(level);
        player.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, state.level());
        rebuild(player, state);
        state.restoreAll();
        display(player, state);
    }

    /** 屬性表有變動（換裝、升級）時呼叫；實際重算延後到下次需要時。 */
    public void markDirty(final Player player) {
        find(player.getUniqueId()).ifPresent(PlayerState::markDirty);
    }

    /** 取得最新的屬性表（髒了就重算）。 */
    public StatSheet sheet(final Player player) {
        final PlayerState state = get(player);
        if (state.isDirty()) {
            rebuild(player, state);
        }
        return state.sheet();
    }

    /**
     * 重算屬性表（設計文件「修飾器來源與快取」）：
     * 等級基礎值 → 裝備修飾器（需求不符的裝備不算）→ 屬性值帶來的加成。
     */
    public void rebuild(final Player player, final PlayerState state) {
        final StatSheet sheet = state.sheet();
        sheet.clearModifiers();
        final int level = state.level();

        // 基礎值
        sheet.setBase(StatKeys.STR, 20);
        sheet.setBase(StatKeys.DEX, 20);
        sheet.setBase(StatKeys.INT, 20);
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        sheet.setBase(StatKeys.CRIT_MULTI, 150);
        sheet.setBase(StatKeys.LIFE_REGEN, 0);

        // 第一輪：所有裝備的修飾器，用來算屬性值
        final List<EquippedItem> equipped = equippedItems(player);
        for (final EquippedItem item : equipped) {
            sheet.addAll(item.modifiers());
        }
        final double str = sheet.get(StatKeys.STR);
        final double dex = sheet.get(StatKeys.DEX);
        final double intelligence = sheet.get(StatKeys.INT);

        // 第二輪：剔除需求不符的裝備
        sheet.clearModifiers();
        boolean hasWeapon = false;
        state.setWeaponTags(Set.of());
        for (final EquippedItem item : equipped) {
            final ItemBase.Requirements req = item.requirements();
            final boolean ok = level >= req.level() && str >= req.str() && dex >= req.dex() && intelligence >= req.intelligence();
            if (!ok) {
                continue;
            }
            sheet.addAll(item.modifiers());
            if (item.base().isWeapon()) {
                hasWeapon = true;
                state.setWeaponTags(item.base().tags());
            }
        }
        if (!hasWeapon) {
            // 徒手：2-6 物理，每秒 1.2 次，暴擊 5%
            sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 2, Set.of(Tags.PHYSICAL), "徒手"));
            sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 6, Set.of(Tags.PHYSICAL), "徒手"));
            sheet.add(Modifier.flat(StatKeys.WEAPON_APS, 1.2, "徒手"));
            sheet.add(Modifier.flat(StatKeys.WEAPON_CRIT, 5, "徒手"));
        }

        // 等級與屬性值的基礎
        final double finalStr = sheet.get(StatKeys.STR);
        final double finalDex = sheet.get(StatKeys.DEX);
        final double finalInt = sheet.get(StatKeys.INT);
        sheet.setBase(StatKeys.LIFE_MAX, Formulas.baseLife(level) + Formulas.lifeFromStrength(finalStr));
        sheet.setBase(StatKeys.MANA_MAX, Formulas.baseMana(level) + Formulas.manaFromIntelligence(finalInt));
        sheet.setBase(StatKeys.EVASION, Formulas.baseEvasion(level));
        sheet.setBase(StatKeys.ACCURACY, Formulas.baseAccuracy(level) + Formulas.accuracyFromDexterity(finalDex));
        sheet.add(Modifier.increased(StatKeys.EVASION, Formulas.evasionIncreaseFromDexterity(finalDex), "敏捷"));
        sheet.add(Modifier.increased(StatKeys.ES_MAX, Formulas.esIncreaseFromIntelligence(finalInt), "智慧"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, Formulas.meleePhysicalIncreaseFromStrength(finalStr),
                Set.of(Tags.MELEE, Tags.PHYSICAL), "力量"));
        // 魔力回復：每秒回 1.75% 最大魔力
        sheet.setBase(StatKeys.MANA_REGEN, sheet.get(StatKeys.MANA_MAX) * 0.0175);

        state.clampToMax();
        state.markClean();
        syncVanillaAttributes(player, sheet);
    }

    /** 我們有算的裝備欄位：主手、副手、四件防具。戒指等虛擬欄位在階段 2。 */
    private List<EquippedItem> equippedItems(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final List<EquippedItem> list = new ArrayList<>();
        collect(list, inventory.getItemInMainHand(), ItemSlot.MAIN_HAND);
        collect(list, inventory.getItemInOffHand(), ItemSlot.OFF_HAND);
        collect(list, inventory.getItem(EquipmentSlot.HEAD), ItemSlot.HEAD);
        collect(list, inventory.getItem(EquipmentSlot.CHEST), ItemSlot.CHEST);
        collect(list, inventory.getItem(EquipmentSlot.LEGS), ItemSlot.LEGS);
        collect(list, inventory.getItem(EquipmentSlot.FEET), ItemSlot.FEET);
        return list;
    }

    private void collect(final List<EquippedItem> list, final ItemStack stack, final ItemSlot slot) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        if (base == null || base.slot() != slot) {
            // 放錯欄位（例如把頭盔拿在手上）不算數
            return;
        }
        list.add(new EquippedItem(base, data.get(),
                ItemStats.modifiers(plugin.items().repository(), data.get()),
                ItemStats.requirements(plugin.items().repository(), base, data.get().ilvl())));
    }

    private record EquippedItem(ItemBase base, ItemData data, List<Modifier> modifiers, ItemBase.Requirements requirements) {
    }

    /** 把會影響原版行為的數值同步過去：血量上限固定、攻擊冷卻拉到無感、移動速度。 */
    private void syncVanillaAttributes(final Player player, final StatSheet sheet) {
        final AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null && health.getBaseValue() != VANILLA_MAX_HEALTH) {
            health.setBaseValue(VANILLA_MAX_HEALTH);
        }
        final AttributeInstance attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeed != null && attackSpeed.getBaseValue() < 100) {
            attackSpeed.setBaseValue(100);
        }
        final AttributeInstance movement = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            movement.removeModifier(movementKey);
            final double increased = sheet.increased(StatKeys.MOVEMENT_SPEED, Set.of());
            if (increased != 0) {
                movement.addModifier(new AttributeModifier(movementKey, increased,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    /** 每秒回復與護盾回充；由排程每 10 tick 呼叫一次。 */
    public void tick(final Player player, final PlayerState state, final long currentTick, final int ticksSinceLast) {
        if (state.isDirty()) {
            rebuild(player, state);
        }
        if (state.isDead() || player.isDead()) {
            return;
        }
        final double seconds = ticksSinceLast / 20.0;
        final StatSheet sheet = state.sheet();
        state.setLife(state.life() + sheet.get(StatKeys.LIFE_REGEN) * seconds);
        state.setMana(state.mana() + sheet.get(StatKeys.MANA_REGEN) * seconds);
        if (state.maxEnergyShield() > 0 && currentTick - state.lastDamageTick() >= ES_RECHARGE_DELAY_TICKS) {
            // 延遲過後每秒回充三分之一
            state.setEnergyShield(state.energyShield() + state.maxEnergyShield() / 3.0 * seconds);
        }
        display(player, state);
    }

    /** 把生命／魔力／護盾投影到原版顯示。 */
    public void display(final Player player, final PlayerState state) {
        if (player.isDead()) {
            return;
        }
        final double ratio = state.life() / state.maxLife();
        final double vanillaHealth = state.life() <= 0 ? 0 : Math.max(1.0, Math.round(ratio * VANILLA_MAX_HEALTH));
        if (vanillaHealth > 0 && Math.abs(player.getHealth() - vanillaHealth) >= 0.5) {
            player.setHealth(Math.min(VANILLA_MAX_HEALTH, vanillaHealth));
        }
        final double maxEs = state.maxEnergyShield();
        final double absorption = maxEs <= 0 ? 0 : Math.round(20.0 * state.energyShield() / maxEs);
        if (Math.abs(player.getAbsorptionAmount() - absorption) >= 0.5) {
            player.setAbsorptionAmount(absorption);
        }
        final double maxMana = state.maxMana();
        player.setLevel((int) Math.round(state.mana()));
        player.setExp(maxMana <= 0 ? 0f : (float) Math.max(0, Math.min(0.999, state.mana() / maxMana)));
        player.sendActionBar(actionBar(state));
    }

    private Component actionBar(final PlayerState state) {
        final StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "<#ff5555>❤ %,d / %,d", Math.round(state.life()), Math.round(state.maxLife())));
        if (state.maxEnergyShield() > 0) {
            text.append(String.format(Locale.ROOT, "  <#77ccff>⛨ %,d / %,d", Math.round(state.energyShield()), Math.round(state.maxEnergyShield())));
        }
        text.append(String.format(Locale.ROOT, "  <#5599ff>◆ %,d / %,d", Math.round(state.mana()), Math.round(state.maxMana())));
        return plugin.text().parse(text.toString());
    }

    /** 全部在線玩家的狀態。 */
    public Map<UUID, PlayerState> all() {
        return states;
    }
}
