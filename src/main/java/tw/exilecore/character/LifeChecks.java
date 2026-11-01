package tw.exilecore.character;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tw.exilecore.ExileCore;

/**
 * 2026-11-01 照 PoE2：「滿血」的統一判斷（目標是任何生物時用；玩家自己有 {@link PlayerState} 就直接用 {@link PlayerState#isFullLife()}）。
 * <ul>
 *   <li>玩家：目前生命 ≥ 沒被保留的最大生命（保留生命——血契——時原版血條永遠不滿，這次之前對玩家的「滿血」永遠不成立）。</li>
 *   <li>怪物：原版血量 ≥ 上限的 {@link #ENTITY_FULL_FRACTION}。</li>
 * </ul>
 * 低血（35%）照最大生命算，不走這裡。
 */
public final class LifeChecks {

    /** 怪物原版血量算滿血的比例（浮點誤差；這次之前各處各用 0.995／0.999，統一成 0.999）。 */
    public static final double ENTITY_FULL_FRACTION = 0.999;

    private LifeChecks() {
    }

    /** 純邏輯：原版血量比例算不算滿血。 */
    public static boolean fullFraction(final double fraction) {
        return fraction >= ENTITY_FULL_FRACTION;
    }

    /** 這個生物現在是不是滿血（玩家照沒被保留的最大生命）。 */
    public static boolean isFullLife(final LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        final PlayerState state = playerState(entity);
        if (state != null) {
            return state.isFullLife();
        }
        return fullFraction(vanillaFraction(entity));
    }

    /** 原版血量比例（怪物；上限讀不到時當滿的）。 */
    public static double vanillaFraction(final LivingEntity entity) {
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null || max.getValue() <= 0 ? 1 : entity.getHealth() / max.getValue();
    }

    /** 有角色的玩家的狀態；不是玩家、插件還沒載入（單元測試）就是 null。 */
    private static PlayerState playerState(final LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        try {
            final ExileCore plugin = JavaPlugin.getPlugin(ExileCore.class);
            if (plugin.players() == null) {
                return null;
            }
            final PlayerState state = plugin.players().get(player);
            return state != null && state.hasCharacter() ? state : null;
        } catch (final RuntimeException ignored) {
            return null;
        }
    }
}
