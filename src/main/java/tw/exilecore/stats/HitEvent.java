package tw.exilecore.stats;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 一次命中已經算好、還沒套用到目標時發出。
 * 階段 3 的異常狀態（點燃、冰緩、感電…）與生命偷取都監聽這個事件，數值引擎不用改。
 */
public final class HitEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity attacker;
    private final LivingEntity defender;
    private final SkillProfile skill;
    private final HitResult result;
    private final Player credit;
    private boolean cancelled;

    public HitEvent(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill, final HitResult result) {
        this(attacker, defender, skill, result, attacker instanceof Player player ? player : null);
    }

    /**
     * @param credit 這一下算哪個玩家的（玩家自己打的就是他；召喚物打的是主人；怪物打的是 null）
     */
    public HitEvent(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill, final HitResult result,
                    final Player credit) {
        this.attacker = attacker;
        this.defender = defender;
        this.skill = skill;
        this.result = result;
        this.credit = credit;
    }

    /** 真正打出這一下的實體（玩家、召喚物或怪物）。 */
    public LivingEntity attacker() {
        return attacker;
    }

    public LivingEntity defender() {
        return defender;
    }

    public SkillProfile skill() {
        return skill;
    }

    public HitResult result() {
        return result;
    }

    /** 這一下算哪個玩家的（召喚物打的算主人）；怪物打的是 null。 */
    public Player credit() {
        return credit;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
