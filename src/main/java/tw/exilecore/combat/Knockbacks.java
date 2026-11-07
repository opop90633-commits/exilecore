package tw.exilecore.combat;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

/** 2026-10-31：直接設速度的擊退（天賦、戰吼、昇華…）也要看擊退抗性：Boss（抗性 100%）推不動。 */
public final class Knockbacks {

    private Knockbacks() {
    }

    /** 推不動（擊退抗性 100%：Boss）。 */
    public static boolean immune(final LivingEntity target) {
        final AttributeInstance resistance = target.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        return resistance != null && resistance.getValue() >= 0.999;
    }

    /** 2026-11-08：往上推的上限（一般擊退 0.3、最大不超過這個）。 */
    public static final double MAX_LIFT = 0.45;

    /**
     * 2026-11-08 照站長「擊退好像會導致怪物飛起來」：擊退往上的那一點只在站在地上時給（原版擊退也是：在空中只推水平），
     * 所以連續被打（多發投射物、召喚物群毆）不會一下一下把牠往上疊、飛起來。這次之前每一下都加 0.25～0.3 往上的速度。
     */
    public static org.bukkit.util.Vector grounded(final LivingEntity target, final org.bukkit.util.Vector push) {
        if (push == null) {
            return null;
        }
        final org.bukkit.util.Vector out = push.clone();
        if (!target.isOnGround()) {
            out.setY(Math.min(0, out.getY()));
        } else {
            out.setY(Math.min(MAX_LIFT, out.getY()));
        }
        return out;
    }

    /** 2026-11-08：把速度直接設成擊退（在地上照給；在空中只換水平、垂直照目前的，不會一直被設成往上而飄著）。 */
    public static void set(final LivingEntity target, final org.bukkit.util.Vector push) {
        final org.bukkit.util.Vector out = grounded(target, push);
        if (!target.isOnGround()) {
            out.setY(Math.min(target.getVelocity().getY(), MAX_LIFT));
        }
        target.setVelocity(out);
    }

    /** 2026-11-08：把擊退加在目前的速度上（往上的照 {@link #grounded}），往上的總速度不超過 {@link #MAX_LIFT}。 */
    public static void add(final LivingEntity target, final org.bukkit.util.Vector push) {
        final org.bukkit.util.Vector total = target.getVelocity().add(grounded(target, push));
        total.setY(Math.min(MAX_LIFT, total.getY()));
        target.setVelocity(total);
    }
}
