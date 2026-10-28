package tw.exilecore.character;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 角色面板的裝備欄（2026-10）：武器、副手、頭盔、胸甲、褲子、鞋子跟飾品一樣放在角色面板的虛擬欄位
 * （{@link PlayerState#jewellery()} 的第 4–9 格，跟戒指、項鍊、腰帶一起存），手上不用拿東西也生效。
 * <p>
 * 看得到：快捷列第 9 格、副手、四個防具欄放的是「顯示用的複本」（沒有物品資料，拿不走、丟不掉，點了打開角色面板），
 * 所以手上拿著武器、身上穿著防具的樣子照舊。顯示用的格子被放進真的物品（右鍵穿防具、Shift＋點防具、拖到第 9 格）：
 * 對的欄位空著就直接裝上，不然放回背包。
 */
public final class Gear {

    /** 裝備欄在 {@link PlayerState#jewellery()} 裡的位置。 */
    public static final int MAIN = 4;
    public static final int OFF = 5;
    public static final int HEAD = 6;
    public static final int CHEST = 7;
    public static final int LEGS = 8;
    public static final int FEET = 9;
    /** 快捷列顯示武器的那一格（0 起算的第 8 格＝鍵盤上的 9）。 */
    public static final int HAND_SLOT = 8;
    /** 這個角色的舊裝備（拿在手上、穿在身上的）搬進裝備欄了沒。 */
    public static final String PROGRESS_MIGRATED = "gear.panel";

    private static final NamespacedKey DISPLAY_KEY = Objects.requireNonNull(NamespacedKey.fromString("exilecore:gear_display"));
    /** 2026-10 效能：顯示用複本的編號（格子裡的還是不是上次放的那個，看這個就好，不用整個比對說明文字）。 */
    private static final NamespacedKey STAMP_KEY = Objects.requireNonNull(NamespacedKey.fromString("exilecore:gear_stamp"));
    /** 同一件裝備、同一個技能，顯示用的複本最多多久重做一次（技能圖示的數值照這個頻率更新）。 */
    private static final long REBUILD_TICKS = 100;
    /**
     * 裝備的顯示用複本多久重做一次（2026-10 效能：spark 裡 Gear.place 的說明轉換佔 1.5%）。裝備本身換了（來源不一樣）馬上重做，
     * 這個只是讓幻化、箭袋顯示開關這種不換裝備的改變跟上（最多慢 30 秒）。
     */
    private static final long GEAR_REBUILD_TICKS = 600;

    private final ExileCore plugin;
    /** 每個玩家每一格上次放的顯示用複本（2026-10 效能：以前每秒每格都重做說明、再整個比對，人多時很重）。 */
    private final Map<java.util.UUID, Shown[]> shown = new java.util.HashMap<>();
    private int nextStamp = 1;

    /** 一格上次放的東西：來源（裝備本身或技能的簽名）、編號、複本、什麼時候做的。 */
    private record Shown(Object source, int stamp, ItemStack want, long built) {
    }

    Gear(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 這個欄位放在 jewellery() 的第幾格（戒指有兩格（展露之指三格），回傳第一格；空的戒指欄見 {@link #ringTarget}）。 */
    public static int index(final ItemSlot slot) {
        return switch (slot) {
            case RING -> 0;
            case AMULET -> 2;
            case BELT -> 3;
            case MAIN_HAND -> MAIN;
            case OFF_HAND -> OFF;
            case HEAD -> HEAD;
            case CHEST -> CHEST;
            case LEGS -> LEGS;
            case FEET -> FEET;
            case MAP, JEWEL, FLASK, CHARM -> -1;
        };
    }

    /** 有沒有開第三個戒指欄（2026-10-26 照 PoE2 昇華・展露之指「+1 戒指欄位」）。 */
    public static boolean ringSlotOpen(final tw.exilecore.stats.StatSheet sheet) {
        return sheet != null && sheet.get(PlayerState.RING_EXTRA_STAT) > 0;
    }

    /** 這個玩家現在有沒有開第三個戒指欄。 */
    public boolean ringSlotOpen(final Player player) {
        return ringSlotOpen(plugin.players().sheet(player));
    }

    /**
     * 戒指要裝進哪一格（2026-10-26）：第一個空的戒指欄（第一、第二，開了的話第三）；都滿了換掉第一格。
     */
    public static int ringTarget(final PlayerState state, final boolean thirdOpen) {
        if (state.jewellery(0) == null) {
            return 0;
        }
        if (state.jewellery(1) == null) {
            return 1;
        }
        if (thirdOpen && state.jewellery(PlayerState.RING3_SLOT) == null) {
            return PlayerState.RING3_SLOT;
        }
        return 0;
    }

    /** 是不是顯示用的複本。 */
    public static boolean isDisplay(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(DISPLAY_KEY, PersistentDataType.BYTE); // 2026-10：唯讀的檢視，不複製整個 meta
    }

    private static int stampOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final Integer value = stack.getPersistentDataContainer().get(STAMP_KEY, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    private static ItemStack withStamp(final ItemStack stack, final int stamp) {
        final ItemStack copy = stack.clone();
        copy.editPersistentDataContainer(data -> data.set(STAMP_KEY, PersistentDataType.INTEGER, stamp));
        return copy;
    }

    /** 外觀換了（幻化、試穿、箭袋顯示開關）：顯示用複本馬上重做（不等 {@link #GEAR_REBUILD_TICKS}）。 */
    public boolean resync(final Player player) {
        shown.remove(player.getUniqueId());
        return sync(player);
    }

    /** 下線、換角色：記的顯示用複本清掉。 */
    public void forget(final java.util.UUID player) {
        shown.remove(player);
    }

    /**
     * 放顯示用的複本（2026-10 效能）：來源沒變、做好還不到 {@link #REBUILD_TICKS}：格子裡還是那個（編號一樣）就什麼都不做，
     * 被換掉了就放回記著的那個；要重做的時候，做出來跟格子裡的一樣就沿用舊的編號（不重放，手上的東西不會閃一下）。
     *
     * @param build 做新的複本（null = 這格應該是空的）
     */
    private void place(final Player player, final int slot, final Object source, final java.util.function.Supplier<ItemStack> build) {
        place(player, slot, source, build, REBUILD_TICKS);
    }

    private void place(final Player player, final int slot, final Object source, final java.util.function.Supplier<ItemStack> build,
                       final long rebuildTicks) {
        final PlayerInventory inventory = player.getInventory();
        final Shown[] mine = shown.computeIfAbsent(player.getUniqueId(), key -> new Shown[41]);
        final Shown last = mine[slot];
        final long now = plugin.getServer().getCurrentTick();
        final ItemStack current = inventory.getItem(slot);
        if (last != null && last.source() == source && now - last.built() < rebuildTicks) {
            if (last.want() == null) {
                if (current != null && !current.isEmpty()) {
                    inventory.setItem(slot, null);
                }
            } else if (stampOf(current) != last.stamp()) {
                inventory.setItem(slot, last.want());
            }
            return;
        }
        final ItemStack want = build.get();
        if (want == null) {
            mine[slot] = new Shown(source, 0, null, now);
            if (current != null && !current.isEmpty()) {
                inventory.setItem(slot, null);
            }
            return;
        }
        final int oldStamp = stampOf(current);
        if (oldStamp != 0 && current.isSimilar(withStamp(want, oldStamp))) {
            mine[slot] = new Shown(source, oldStamp, current.clone(), now); // 一樣：不重放
            return;
        }
        final int stamp = nextStamp++;
        final ItemStack stamped = withStamp(want, stamp);
        ItemRenderer.nativeText(stamped); // 原版每 tick 比對這一格：存成原版的文字元件比對才便宜
        mine[slot] = new Shown(source, stamp, stamped, now);
        inventory.setItem(slot, stamped);
    }

    /** 主手的武器（裝備欄）；沒有是 null。 */
    public static ItemStack mainHand(final PlayerState state) {
        return state.jewellery(MAIN);
    }

    /** 背包某一格（顯示用的格子）對應的裝備欄；不是顯示用的格子回傳 -1。 */
    public static int indexAt(final int inventorySlot) {
        return switch (inventorySlot) {
            case HAND_SLOT -> MAIN;
            case 40 -> OFF;
            case 39 -> HEAD;
            case 38 -> CHEST;
            case 37 -> LEGS;
            case 36 -> FEET;
            default -> -1;
        };
    }

    /** 背包某一格顯示的那件真的裝備（不是顯示用的格子、沒裝東西回傳 null）。 */
    public static ItemStack realAt(final PlayerState state, final int inventorySlot) {
        final int index = indexAt(inventorySlot);
        return index < 0 ? null : state.jewellery(index);
    }

    /** 四件防具（頭、身、腿、腳；沒裝的是 null）。 */
    public static ItemStack[] armour(final PlayerState state) {
        return new ItemStack[] {state.jewellery(HEAD), state.jewellery(CHEST), state.jewellery(LEGS), state.jewellery(FEET)};
    }

    /** 顯示用的複本：外觀、名稱、說明照原本的，物品資料（詞綴、代號）拿掉，盾不能舉。 */
    static ItemStack display(final ItemStack real) {
        final ItemStack copy = real.clone();
        copy.setAmount(1);
        copy.editMeta(meta -> {
            final PersistentDataContainer data = meta.getPersistentDataContainer();
            for (final NamespacedKey key : new ArrayList<>(data.getKeys())) {
                data.remove(key);
            }
            data.set(DISPLAY_KEY, PersistentDataType.BYTE, (byte) 1);
            final List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(ItemRenderer.line(""));
            lore.add(ItemRenderer.line("<dark_gray>裝在角色面板上（這裡只是顯示）"));
            lore.add(ItemRenderer.line("<yellow>點一下：打開角色面板換裝備"));
            meta.lore(lore);
        });
        if (copy.hasData(DataComponentTypes.BLOCKS_ATTACKS)) {
            copy.unsetData(DataComponentTypes.BLOCKS_ATTACKS); // 副手的盾不能舉（PoE 的格擋是被動機率）
        }
        return copy;
    }

    /** 沒拿武器時第 9 格的提示（空手也要有東西：丟東西鍵、右鍵的副手判斷都看主手）。 */
    static ItemStack unarmed() {
        final ItemStack stack = ItemStack.of(Material.LIGHT_GRAY_DYE);
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<gray>空手"));
            meta.lore(List.of(ItemRenderer.line("<dark_gray>武器裝在角色面板上（Shift＋F → 角色）"),
                    ItemRenderer.line("<yellow>點一下：打開角色面板")));
            meta.getPersistentDataContainer().set(DISPLAY_KEY, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    // ---- 同步顯示 ----

    /**
     * 讓背包裡的顯示用格子跟裝備欄一致：第 9 格、副手、四個防具欄。
     * 那幾格放進了真的物品（右鍵穿防具、拖進去）：對的欄位空著就裝上，不然搬回背包；顯示用的複本跑到別的格子就清掉。
     *
     * @return 裝備欄有沒有變（要重算屬性）
     */
    public boolean sync(final Player player) {
        if (shown.size() > plugin.getServer().getOnlinePlayers().size() + 16) {
            shown.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null); // 下線的人記的複本清掉
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return false;
        }
        final PlayerInventory inventory = player.getInventory();
        boolean changed = false;
        // 顯示用的格子裡被放進的真的物品
        changed |= absorb(player, state, HAND_SLOT, ItemSlot.MAIN_HAND);
        changed |= absorb(player, state, 40, ItemSlot.OFF_HAND);
        changed |= absorb(player, state, 39, ItemSlot.HEAD);
        changed |= absorb(player, state, 38, ItemSlot.CHEST);
        changed |= absorb(player, state, 37, ItemSlot.LEGS);
        changed |= absorb(player, state, 36, ItemSlot.FEET);
        changed |= checkPair(player, state);
        changed |= checkThirdRing(player, state);
        final boolean combat = combat(player);
        if (!combat) {
            restoreHotbar(player, state); // 離開戰鬥區域：技能圖示換回原本的東西
        }
        // 別的格子裡的顯示用複本（被拖走、存檔時留下的）
        for (int slot = 0; slot < 36; slot++) {
            if (slot != HAND_SLOT && !(combat && skillSlot(state, slot)) && isDisplay(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
        if (combat) {
            showSkills(player, state); // 戰鬥區域：快捷列的技能鍵（經典 3–8；自訂時是藥劑以外的 6 格）換成技能圖示
        }
        if (isDisplay(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        show(player, HAND_SLOT, state.jewellery(MAIN), true, ItemSlot.MAIN_HAND);
        show(player, 40, hiddenInHand(player, state.jewellery(OFF)) ? null : state.jewellery(OFF), false, ItemSlot.OFF_HAND);
        show(player, 39, state.jewellery(HEAD), false, ItemSlot.HEAD);
        show(player, 38, state.jewellery(CHEST), false, ItemSlot.CHEST);
        show(player, 37, state.jewellery(LEGS), false, ItemSlot.LEGS);
        show(player, 36, state.jewellery(FEET), false, ItemSlot.FEET);
        if (changed) {
            plugin.players().markDirty(player);
        }
        return changed;
    }

    /**
     * 箭袋（2026-10）：裝在副手時不放顯示用的複本——不會拿在左手上，副手也就沒有東西可以右鍵
     * （以前叉鏃箭袋的材質是望遠鏡，按住右鍵施放時鏡頭會一直拉近拉遠）。裝備本身還是在角色面板的副手欄，效果照算。
     * 例外：副手套了幻化、而且玩家選了要顯示（贊助 → 裝備幻化的開關，預設顯示）——那就照幻化的樣子拿在手上。
     */
    private boolean hiddenInHand(final Player player, final ItemStack real) {
        if (!quiver(real)) {
            return false;
        }
        return plugin.sponsors() == null || !plugin.sponsors().showsQuiverSkin(player);
    }

    private boolean quiver(final ItemStack real) {
        if (real == null || real.isEmpty()) {
            return false;
        }
        final ItemBase base = baseOf(real);
        return base != null && base.isQuiver();
    }

    private void show(final Player player, final int slot, final ItemStack real, final boolean hand, final ItemSlot itemSlot) {
        place(player, slot, real, () -> buildShown(player, real, hand, itemSlot), GEAR_REBUILD_TICKS);
    }

    /** 這一格該放的顯示用複本（null = 空的）。 */
    private ItemStack buildShown(final Player player, final ItemStack real, final boolean hand, final ItemSlot itemSlot) {
        ItemStack want = real == null ? (hand ? unarmed() : null) : display(real);
        if (real == null && itemSlot == ItemSlot.HEAD && plugin.sponsors() != null) {
            // 2026-10：頭飾（3D 幻化）沒戴頭盔也顯示
            final ItemStack headgear = plugin.sponsors().headgearOnly(player);
            if (headgear != null) {
                want = display(headgear);
            }
        }
        if (real != null && plugin.sponsors() != null) {
            plugin.sponsors().applyTransmog(player, itemSlot, want); // 2026-10：裝備幻化（只改顯示用的複本）
            if (itemSlot == ItemSlot.OFF_HAND && want.getType() != Material.LEATHER && quiver(real)) {
                // 顯示幻化的箭袋：顯示用的複本一律換成皮革（樣子照幻化的物品模型，不看材質）——
                // 有的箭袋材質右鍵有原版的動作（望遠鏡會拉近鏡頭），副手的東西不要有任何右鍵動作
                want = want.withType(Material.LEATHER);
            }
        }
        return want;
    }

    /**
     * 主手與副手搭不搭（2026-10）：雙手武器不能配盾、法器；箭袋只能配弓（2026-10：弩不行）。
     *
     * @return 不搭的原因；搭（或其中一邊沒裝）回傳 null
     */
    /** 2026-10-26（昇華・力量之器）：沙靈持用長杖時可以裝備法器。 */
    public static String pairProblem(final ItemBase main, final ItemBase off, final tw.exilecore.ExileCore plugin, final PlayerState state) {
        if (tw.exilecore.ascendancy.AscBuild.staffFocus(plugin, state, main, off)) {
            return null;
        }
        final tw.exilecore.stats.StatSheet sheet = state == null ? null : state.sheet();
        // 2026-10-27 照 PoE2 基石・巨人之血：雙手斧、錘、劍可以單手持用，所以副手照樣能裝盾或法器；這次之前雙手武器一律不能配
        if (off != null && off.isShield() && main != null && sheet != null
                && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_GIANTS_BLOOD)
                && tw.exilecore.stats.KeystoneKeys.giantWeapon(main.tags())) {
            return null;
        }
        if (off != null && off.slot() == tw.exilecore.item.ItemSlot.MAIN_HAND && giantOffHand(main, off, sheet)) {
            return null; // 2026-10-27 照 PoE2 基石・巨人之血：兩手各拿一把雙手斧、錘、劍（雙持，DualWield）
        }
        if (off != null && off.slot() == tw.exilecore.item.ItemSlot.MAIN_HAND) {
            // 2026-10-28 照 PoE2 0.5（poe2db Sceptres：「Sceptres can be equipped in your main hand or off hand」「you cannot Dual Wield two Sceptres」）：
            // 權杖可以放副手（主手是單手武器或空著）；不能兩把權杖；雙手武器配權杖要荒野領主（獸魂符）。
            // 這次之前副手的權杖只有荒野領主才行
            if (off.tags().contains("sceptre")) {
                return sceptreOffHand(main, off, sheet) ? null : sceptreOffHandProblem(main);
            }
            return "副手不能放武器（權杖除外；巨人之血：兩手各拿一把雙手斧、錘、劍）";
        }
        return pairProblem(main, off);
    }

    /**
     * 副手能不能放這把權杖（2026-10-28 照 PoE2 0.5）：權杖是單手武器，主手、副手都能放——主手空著、或拿單手武器（不是權杖：不能雙持兩把權杖）；
     * 主手是雙手武器時只有荒野領主（點了、主手是獸魂符）可以（傳奇另外擋：{@link #lordOfWildsSceptre}、{@link #uniqueSceptre}）。
     * 這次之前只有荒野領主那一種。
     */
    public static boolean sceptreOffHand(final ItemBase main, final ItemBase off, final tw.exilecore.stats.StatSheet sheet) {
        if (off == null || !off.tags().contains("sceptre")) {
            return false;
        }
        if (main == null) {
            return true;
        }
        if (main.tags().contains("sceptre")) {
            return false;
        }
        if (main.hasTag(tw.exilecore.stats.Tags.TWO_HAND)) {
            return lordOfWildsSceptre(main, sheet);
        }
        return main.isWeapon();
    }

    /** 荒野領主（PoE2）：主手是獸魂符、點了荒野領主，副手可以再裝一把非傳奇權杖。 */
    public static boolean lordOfWildsSceptre(final ItemBase main, final tw.exilecore.stats.StatSheet sheet) {
        return main != null && sheet != null && main.tags().contains(tw.exilecore.stats.Tags.TALISMAN)
                && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_LORD_OF_WILDS);
    }

    /** 副手的權杖不能放的原因（{@link #sceptreOffHand} 不成立時）。 */
    static String sceptreOffHandProblem(final ItemBase main) {
        if (main != null && main.tags().contains("sceptre")) {
            return "不能同時拿兩把權杖";
        }
        if (main != null && main.hasTag(tw.exilecore.stats.Tags.TWO_HAND)) {
            return "雙手武器不能配權杖（荒野領主：持用獸魂符時可以再裝一把非傳奇權杖）";
        }
        return "副手的權杖要配單手武器";
    }

    /** 2026-10-28：副手的權杖有沒有問題（含荒野領主只收非傳奇）；沒問題回傳 null。 */
    public static String offHandSceptreProblem(final ItemBase main, final ItemBase off, final ItemData data,
                                               final tw.exilecore.stats.StatSheet sheet) {
        if (!sceptreOffHand(main, off, sheet)) {
            return sceptreOffHandProblem(main);
        }
        if (main != null && main.hasTag(tw.exilecore.stats.Tags.TWO_HAND) && uniqueSceptre(data)) {
            return "荒野領主只能在副手裝非傳奇的權杖";
        }
        return null;
    }

    /**
     * 巨人之血（PoE2「可以單手持用雙手斧、錘、劍」）：主手、副手都是雙手斧、錘、劍時可以雙持（2026-10-27；這次之前副手不收武器，
     * 平衡等效是副手空著時攻擊傷害更多 25%）。
     */
    public static boolean giantOffHand(final ItemBase main, final ItemBase off, final tw.exilecore.stats.StatSheet sheet) {
        return main != null && off != null && sheet != null && off.isWeapon()
                && tw.exilecore.stats.KeystoneKeys.giantWeapon(main.tags()) && tw.exilecore.stats.KeystoneKeys.giantWeapon(off.tags())
                && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_GIANTS_BLOOD);
    }

    /** 這把權杖是傳奇（荒野領主只收非傳奇的）。 */
    static boolean uniqueSceptre(final ItemData data) {
        return data != null && data.rarity() == tw.exilecore.item.Rarity.UNIQUE;
    }

    /**
     * 裝上的時候放哪一格（2026-10-27）：權杖本來是主手；荒野領主拿著獸魂符時放副手（換掉獸魂符不合理）。
     * 2026-10-28 照 PoE2：主手拿著單手武器（不是權杖）時，權杖放副手（換下副手的盾、法器或舊權杖）；這次之前一律換掉主手。
     *
     * 傳奇權杖配獸魂符照常放主手（原作荒野領主只收非傳奇）。
     *
     * @return 欄位（{@link #index}）
     */
    int target(final PlayerState state, final ItemBase base, final ItemStack stack) {
        return target(state, base, stack, -1);
    }

    /** preferred：拿著裝備點了哪一格（{@link #index}；-1 = 沒指定）——點副手那格的權杖放副手（能放的話）。 */
    int target(final PlayerState state, final ItemBase base, final ItemStack stack, final int preferred) {
        final int target = index(base.slot());
        if (target == MAIN && base.tags().contains("sceptre")) {
            final ItemBase main = baseOf(state.jewellery(MAIN));
            final boolean offOk = offHandSceptreProblem(main, base, plugin.items().read(stack).orElse(null), state.sheet()) == null;
            if (offOk && (main != null || preferred == OFF)) {
                return OFF;
            }
        }
        // 2026-10-27 巨人之血：主手已經拿著雙手斧、錘、劍、副手空著，再裝一把就放副手（雙持）；副手有東西的話照常換主手
        if (target == MAIN && state.jewellery(OFF) == null && giantOffHand(baseOf(state.jewellery(MAIN)), base, state.sheet())) {
            return OFF;
        }
        return target;
    }

    /**
     * 2026-10-28 照 PoE2（權杖主手、副手都能放）：主手拿著權杖、副手空著，再裝一把單手武器（不是權杖）時，權杖移到副手、
     * 新的武器放主手（攻擊用主手；權杖不能攻擊）。有移回傳 true（之後主手是空的）。這次之前直接換掉主手的權杖。
     */
    boolean moveSceptreToOff(final PlayerState state, final ItemBase incoming, final int target) {
        if (target != MAIN || incoming == null || !incoming.isWeapon() || incoming.tags().contains("sceptre")
                || incoming.hasTag(tw.exilecore.stats.Tags.TWO_HAND) || state.jewellery(OFF) != null) {
            return false;
        }
        final ItemStack main = state.jewellery(MAIN);
        final ItemBase mainBase = baseOf(main);
        if (mainBase == null || !mainBase.tags().contains("sceptre")) {
            return false;
        }
        state.setJewellery(OFF, main);
        state.setJewellery(MAIN, null);
        return true;
    }

    public static String pairProblem(final ItemBase main, final ItemBase off) {
        if (off == null) {
            return null;
        }
        // 法器（focus）的基底也帶 shield 標籤，所以這一條同時擋盾牌和法器
        if (off.isShield() && main != null && main.hasTag(tw.exilecore.stats.Tags.TWO_HAND)) {
            return off.hasTag("focus") ? "雙手武器不能配法器" : "雙手武器不能配盾";
        }
        if (off.isQuiver() && (main == null || !main.hasTag(tw.exilecore.stats.Tags.BOW))) {
            return "箭袋要配弓（弩不能裝箭袋）";
        }
        return null;
    }

    /** 主手換了之後副手不搭：副手卸下放回背包。回傳裝備欄有沒有變。 */
    private boolean checkPair(final Player player, final PlayerState state) {
        final ItemStack off = state.jewellery(OFF);
        if (off == null) {
            return false;
        }
        final String problem = pairProblem(baseOf(state.jewellery(MAIN)), baseOf(off), plugin, state);
        if (problem == null) {
            return false;
        }
        state.setJewellery(OFF, null);
        stow(player, state, off);
        player.sendMessage(plugin.text().prefixed("<yellow>" + ItemRenderer.escape(name(off)) + " 卸下了，放回背包<gray>（" + problem + "）"));
        return true;
    }

    /**
     * 第三個戒指欄關掉了（2026-10-26：退掉展露之指、換昇華）：那一格的戒指卸下放回背包（滿了放隨身背包，再滿掉在腳邊）。
     * 回傳裝備欄有沒有變。
     */
    private boolean checkThirdRing(final Player player, final PlayerState state) {
        final ItemStack ring = state.jewellery(PlayerState.RING3_SLOT);
        if (ring == null || ringSlotOpen(player)) {
            return false;
        }
        state.setJewellery(PlayerState.RING3_SLOT, null);
        stow(player, state, ring);
        player.sendMessage(plugin.text().prefixed("<yellow>" + ItemRenderer.escape(name(ring))
                + " 卸下了，放回背包<gray>（沒有「+1 戒指欄位」，第三個戒指欄關閉了）"));
        return true;
    }

    /** 顯示用的格子裡是真的物品：對的欄位空著就裝上，不然搬到背包別的地方。回傳裝備欄有沒有變。 */
    private boolean absorb(final Player player, final PlayerState state, final int slot, final ItemSlot wanted) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.isEmpty() || isDisplay(stack)) {
            return false;
        }
        inventory.setItem(slot, null);
        final ItemBase base = baseOf(stack);
        final int index = index(wanted);
        if (base != null && base.slot() == wanted && state.jewellery(index) == null) {
            final String blocked = plugin.players().equipProblem(player, stack); // 2026-10-18：直接放進空欄也要檢查未鑑定、需求不夠
            if (blocked != null) {
                stow(player, state, stack);
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(blocked)));
                return false;
            }
            state.setJewellery(index, stack);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<green>裝上 " + ItemRenderer.escape(base.name()) + "<gray>（裝備在角色面板）"));
            return true;
        }
        stow(player, state, stack);
        return false;
    }

    /** 放回背包（不放在顯示用的第 9 格）；滿了放隨身背包，再滿掉在腳邊（只有自己撿得到）。 */
    void stow(final Player player, final PlayerState state, final ItemStack stack) {
        final PlayerInventory inventory = player.getInventory();
        final boolean combat = combat(player);
        for (int slot = 0; slot < 36; slot++) {
            if (slot == HAND_SLOT || combat && skillSlot(state, slot)) {
                continue;
            }
            final ItemStack there = inventory.getItem(slot);
            if (there == null || there.isEmpty()) {
                inventory.setItem(slot, stack);
                return;
            }
        }
        if (state.bag() != null && state.bag().usable() && state.bag().add(stack)) {
            player.sendMessage(plugin.text().prefixed("<gray>背包滿了，" + ItemRenderer.escape(name(stack)) + "放進隨身背包"));
            return;
        }
        plugin.personalDrops().dropForever(player, stack);
        player.sendMessage(plugin.text().prefixed("<yellow>背包和隨身背包都滿了，" + ItemRenderer.escape(name(stack)) + "掉在腳邊"));
    }

    private ItemBase baseOf(final ItemStack stack) {
        final Optional<ItemData> data = plugin.items().read(stack);
        return data.map(value -> plugin.items().repository().base(value.base())).orElse(null);
    }

    private static String name(final ItemStack stack) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stack.effectiveName());
    }

    // ---- 快捷列的技能圖示 ----

    /** 快捷列這一格是不是技能鍵（2026-10：照這個角色選的排法 {@link HotbarLayout}）。 */
    static boolean skillSlot(final PlayerState state, final int slot) {
        return HotbarLayout.of(state).isSkillSlot(slot);
    }

    /** 在可以戰鬥的地方（快捷列 3–8 是技能）。 */
    private boolean combat(final Player player) {
        return plugin.skills() != null && plugin.skills().eligible(player) && !plugin.skills().inTown(player);
    }

    /** 戰鬥區域：技能鍵那幾格原本的東西收起來（收的位置有東西就放回背包別的地方），換成技能圖示。 */
    private void showSkills(final Player player, final PlayerState state) {
        final PlayerInventory inventory = player.getInventory();
        final HotbarLayout layout = HotbarLayout.of(state);
        for (int index = 0; index < layout.skillKeys(); index++) {
            final int slot = layout.skillSlot(index);
            final int at = slot;
            final ItemStack current = inventory.getItem(slot);
            if (current != null && !current.isEmpty() && !isDisplay(current)) {
                inventory.setItem(slot, icon(player, slot)); // 先放圖示，放回背包時才不會又放回這一格
                final int stash = PlayerState.HOTBAR_STASH + index;
                if (state.jewellery(stash) == null) {
                    state.setJewellery(stash, current);
                } else {
                    stow(player, state, current);
                }
                plugin.players().markDirty(player);
                continue;
            }
            place(player, slot, skillSource(player, state, slot), () -> icon(player, at));
        }
    }

    /** 技能圖示的來源簽名：綁的是哪個技能、幾級、掛什麼輔助（變了就馬上重做；沒變的話每 5 秒更新一次數值）。 */
    private static String skillSource(final Player player, final PlayerState state, final int slot) {
        final SkillLoadout loadout = state.skills();
        final int position = loadout.bound(HotbarLayout.of(state).bindForSlot(slot));
        final SkillLoadout.Entry entry = loadout.skill(position);
        return (entry == null ? "-" : entry.skillId() + "@" + entry.level() + ":" + entry.encodeSupports()).intern();
    }

    /**
     * 快捷列技能的冷卻倒數（2026-10，每 4 tick）：圖示的數量 = 還剩幾秒（無條件進位，最多 99；剩不到 1 秒顯示 1 就看不到數字），
     * 冷卻好了變回 1；原版的冷卻遮罩也一起對時（冷卻被重置、縮短，或某些地方沒馬上蓋到時補上）。
     */
    public void cooldowns(final Player player) {
        if (plugin.skills() == null || !combat(player)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final SkillLoadout loadout = state.skills();
        final HotbarLayout layout = HotbarLayout.of(state);
        for (int index = 0; index < layout.skillKeys(); index++) {
            final int slot = layout.skillSlot(index);
            final ItemStack current = inventory.getItem(slot);
            if (!isDisplay(current)) {
                continue;
            }
            final SkillLoadout.Entry entry = loadout.skill(loadout.bound(layout.bindForSlot(slot)));
            long left = 0;
            if (entry != null) {
                left = plugin.skills().cooldownLeft(player, entry.skillId());
                final net.kyori.adventure.key.Key group = tw.exilecore.skill.SkillService.cooldownGroup(entry.skillId());
                final int shown = player.getCooldown(group);
                if (left <= 0 && shown > 0) {
                    player.setCooldown(group, 0);
                } else if (left > 0 && Math.abs(shown - left) > 10) {
                    player.setCooldown(group, (int) left);
                }
            }
            final int amount = left <= 0 ? 1 : (int) Math.max(1, Math.min(current.getMaxStackSize(), (left + 19) / 20));
            if (current.getAmount() != amount) {
                current.setAmount(amount);
                inventory.setItem(slot, current);
            }
        }
    }

    /** 不在戰鬥區域：技能圖示拿掉，收起來的東西放回原本那一格（那一格被別的東西佔了就放回背包別的地方）。 */
    private void restoreHotbar(final Player player, final PlayerState state) {
        final PlayerInventory inventory = player.getInventory();
        final HotbarLayout layout = HotbarLayout.of(state);
        for (int index = 0; index < layout.skillKeys(); index++) {
            final int slot = layout.skillSlot(index);
            final int stash = PlayerState.HOTBAR_STASH + index;
            final ItemStack current = inventory.getItem(slot);
            if (isDisplay(current)) {
                inventory.setItem(slot, null);
            }
            final ItemStack stored = state.jewellery(stash);
            if (stored == null) {
                continue;
            }
            state.setJewellery(stash, null);
            final ItemStack there = inventory.getItem(slot);
            if (there == null || there.isEmpty()) {
                inventory.setItem(slot, stored);
            } else {
                stow(player, state, stored);
            }
            plugin.players().markDirty(player);
        }
    }

    /** 這一格的技能圖示（標成顯示用）。 */
    private ItemStack icon(final Player player, final int slot) {
        final ItemStack stack = plugin.skills().hotbarIcon(player, HotbarLayout.of(plugin.players().get(player)).bindForSlot(slot));
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(DISPLAY_KEY, PersistentDataType.BYTE, (byte) 1));
        return stack;
    }

    // ---- 搬舊裝備 ----

    /**
     * 第一次用新版的角色：手上的武器、身上的防具、副手搬進裝備欄（欄位已經有東西的放回背包），快捷列第 9 格原本的東西搬開。
     * 記在角色進度裡，只做一次。
     */
    public void migrate(final Player player, final PlayerState state) {
        if (!state.isLoaded() || !state.hasCharacter() || "1".equals(state.progress().get(PROGRESS_MIGRATED))) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final List<String> moved = new ArrayList<>();
        // 防具與副手：直接搬（absorb 會檢查欄位）
        final int[][] worn = {{39, HEAD}, {38, CHEST}, {37, LEGS}, {36, FEET}, {40, OFF}};
        final ItemSlot[] wornSlots = {ItemSlot.HEAD, ItemSlot.CHEST, ItemSlot.LEGS, ItemSlot.FEET, ItemSlot.OFF_HAND};
        for (int i = 0; i < worn.length; i++) {
            final ItemStack stack = inventory.getItem(worn[i][0]);
            if (stack == null || stack.isEmpty() || isDisplay(stack)) {
                continue;
            }
            inventory.setItem(worn[i][0], null);
            final ItemBase base = baseOf(stack);
            if (base != null && base.slot() == wornSlots[i] && state.jewellery(worn[i][1]) == null) {
                state.setJewellery(worn[i][1], stack);
                moved.add(base.name());
            } else {
                stow(player, state, stack);
            }
        }
        // 手上拿的武器（拿的不是武器，就找快捷列上的第一把）
        if (state.jewellery(MAIN) == null) {
            int weaponSlot = -1;
            final ItemBase held = baseOf(inventory.getItemInMainHand());
            if (held != null && held.slot() == ItemSlot.MAIN_HAND) {
                weaponSlot = inventory.getHeldItemSlot();
            } else {
                for (int slot = 0; slot < 9; slot++) {
                    final ItemBase base = baseOf(inventory.getItem(slot));
                    if (base != null && base.slot() == ItemSlot.MAIN_HAND) {
                        weaponSlot = slot;
                        break;
                    }
                }
            }
            if (weaponSlot >= 0) {
                final ItemStack weapon = inventory.getItem(weaponSlot);
                inventory.setItem(weaponSlot, null);
                state.setJewellery(MAIN, weapon);
                moved.add(baseOf(weapon).name());
            }
        }
        // 第 9 格原本的東西搬開（之後那一格顯示武器）
        final ItemStack ninth = inventory.getItem(HAND_SLOT);
        if (ninth != null && !ninth.isEmpty() && !isDisplay(ninth)) {
            inventory.setItem(HAND_SLOT, null);
            stow(player, state, ninth);
        }
        plugin.characters().setProgress(player, PROGRESS_MIGRATED, "1");
        sync(player);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        if (moved.isEmpty() && !"1".equals(state.progress().get(CharacterService.PROGRESS_STARTER_KIT))) {
            return; // 新角色：沒有舊裝備要搬，不用說改版
        }
        player.sendMessage(plugin.text().prefixed("<#f5b942>裝備改版：<gray>武器、副手、防具改成裝在角色面板上（Shift＋F → 角色），手上不用拿著也有效。"
                + (moved.isEmpty() ? "" : "<white>" + ItemRenderer.escape(String.join("、", moved)) + "<gray> 已經幫你裝上。")
                + "快捷列第 9 格顯示手上的武器，戰鬥中按 " + HotbarLayout.of(state).skillKeysText() + " 放技能、"
                + (plugin.flasks() == null ? "1、2" : plugin.flasks().slotText(player)) + " 喝藥劑（設定裡可以改位置）。"));
    }

    // ---- 裝上、卸下（角色面板） ----

    /**
     * 裝上一件（從背包的某一格）：放進對的欄位，原本的那件換回那一格。戒指：兩格都滿就換掉第一格。
     *
     * @return 錯誤訊息；成功回傳 null
     */
    public String equip(final Player player, final int inventorySlot) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return "先建立角色（/char）再裝備";
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(inventorySlot);
        if (stack == null || stack.isEmpty() || isDisplay(stack)) {
            return null;
        }
        final ItemBase base = baseOf(stack);
        if (base == null || !base.isWearable()) {
            return "這不是能裝備的東西";
        }
        int target = target(state, base, stack); // 2026-10-27 荒野領主：權杖放副手
        if (target < 0) {
            return "這不是能裝備的東西";
        }
        final String blocked = plugin.players().equipProblem(player, stack); // 2026-10：未鑑定、需求不夠的穿不上
        if (blocked != null) {
            return blocked;
        }
        if (base.slot() == ItemSlot.RING) {
            target = ringTarget(state, ringSlotOpen(player)); // 2026-10-26：展露之指的第三個戒指欄
        }
        if (target == OFF) {
            final String problem = pairProblem(baseOf(state.jewellery(MAIN)), base, plugin, state);
            if (problem != null) {
                return problem;
            }
        }
        moveSceptreToOff(state, base, target); // 2026-10-28 照 PoE2：主手的權杖讓到副手
        final ItemStack previous = state.jewellery(target);
        state.setJewellery(target, stack.clone());
        inventory.setItem(inventorySlot, previous);
        sync(player);
        plugin.players().sheet(player);
        return null;
    }

    /**
     * 裝到指定的欄位（2026-10：月卡的裝備存檔用，戒指要分得出第一、第二格）：背包那一格的東西放進 index，原本的換回那一格。
     *
     * @return 錯誤訊息；成功回傳 null
     */
    public String equipAt(final Player player, final int inventorySlot, final int index) {
        final PlayerState state = plugin.players().get(player);
        final PlayerInventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(inventorySlot);
        if (stack == null || stack.isEmpty() || isDisplay(stack)) {
            return "找不到那件裝備";
        }
        final ItemBase base = baseOf(stack);
        if (base == null || !base.isWearable()) {
            return "這不是能裝備的東西";
        }
        final int natural = index(base.slot());
        if (natural != index && !(base.slot() == ItemSlot.RING && (index == 0 || index == 1 || index == PlayerState.RING3_SLOT))
                && !(index == OFF && (target(state, base, stack, OFF) == OFF // 2026-10-27 荒野領主、2026-10-28 照 PoE2：副手的權杖
                        || giantOffHand(baseOf(state.jewellery(MAIN)), base, state.sheet())))) { // 2026-10-27 巨人之血：副手的雙手武器
            return "放錯欄位";
        }
        if (index == PlayerState.RING3_SLOT && !ringSlotOpen(player)) {
            return "沒有第三個戒指欄（要「+1 戒指欄位」）";
        }
        final String blocked = plugin.players().equipProblem(player, stack); // 2026-10：未鑑定、需求不夠的穿不上
        if (blocked != null) {
            return blocked;
        }
        if (index == OFF) {
            final String problem = pairProblem(baseOf(state.jewellery(MAIN)), base, plugin, state);
            if (problem != null) {
                return problem;
            }
        }
        final ItemStack previous = state.jewellery(index);
        state.setJewellery(index, stack.clone());
        inventory.setItem(inventorySlot, previous);
        sync(player);
        plugin.players().sheet(player);
        return null;
    }

    /** 卸下一格回背包；背包滿了回傳錯誤訊息。 */
    public String unequip(final Player player, final int index) {
        final PlayerState state = plugin.players().get(player);
        final ItemStack equipped = state.jewellery(index);
        if (equipped == null) {
            return null;
        }
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (slot == HAND_SLOT) {
                continue;
            }
            final ItemStack there = inventory.getItem(slot);
            if (there == null || there.isEmpty()) {
                inventory.setItem(slot, equipped);
                state.setJewellery(index, null);
                sync(player);
                plugin.players().sheet(player);
                return null;
            }
        }
        return "背包滿了";
    }

    /** 新手裝備：直接裝上（那一格已經有東西就回傳 false，由呼叫的人放進背包）。 */
    public boolean equipDirect(final Player player, final PlayerState state, final ItemStack stack) {
        final ItemBase base = baseOf(stack);
        final int index = base == null ? -1 : index(base.slot());
        if (index < 0 || state.jewellery(index) != null) {
            return false;
        }
        state.setJewellery(index, stack);
        return true;
    }

    /** 這個欄位的原版裝備格（顯示用；不是防具、副手回傳 null）。 */
    static EquipmentSlot equipmentSlot(final int index) {
        return switch (index) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case OFF -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    /** 給選單：裝備欄的內容（含戒指、項鍊、腰帶；2026-10-26 加上第三個戒指欄）。鍵是 jewellery() 的位置。 */
    public static Map<Integer, ItemStack> equipped(final PlayerState state) {
        final Map<Integer, ItemStack> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < PlayerState.GEAR_SLOTS.length; i++) {
            map.put(PlayerState.gearIndex(i), state.jewellery(PlayerState.gearIndex(i)));
        }
        return map;
    }
}
