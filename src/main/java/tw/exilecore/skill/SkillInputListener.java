package tw.exilecore.skill;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;

/**
 * 施放組合鍵（設計文件「施放方式」）：左鍵、右鍵、潛行＋左鍵、潛行＋右鍵、數字鍵 3–8（2026-10 拿掉 F 鍵切換技能組）。
 * <p>
 * Minecraft 按住右鍵時客戶端每 4 tick 會再送一次「使用」，所以右鍵可以按住連續施放。
 * 按住左鍵原版只會送一次；戰鬥區域裡用 {@link HoldToCast} 讓客戶端把按住左鍵當成一直在挖方塊（每 tick 揮手），
 * 按住的時候每次揮手當成一次左鍵，所以左鍵也可以按住連續施放。原版的攻擊、放方塊、拉弓、舉盾都會被取消
 * （戰鬥中不會挖到地、不會拉弓減速）。創造模式、沒有角色、在營地裡照原版。
 * <p>
 * 揮手不一定是左鍵：右鍵用工具（斧頭削樹皮、鋤頭鋤地、鏟子鏟出小路，手斧、巨斧、爪、權杖、巨錘就是這些原版物品）、
 * 開門、放方塊、丟東西，客戶端也會揮手。所以只有「左鍵點方塊」（客戶端真的開始挖）之後、到放開（停止挖）之間的揮手才算左鍵；
 * 伺服器從揮手推出來的「左鍵點空氣」在右鍵、丟東西的同一個或下一個 tick 不算（2026-10：右鍵有時候會同時放出左鍵的技能）。
 * <p>
 * 拿著武器時客戶端的準星不會停在實體上（武器的攻擊距離元件），右鍵 NPC、蹲下右鍵玩家改由伺服器沿視線找。
 */
final class SkillInputListener implements Listener {

    private final ExileCore plugin;
    private final SkillService service;

    SkillInputListener(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private static int key(final boolean sneaking, final boolean right) {
        if (right) {
            return sneaking ? SkillLoadout.KEY_SNEAK_RIGHT : SkillLoadout.KEY_RIGHT;
        }
        return sneaking ? SkillLoadout.KEY_SNEAK_LEFT : SkillLoadout.KEY_LEFT;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 左鍵或右鍵點空氣、點方塊。原版把點空氣的事件標成「已取消」，所以不能 ignoreCancelled。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }
        final Player player = event.getPlayer();
        final boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (right && event.getItem() != null && plugin.currencies() != null && plugin.currencies().isCurrency(event.getItem())
                && player.getGameMode() != GameMode.CREATIVE) {
            event.setUseItemInHand(Event.Result.DENY); // 通貨（例如終界珍珠做的）不能被原版丟出去用掉
        }
        final long now = now();
        if (right && event.getItem() != null && hasCharacter(player) && (service.gems().use(player, event.getItem(), event.getHand())
                || tw.exilecore.item.UniqueVoucherMenu.tryOpen(plugin, player, event.getItem(), event.getHand())
                || plugin.flasks() != null && plugin.flasks().charms() != null
                        && plugin.flasks().charms().equipFromHand(player, event.getItem(), event.getHand()))) {
            // 寶石：雕琢、放進技能面板（營地裡、創造模式也可以）；傳奇兌換券：選一件傳奇；護符：裝進護符欄。同一個 tick 跟著來的右鍵不施放
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            final SkillRuntime runtime = service.runtime(player);
            runtime.lastRightTick = now;
            runtime.rightTriggerTick = now;
            runtime.suppressRightUntil = Math.max(runtime.suppressRightUntil, now + 1);
            return;
        }
        if (!service.eligible(player)) {
            return;
        }
        final SkillRuntime runtime = service.runtime(player);
        if (right && interactAlongSight(player, event, runtime, now)) {
            return; // 右鍵的是 NPC（或蹲下右鍵玩家）：照點實體處理，不施放
        }
        if (right) {
            // 右鍵可能讓客戶端揮手（工具削樹皮、鋤地、鏟路，開門、放方塊…）：這一下與下一個 tick 的「左鍵點空氣」不算。
            // 按住左鍵（正在挖）時的揮手照算，所以同時按著左右鍵也不會吃掉左鍵。
            runtime.lastRightTick = now;
        }
        if (service.inTown(player)) {
            // 剛走進營地的半秒內方塊互動距離還沒拿掉（HoldToCast 每 10 tick 更新）：太遠的方塊不給原版用
            if (event.getClickedBlock() != null && !HoldToCast.withinVanillaReach(player, event.getClickedBlock())) {
                event.setCancelled(true);
            }
            // 營地裡只有光環能開關（其他技能不能放）
            final int key = key(player.isSneaking(), right);
            if (service.auraBound(player, key)) {
                if (right) {
                    if (now <= runtime.suppressRightUntil || runtime.rightTriggerTick == now
                            || (event.getHand() != EquipmentSlot.HAND && !player.getInventory().getItemInMainHand().isEmpty())) {
                        return;
                    }
                    runtime.rightTriggerTick = now;
                    service.trigger(player, key, null);
                } else if (event.getHand() == EquipmentSlot.HAND && genuineLeft(player, runtime, event.getAction(), now)) {
                    leftPress(player, runtime, null, now);
                }
            }
            return;
        }
        if (right) {
            final Block clicked = event.getClickedBlock();
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null && !player.isSneaking()
                    && vanillaInteraction(clicked) && event.useInteractedBlock() != Event.Result.DENY
                    && HoldToCast.withinVanillaReach(player, clicked)) {
                runtime.lastRightTick = now; // 開門、開箱子客戶端會揮手
                return; // 門、箱子、按鈕照原版（原版的距離內；方塊互動距離拉長後客戶端會送很遠的方塊過來）
            }
            event.setCancelled(true); // 戰鬥中右鍵不做原版的事：放方塊、拉弓、舉盾
            if (now <= runtime.suppressRightUntil || runtime.rightTriggerTick == now) {
                return; // 剛點了實體，或這個 tick 已經放過
            }
            // 主手沒用掉時客戶端會再試副手：主手有東西就只看主手；主手空的（空手點空氣只會送副手）才用副手這一下
            if (event.getHand() != EquipmentSlot.HAND && !player.getInventory().getItemInMainHand().isEmpty()) {
                return;
            }
            runtime.rightTriggerTick = now;
            disableShieldRaise(player);
            service.trigger(player, key(player.isSneaking(), true), null);
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        event.setCancelled(true); // 戰鬥中不會挖到方塊
        if (genuineLeft(player, runtime, event.getAction(), now)) {
            leftPress(player, runtime, null, now);
        }
    }

    /**
     * 這一下「左鍵」是不是真的按了左鍵。生存模式的左鍵點方塊是客戶端真的開始挖（一定是左鍵，之後按住會每 tick 揮手：
     * 記下「按住中」）；左鍵點空氣是伺服器從揮手推出來的（冒險模式的左鍵點方塊也是），右鍵、丟東西帶出來的不算。
     */
    private boolean genuineLeft(final Player player, final SkillRuntime runtime, final Action action, final long now) {
        if (action == Action.LEFT_CLICK_BLOCK && player.getGameMode() == GameMode.SURVIVAL) {
            runtime.leftHeld = true;
            runtime.leftHeldTick = now;
            return true;
        }
        return SkillMath.airClick(runtime.lastRightTick, runtime.lastDropTick, now);
    }

    /**
     * 一次左鍵：同一個 tick 只算一次（點方塊、點空氣、揮手、打到實體會各來一個事件）。
     * 按住左鍵時每 tick 來一次，排隊的施放就一直接下去。
     */
    private void leftPress(final Player player, final SkillRuntime runtime, final LivingEntity target, final long now) {
        if (now == runtime.leftTriggerTick) {
            return;
        }
        runtime.leftTriggerTick = now;
        service.trigger(player, key(player.isSneaking(), false), target);
    }

    /** 左鍵直接點到實體：取消原版攻擊，當成左鍵施放、優先打這個目標。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        final Player player = event.getPlayer();
        if (!service.eligible(player) || service.inTown(player)) {
            return;
        }
        event.setCancelled(true);
        final SkillRuntime runtime = service.runtime(player);
        final long now = now();
        runtime.lastEntityAttackTick = now;
        // 點到的是打不到的東西（自己的圖騰、鐘、別的盔甲座）：照準心的方向放，不要對著它放
        final LivingEntity target = event.getAttacked() instanceof LivingEntity living && service.targets().valid(living, player) ? living : null;
        leftPress(player, runtime, target, now);
    }

    /**
     * 揮手（主手）：按住左鍵時客戶端以為在挖方塊，每 tick 揮一次（{@link HoldToCast}），每一下當成一次左鍵。
     * 點一下的那次揮手跟「左鍵點方塊」在同一個 tick，只算一次。沒有按住左鍵時的揮手（右鍵用工具、開門、丟東西帶出來的）不算。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(final PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        if (!service.hold().active(player)) {
            return;
        }
        final SkillRuntime runtime = service.runtime(player);
        final long now = now();
        if (!SkillMath.heldSwing(runtime.leftHeld, runtime.leftHeldTick, now)) {
            return;
        }
        runtime.leftHeldTick = now;
        leftPress(player, runtime, null, now);
    }

    /** 放開左鍵（或準星換到別的方塊、開了介面）：客戶端送出停止挖，之後的揮手不再當成按住左鍵。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStopDigging(final BlockDamageAbortEvent event) {
        service.runtime(event.getPlayer()).leftHeld = false;
    }

    /**
     * 右鍵時沿視線找 NPC（蹲下時也找玩家：交易邀請）：拿著武器時客戶端的準星不會停在實體上（{@link HoldToCast}），
     * 點不到它們。找到就照「右鍵點了那個實體」處理（觸發原本的事件：NPC 的對話與選單、交易邀請），這一下不施放。
     * 客戶端自己點到實體的那一下（同一個 tick）不再處理一次。
     */
    private boolean interactAlongSight(final Player player, final PlayerInteractEvent event, final SkillRuntime runtime, final long now) {
        if (now <= runtime.suppressRightUntil || runtime.rightTriggerTick == now) {
            return false;
        }
        final Entity target = HoldToCast.lookedAt(player, entity -> interactable(player, entity));
        if (target == null) {
            return false;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        runtime.suppressRightUntil = now + 1;
        runtime.lastRightTick = now;
        plugin.getServer().getPluginManager().callEvent(new PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND));
        return true;
    }

    /**
     * 右鍵沿視線找得到的實體：NPC（含其他插件的假人）；在營地裡蹲下時也找其他玩家（交易邀請）。
     * 戰鬥區域裡蹲下右鍵是技能 4，隊友站在前面也照樣施放，不會一直送出交易邀請。
     */
    private boolean interactable(final Player player, final Entity entity) {
        if (plugin.npcs() != null && plugin.npcs().isNpc(entity) || entity.hasMetadata("NPC")) {
            return true;
        }
        return entity instanceof Player && player.isSneaking() && service.inTown(player);
    }

    /**
     * 右鍵點實體：點到怪物就當成右鍵施放、優先打它；點到 NPC 或其他東西，
     * 同一瞬間跟著來的「右鍵空氣」不要施放（不然跟 NPC 講話會順便放技能）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        if (!service.eligible(player)) {
            return;
        }
        final SkillRuntime runtime = service.runtime(player);
        final long now = now();
        runtime.suppressRightUntil = now + 1; // 跟著來的「右鍵空氣」可能晚一個 tick 才到
        runtime.lastRightTick = now; // 點實體客戶端可能會揮手：不算左鍵
        final Entity clicked = event.getRightClicked();
        if (!event.isCancelled() && clicked instanceof LivingEntity living && service.targets().valid(living, player)
                && !service.inTown(player)) {
            if (runtime.rightTriggerTick != now) {
                runtime.rightTriggerTick = now;
                service.trigger(player, key(player.isSneaking(), true), living);
            }
        }
    }

    /** 丟東西（Q）時客戶端會揮手：同一 tick 的揮手不算左鍵。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(final PlayerDropItemEvent event) {
        if (service.eligible(event.getPlayer())) {
            service.runtime(event.getPlayer()).lastDropTick = now();
        }
    }

    /**
     * 技能鍵（經典排法 3–8；藥劑往後的排法 1–4、7、8，見 HotbarLayout）：在可以戰鬥的地方放綁在那一格的技能，
     * 手上的東西不換（一直拿著第 9 格的武器）。藥劑鍵由藥劑模組先處理；9 是手上的武器。營地裡照原版換格子。
     * 滾輪也會送這個事件：滾輪往上、往下會被當成按了旁邊那一格（所以戰鬥中不要用滾輪）。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHeld(final org.bukkit.event.player.PlayerItemHeldEvent event) {
        final Player player = event.getPlayer();
        if (!service.eligible(player) || service.inTown(player)) {
            return;
        }
        final int newSlot = event.getNewSlot();
        if (newSlot == HoldToCast.COMBAT_HOTBAR_SLOT) {
            return;
        }
        event.setCancelled(true); // 戰鬥中手上一直是第 9 格的武器
        final int bind = tw.exilecore.character.HotbarLayout.of(plugin.players().get(player)).bindForSlot(newSlot); // 2026-10：照玩家選的排法
        if (bind >= 0) {
            service.trigger(player, bind, null);
        }
    }

    /** 跳躍重擊落地不摔傷。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(final EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && service.recentlyLeapt(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        service.runtime(event.getPlayer()).reset();
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }

    /** 有角色、活著（用寶石不限遊戲模式）。 */
    private boolean hasCharacter(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() && state.hasCharacter() && !player.isDead();
    }

    /** 右鍵這個方塊有原版的用途（開門、開箱子、工作台…），不當成施放。 */
    private static boolean vanillaInteraction(final Block block) {
        final Material type = block.getType();
        if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)
                || Tag.BUTTONS.isTagged(type) || Tag.BEDS.isTagged(type) || Tag.ANVIL.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case LEVER, CRAFTING_TABLE, ENCHANTING_TABLE, BELL, LOOM, CARTOGRAPHY_TABLE, GRINDSTONE, STONECUTTER,
                 SMITHING_TABLE, LECTERN, NOTE_BLOCK, REPEATER, COMPARATOR, JUKEBOX, BEACON, RESPAWN_ANCHOR -> true;
            default -> block.getState(false) instanceof InventoryHolder;
        };
    }

    /**
     * 副手的盾不能舉起來（PoE 的格擋是被動機率）：舉盾會讓按住右鍵的連續施放斷掉。
     * 新做出來的盾已經拿掉這個功能（ItemRenderer），這裡處理以前做的。
     */
    private void disableShieldRaise(final Player player) {
        final ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand.getType() == Material.SHIELD && offHand.hasData(DataComponentTypes.BLOCKS_ATTACKS)
                && plugin.items().read(offHand).isPresent()) {
            offHand.unsetData(DataComponentTypes.BLOCKS_ATTACKS);
            player.getInventory().setItemInOffHand(offHand);
        }
    }
}
