package tw.exilecore.stats;

import org.bukkit.entity.LivingEntity;
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
    private boolean cancelled;

    public HitEvent(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill, final HitResult result) {
        this.attacker = attacker;
        this.defender = defender;
        this.skill = skill;
        this.result = result;
    }

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
