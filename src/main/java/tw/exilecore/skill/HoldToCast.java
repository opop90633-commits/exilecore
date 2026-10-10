package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import tw.exilecore.ExileCore;

import java.util.function.Predicate;

/**
 * 按住左鍵連續施放（config.yml 的 combat.hold-left-click，預設開）。
 * <p>
 * 原版 Java 客戶端按住左鍵時，只有「挖方塊」會一直送東西給伺服器（每 tick 揮一次手）；點空氣、點實體都只有按下去的那一次
 * （Minecraft 26.2 的 Minecraft.continueAttack）。所以在戰鬥區域裡：
 * <ul>
 *   <li>方塊互動距離拉到 64 格（原版的上限）、挖方塊的速度變成 0：準星幾乎都落在方塊上，按住左鍵＝一直在「挖」，
 *       但方塊永遠挖不動、沒有裂痕；伺服器每 tick 收到揮手，就當成左鍵還按著（SkillInputListener）。</li>
 *   <li>武器帶原版的「攻擊距離」元件（最近 64 格，ItemRenderer）：客戶端找準星目標時跳過 64 格內的實體，
 *       怪物擋在前面時準星也落在後面的方塊上（不然準星一停在怪物身上就斷掉）。要打誰由伺服器沿視線找（SkillTargets.aimed）。</li>
 * </ul>
 * 營地裡、創造模式、沒有角色時拿掉。修飾器是暫時的（不寫進玩家存檔），插件拿掉就沒了。
 * 副作用：準星指著的遠處方塊會有原版的黑色外框；按住時那裡冒一點挖方塊的碎屑（敲擊聲由介面資源包消掉）。
 */
final class HoldToCast {

    /** 右鍵 NPC、玩家改由伺服器沿視線找的距離（原版的實體互動距離 3 格，寬一點比較好點）。 */
    static final double ENTITY_REACH = 4.5;

    private final ExileCore plugin;
    private final SkillService service;
    private final NamespacedKey reachKey;
    private final NamespacedKey miningKey;

    /** 戰鬥中手上一直拿著快捷列的第 9 格（武器顯示在那裡；數字鍵 3–8 放技能、1、2 喝藥）。 */
    static final int COMBAT_HOTBAR_SLOT = 8;

    HoldToCast(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.reachKey = new NamespacedKey(plugin, "hold_cast_reach");
        this.miningKey = new NamespacedKey(plugin, "hold_cast_no_mining");
    }

    /** config.yml 的 combat.hold-left-click（/ec reload 後馬上生效）。 */
    boolean enabled() {
        return plugin.getConfig().getBoolean("combat.hold-left-click", true);
    }

    /** 這個玩家現在用不用按住左鍵（開著、走技能系統、不在營地）。 */
    boolean active(final Player player) {
        return enabled() && service.eligible(player) && !service.inTown(player);
    }

    /** 加上或拿掉方塊距離與挖掘速度（每 10 tick、換角色、死掉重生時）；主手的舊武器順便重畫（補上攻擊距離元件）。 */
    void sync(final Player player) {
        final boolean want = active(player);
        set(player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE), reachKey, want, SkillMath.HOLD_CAST_REACH - SkillMath.VANILLA_BLOCK_REACH,
                AttributeModifier.Operation.ADD_NUMBER);
        set(player.getAttribute(Attribute.BLOCK_BREAK_SPEED), miningKey, want, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        if (service.eligible(player) && !service.inTown(player) && player.getInventory().getHeldItemSlot() != COMBAT_HOTBAR_SLOT) {
            player.getInventory().setHeldItemSlot(COMBAT_HOTBAR_SLOT); // 進到可以戰鬥的地方：手上自動換成第 9 格
        }
        if (plugin.items() != null && service.eligible(player)) {
            final ItemStack hand = player.getInventory().getItemInMainHand();
            if (plugin.items().refresh(hand)) {
                player.getInventory().setItemInMainHand(hand);
            }
        }
    }

    /** 拿掉（下線、插件關閉）。 */
    void clear(final Player player) {
        set(player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE), reachKey, false, 0, AttributeModifier.Operation.ADD_NUMBER);
        set(player.getAttribute(Attribute.BLOCK_BREAK_SPEED), miningKey, false, 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    private static void set(final AttributeInstance attribute, final NamespacedKey key, final boolean want, final double amount,
                            final AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        final boolean has = attribute.getModifier(key) != null;
        if (want && !has) {
            attribute.addTransientModifier(new AttributeModifier(key, amount, operation));
        } else if (!want && has) {
            attribute.removeModifier(key);
        }
    }

    /**
     * 右鍵的方塊在原版的距離內（開門、開箱子照原版）。方塊互動距離拉長以後，客戶端會送很遠的方塊過來，
     * 太遠的不給原版用（當成施放），才不會隔著 60 格開門。
     */
    static boolean withinVanillaReach(final Player player, final Block block) {
        final Location eye = player.getEyeLocation();
        return SkillMath.withinVanillaReach(eye.getX(), eye.getY(), eye.getZ(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * 準星（視線）上最近的、符合條件的實體：中間被方塊擋住就沒有。拿著武器時客戶端的準星不會停在實體上，
     * 右鍵 NPC、蹲下右鍵玩家改用這個找。
     */
    static Entity lookedAt(final Player player, final Predicate<Entity> filter) {
        final Location eye = player.getEyeLocation();
        final RayTraceResult result = player.getWorld().rayTrace(eye, eye.getDirection(), ENTITY_REACH, FluidCollisionMode.NEVER, true, 0.3,
                entity -> entity != player && filter.test(entity));
        return result == null ? null : result.getHitEntity();
    }
}
