package tw.exilecore.skill;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeEffectKeys;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2「移動中使用技能的移動速度懲罰」（Movement Speed Penalty while using skills）：
 * 放攻擊、法術的那段時間（攻擊時間／施法時間）移動速度更少 70%（原作 movement_speed_+%_final_while_performing_action -70，
 * 召喚物指令 -50），再乘上「增加／減少移動中使用技能的移動速度懲罰」（天賦樹）與「更多／更少」（衝刺突擊、鑲嵌的寶石綠色、凝息）。
 * 這次之前放技能不會變慢，那些減少懲罰的效果改成平衡等效的移動速度。
 * <p>
 * 做法：施放時在玩家的移動速度屬性上掛一個暫時的「最終倍率」修飾器（MULTIPLY_SCALAR_1：每個各乘一次，跟其他加成相乘），動作時間結束就拿掉
 * （{@link #tick} 每 tick 看一次）。位移技能（跳躍、衝刺、瞬影）、光環開關、舉盾（自己有減速）、騎坐騎（原作 -100% 懲罰）不算。
 * 原作的近戰打擊、猛擊放的時候會停下來（不能邊走邊打）；Minecraft 停住手感太差，本伺服器一律照可以邊走邊放的 70% 算。
 */
public final class SkillMovePenalty {

    /** 原作大部分技能的基礎懲罰：更少 70%。 */
    public static final double BASE = 0.70;
    /** 原作召喚物指令（Command:）的基礎懲罰：更少 50%。 */
    public static final double COMMAND = 0.50;

    private final NamespacedKey key;
    /** 玩家 → 懲罰到哪一 tick 為止。 */
    private final Map<UUID, Long> until = new HashMap<>();

    public SkillMovePenalty(final Plugin plugin) {
        this.key = new NamespacedKey(plugin, "skill_move_penalty");
    }

    /** 這個技能放的時候基礎懲罰是多少（小數；0 = 不會變慢）。純函式。 */
    public static double base(final Mechanic mechanic, final boolean command, final boolean mount, final boolean mounted) {
        if (mounted || mount || mechanic == null) {
            return 0; // 原作騎著犀牛：rhoa_movement_speed_penalty_+%_final_while_performing_action -100
        }
        return switch (mechanic) {
            case LEAP, DASH, FLICKER, AURA, GUARD -> 0; // 位移技能自己會動；光環只是開關；舉盾另外有減速（Channelling）
            default -> command ? COMMAND : BASE;
        };
    }

    /** 最後的懲罰（小數，0～1）：基礎 ×（1 ＋ 增加）× 更多。context 是技能的標籤（凝息只算被輔助的技能）。 */
    public static double penalty(final StatSheet effective, final double base, final Set<String> context) {
        if (base <= 0 || effective == null) {
            return Math.max(0, base);
        }
        return TreeEffectKeys.actionPenalty(effective, base, context);
    }

    /** 放了技能：接下來 ticks 個 tick 移動速度更少 penalty（0～1）。 */
    public void apply(final Player player, final double penalty, final double ticks, final long now) {
        if (penalty <= 0 || ticks <= 0) {
            return;
        }
        final AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(key);
        speed.addTransientModifier(new AttributeModifier(key, -Math.min(1.0, penalty), AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        until.merge(player.getUniqueId(), now + Math.max(1, Math.round(ticks)), Math::max);
    }

    /** 每 tick：動作時間過了就拿掉。 */
    public void tick(final Player player, final long now) {
        final Long end = until.get(player.getUniqueId());
        if (end != null && now < end) {
            return;
        }
        if (end != null) {
            until.remove(player.getUniqueId());
            clear(player);
        } else if (now % 20 == 0) {
            clear(player); // 插件重新載入前留下的（暫時的修飾器不存檔，只有線上的人身上可能還有）
        }
    }

    /** 現在有沒有在懲罰中（防作弊算允許速度時用）。 */
    public boolean active(final Player player) {
        return until.containsKey(player.getUniqueId());
    }

    /** 拿掉懲罰。 */
    public void clear(final Player player) {
        final AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(key) != null) {
            speed.removeModifier(key);
        }
    }

    /** 2026-10-27：離線、換角色：拿掉懲罰也忘掉時間（這次之前只有線上的人 tick 時才清）。 */
    public void forget(final Player player) {
        until.remove(player.getUniqueId());
        clear(player);
    }

    /** 這個懲罰的修飾器代號（防作弊用：算允許的速度時把它除掉，避免網路延遲誤判）。 */
    public NamespacedKey key() {
        return key;
    }
}
