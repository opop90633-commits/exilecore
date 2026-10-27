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
 * 2026-10-27 照站長「放技能減速全部移除」：SkillService 不再呼叫 {@link #apply}，放技能不會變慢；減少懲罰的效果又改回平衡等效的移動速度。
 * 保留 {@link #tick}／{@link #forget}／{@link #clear}：清掉舊版留下的慢速走路速度（存檔裡的 walk speed）與舊的屬性修飾器，已經在遊戲裡的人會恢復正常。
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
        // 2026-10-27 照站長「第三人稱放技能減速會讓視角變大變小」：改成調低原版的走路速度（walk speed）。
        // 客戶端的視野（FOV）照「移動速度屬性 ÷ 走路速度」算，兩個一起變小比例不變，視角就不會縮放；
        // 我們的移動速度加成都是乘在基礎值上（MULTIPLY_SCALAR_1），所以實際變慢的幅度跟以前掛「更少 70%」一樣。
        // 這次之前在移動速度屬性上掛 MULTIPLY_SCALAR_1 修飾器，每放一次技能視角就縮一下。
        final AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(key) != null) {
            speed.removeModifier(key); // 舊版留下的
        }
        player.setWalkSpeed((float) (DEFAULT_WALK * (1 - Math.min(MAX_PENALTY, penalty))));
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

    /** 拿掉懲罰（走路速度回到原版預設；伺服器當掉時存檔裡留下的慢速，下次上線 tick 也會在這裡復原）。 */
    public void clear(final Player player) {
        final AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(key) != null) {
            speed.removeModifier(key);
        }
        if (Math.abs(player.getWalkSpeed() - DEFAULT_WALK) > 1e-4) {
            player.setWalkSpeed(DEFAULT_WALK);
        }
    }

    /** 原版的走路速度（Bukkit 的 walk speed 預設 0.2）。 */
    public static final float DEFAULT_WALK = 0.2f;
    /** 懲罰最多到 99%（走路速度 0 時客戶端的視野算法會除以 0）。 */
    static final double MAX_PENALTY = 0.99;

    /** 目前的懲罰把速度乘了多少（1 = 沒有）；防作弊算允許速度時除回來。 */
    public static double factor(final Player player) {
        final float walk = player.getWalkSpeed();
        return walk <= 0 || walk >= DEFAULT_WALK ? 1.0 : walk / DEFAULT_WALK;
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
