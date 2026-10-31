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
}
