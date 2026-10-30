package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Tags;

import java.util.Locale;
import java.util.Set;

/**
 * 連珠（2026-10）：skills.yml 裡增益技能（mechanic: guard）的 barrage 區塊。純資料，不依賴 Bukkit。
 * <p>
 * 放了之後 guard.duration 秒內，你下一次「弓或矛的投射物攻擊」會自動重複幾次：每一次重複都是重新射一次
 * （可以再打到同一個敵人），傷害少 less%，隔 gap 秒一次，不花魔力。施放時用掉身上全部的 charge 戰星，每顆多重複 perCharge 次。
 * 冷卻：技能自己的 cooldown 從施放開始算；增益真的用掉時，改成「cooldown ＋ 被強化那一招的攻擊時間 × cooldownAttackTime%」
 * （從用掉那一刻開始算，最多 maxCooldown 秒）。
 *
 * @param repeats            重複幾次（不含原本那一次）
 * @param charge             施放時用掉哪一種戰星（charges.yml 的代號；null = 不用）
 * @param perCharge          每用掉一顆多重複幾次
 * @param less               每一次重複少幾 % 傷害
 * @param gap                每一次重複隔幾秒
 * @param cooldownAttackTime 用掉之後冷卻多加「被強化技能攻擊時間」的幾 %（隨技能等級）
 * @param maxCooldown        冷卻最多幾秒
 */
public record BarrageSpec(int repeats, String charge, int perCharge, double less, double gap, Scaled cooldownAttackTime, double maxCooldown,
                          boolean spells) {

    /** 舊的寫法（強化弓／矛的投射物攻擊）。 */
    public BarrageSpec(final int repeats, final String charge, final int perCharge, final double less, final double gap,
                       final Scaled cooldownAttackTime, final double maxCooldown) {
        this(repeats, charge, perCharge, less, gap, cooldownAttackTime, maxCooldown, false);
    }

    /**
     * 2026-10-26：這個連珠能不能強化這一招。spells = true（PoE2 的 Unleash）：下一個「可以重複」的法術（沒有冷卻、不是引導、不是增益）；
     * 其他照 {@link #empowers(SkillDefinition, Set, int)}。
     */
    public boolean empowersSkill(final SkillDefinition definition, final Set<String> weaponTags, final int ownRepeats) {
        if (!spells) {
            return empowers(definition, weaponTags, ownRepeats);
        }
        return !definition.isAttack() && definition.tags().contains(Tags.SPELL) && definition.cooldown() <= 0 && ownRepeats <= 0
                && definition.dealsDamage() && definition.mechanic() != Mechanic.META && definition.mechanic() != Mechanic.AURA
                && !SupportDefinition.channels(definition); // 2026-10-30 照 PoE2（引導的法術不是 Repeatable）：說明寫了不能、這次之前程式沒擋
    }

    /** 重複最多幾次（戰星再多也不超過）。 */
    public static final int MAX_REPEATS = 12;

    /** 用掉 consumed 顆戰星之後總共重複幾次。 */
    public int repeatsWith(final int consumed) {
        return Math.min(MAX_REPEATS, repeats + Math.max(0, consumed) * perCharge);
    }

    /** 2026-10-28 照 PoE2 Barrage 的品質：重複的投射物「更少」少幾個百分點（less + delta，delta 負的）。 */
    public BarrageSpec withLess(final double delta) {
        return delta == 0 ? this : new BarrageSpec(repeats, charge, perCharge, Math.max(0, Math.min(95, less + delta)), gap, cooldownAttackTime,
                maxCooldown, spells);
    }

    /** 重複的那幾次的傷害倍率（少 50% = 0.5）。 */
    public double damageScale() {
        return Math.max(0, 1 - less / 100.0);
    }

    /** 每一次重複隔幾 tick（至少 1）。 */
    public int gapTicks() {
        return (int) Math.max(1, Math.round(gap * 20));
    }

    /**
     * 增益用掉之後的冷卻（tick）：（基礎冷卻 ＋ 攻擊時間 × percent%），最多 maxCooldown 秒，再乘冷卻的倍率（冷卻回復速度）。
     *
     * @param baseSeconds   技能自己的 cooldown（秒）
     * @param attackTicks   被強化那一招的攻擊間隔（tick）
     * @param percent       {@link #cooldownAttackTime} 在這個等級的值
     * @param cooldownScale 冷卻的倍率（1 = 沒有加成；0.8 = 冷卻短兩成）
     */
    public int usedCooldownTicks(final double baseSeconds, final double attackTicks, final double percent, final double cooldownScale) {
        final double seconds = Math.min(maxCooldown, Math.max(0, baseSeconds) + Math.max(0, attackTicks) / 20.0 * Math.max(0, percent) / 100.0);
        return (int) Math.max(1, Math.round(seconds * 20 * Math.max(0.1, cooldownScale)));
    }

    /**
     * 這一招能不能被連珠強化：弓或矛的投射物攻擊，而且是「可以重複」的——有冷卻的、自己就會重複的（複擊）不行；
     * 引導技能由呼叫端另外擋。
     *
     * @param weaponTags 現在手上武器的標籤
     */
    public static boolean empowers(final SkillDefinition definition, final Set<String> weaponTags, final int ownRepeats) {
        return definition.isAttack() && definition.mechanic() == Mechanic.PROJECTILE && definition.cooldown() <= 0 && ownRepeats <= 0
                && (weaponTags.contains(Tags.BOW) || weaponTags.contains(Tags.SPEAR));
    }

    /** skills.yml 的 barrage 區塊；沒有回傳 null。 */
    static BarrageSpec parse(final YamlNode skill) {
        if (!skill.has("barrage")) {
            return null;
        }
        final YamlNode node = skill.node("barrage");
        final String where = node.path();
        String charge = node.has("charge") ? node.string("charge", "").trim().toLowerCase(Locale.ROOT) : null;
        if (charge != null && charge.isEmpty()) {
            charge = null;
        }
        if (charge != null && !charge.matches("[a-z_]+")) {
            throw new IllegalArgumentException(where + ".charge：要寫戰星的代號（charges.yml，例如 frenzy），卻是「" + charge + "」");
        }
        final Scaled cooldown = Scaled.parse(where + ".cooldown_attack_time", node.raw("cooldown_attack_time"), 0);
        check(where, "cooldown_attack_time", cooldown.first(), 0, 5000);
        check(where, "cooldown_attack_time", cooldown.min(), 0, 5000);
        check(where, "cooldown_attack_time", cooldown.last(), 0, 5000);
        return new BarrageSpec((int) check(where, "repeats", node.integer("repeats", 2), 1, MAX_REPEATS), charge,
                (int) check(where, "per_charge", node.integer("per_charge", charge == null ? 0 : 1), 0, 5),
                check(where, "less", node.decimal("less", 50), 0, 95),
                check(where, "gap", node.decimal("gap", 0.2), 0.05, 2),
                cooldown, check(where, "max_cooldown", node.decimal("max_cooldown", 30), 1, 120), node.bool("spells", false));
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
