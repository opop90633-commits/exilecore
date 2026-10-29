package tw.exilecore.combat;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;
import tw.exilecore.stats.SkillProfile;

/**
 * 技能命中走的傷害管道：記下「這一下是哪個技能」，再用玩家攻擊的傷害來源打下去，
 * 讓 {@link CombatListener} 用技能的標籤與數值結算（命中、暴擊、減免、HitEvent 都跟普攻同一套），
 * 原版負責受傷動畫、仇恨與擊殺歸屬（掉落與經驗）。
 * <p>
 * 技能命中預設不擊退（PoE 沒有擊退），有寫擊退的技能（碎岩擊）改成技能自己的方向與力道。
 */
public final class SkillDamage implements Listener {

    /** 正在結算的技能命中（attacker 是玩家，或用招式的 Boss）。 */
    record Context(LivingEntity attacker, LivingEntity target, SkillProfile profile, Vector knockback) {
    }

    private Context current;

    /**
     * 用技能打一個目標。
     *
     * @param knockback 擊退（null = 不擊退）
     * @return 目標在這一下之後是否還有效（活著）
     */
    public boolean hit(final Player attacker, final LivingEntity target, final SkillProfile profile, final Vector knockback) {
        final LivingEntity by = proxy == null ? null : proxy.apply(attacker, profile);
        return hitAs(by != null && by.isValid() ? by : attacker, target, profile, knockback);
    }

    /**
     * 2026-10-29 照 PoE2：這一下其實是你的召喚物放的（巨靈的指令）：回傳那隻召喚物，CombatListener 用牠的屬性表結算；
     * 不是回傳 null（照舊算你的）。技能模組接上（DjinnCommands）。
     */
    private java.util.function.BiFunction<Player, SkillProfile, LivingEntity> proxy;

    public void setHitProxy(final java.util.function.BiFunction<Player, SkillProfile, LivingEntity> proxy) {
        this.proxy = proxy;
    }

    /**
     * 怪物（Boss）用招式打一個目標：走跟怪物普攻一樣的結算（怪物的屬性表、玩家的防禦），
     * 只是技能描述換成招式的（傷害倍率、類型、標籤）。
     */
    public boolean hitAs(final LivingEntity attacker, final LivingEntity target, final SkillProfile profile, final Vector knockback) {
        if (!target.isValid() || target.isDead() || !attacker.isValid()) {
            return false;
        }
        final Context previous = current;
        current = new Context(attacker, target, profile, knockback);
        try {
            // 數值由 CombatListener 換掉，這裡的 1 只是觸發
            target.damage(1.0, attacker);
        } finally {
            current = previous;
        }
        return target.isValid() && !target.isDead();
    }

    /** 正在結算、打這個目標的技能代號（不是技能命中回傳 null）。Boss 的傷害紀錄用。 */
    public String currentSkill(final LivingEntity defender) {
        final Context context = contextFor(defender);
        return context == null ? null : context.profile().id();
    }

    /** 這個傷害事件是不是某個技能命中（給 CombatListener）。 */
    Context contextFor(final LivingEntity defender) {
        final Context context = current;
        return context != null && context.target() == defender ? context : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(final EntityKnockbackEvent event) {
        final Context context = current;
        if (context == null || event.getEntity() != context.target()) {
            return;
        }
        if (context.knockback() == null) {
            event.setCancelled(true);
            return;
        }
        // 擊退抗性（Boss）照算：抗性 100% 就推不動
        final AttributeInstance resistance = context.target().getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        final double scale = 1 - Math.max(0, Math.min(1, resistance == null ? 0 : resistance.getValue()));
        if (scale <= 0) {
            event.setCancelled(true);
        } else {
            event.setKnockback(context.knockback().clone().multiply(scale));
        }
    }
}
