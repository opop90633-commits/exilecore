package tw.exilecore.flask;

import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一瓶藥劑（或一顆護符）自己的詞綴算出來的數值（2026-10，PoE2 的藥劑詞綴）。純資料，不依賴 Bukkit。
 * <p>
 * 從物品上的 {@link ItemData}（詞綴）算：回復量、回復速度、立即回復、充能上限、每次用掉、拿到的充能……
 * 都是乘在 flasks.yml 基底數值上的倍率；沒有詞綴就是 {@link #NONE}。
 *
 * @param recovery      回復量倍率（1.45 = 增加 45%）
 * @param recoveryLow   生命／魔力低於 35% 時另外再乘的倍率
 * @param speed         回復速度倍率（時間 ÷ 這個）
 * @param instant       回復量的幾成立即回復（0–1）
 * @param minionHeal    召喚物也回復回復量的幾成（0–1）
 * @param chargesGained 拿到的充能倍率
 * @param chargesUsed   每次用掉的充能倍率
 * @param maxCharges    充能上限倍率
 * @param chargeOnKill  殺怪時多拿 1 點充能的機率（0–1）
 * @param perMinute     每分鐘自動拿到幾點充能
 * @param costOther     喝的時候另一種資源扣掉回復量的幾成（0–1）
 * @param duration      護符效果持續時間倍率
 * @param guard         護符觸發時的守護
 * @param lifeOnUse     護符觸發時回復的生命
 * @param manaOnUse     護符觸發時回復的魔力
 */
public record FlaskMods(double recovery, double recoveryLow, double speed, double instant, double minionHeal,
                        double chargesGained, double chargesUsed, double maxCharges, double chargeOnKill, double perMinute,
                        double costOther, double duration, double guard, double lifeOnUse, double manaOnUse) {

    public static final FlaskMods NONE = new FlaskMods(1, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 1, 0, 0, 0);

    /** 生命／魔力「低」的門檻（PoE2 的 low life：35%）。 */
    public static final double LOW_FRACTION = 0.35;

    /** 從物品資料算（詞綴照 affixes/flasks.yml；不認得的詞綴略過）。 */
    public static FlaskMods of(final ItemRepository repository, final ItemData data) {
        if (data == null || data.affixes().isEmpty()) {
            return NONE;
        }
        final List<Modifier> modifiers = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            for (final AffixMod mod : affix.mods()) {
                modifiers.add(mod.toModifier(rolled.vars(), affix.id()));
            }
        }
        return of(modifiers);
    }

    /** 從修飾器算（測試、說明用）。 */
    public static FlaskMods of(final List<Modifier> modifiers) {
        double recovery = 0, recoveryLow = 0, speed = 0, instant = 0, minionHeal = 0, gained = 0, used = 0, max = 0, onKill = 0, perMinute = 0,
                costOther = 0, duration = 0, guard = 0, life = 0, mana = 0;
        for (final Modifier m : modifiers) {
            final double v = m.value();
            switch (m.stat()) {
                case StatKeys.FLASK_LOCAL_RECOVERY -> recovery += pct(m);
                case StatKeys.FLASK_LOCAL_RECOVERY_LOW -> recoveryLow += pct(m);
                case StatKeys.FLASK_LOCAL_SPEED -> speed += pct(m);
                case StatKeys.FLASK_LOCAL_INSTANT -> instant += v;
                case StatKeys.FLASK_LOCAL_MINION_HEAL -> minionHeal += v;
                case StatKeys.FLASK_LOCAL_CHARGES_GAINED -> gained += pct(m);
                case StatKeys.FLASK_LOCAL_CHARGES_USED -> used += pct(m);
                case StatKeys.FLASK_LOCAL_MAX_CHARGES -> max += pct(m);
                case StatKeys.FLASK_LOCAL_CHARGE_ON_KILL -> onKill += v;
                case StatKeys.FLASK_LOCAL_CHARGES_PER_MINUTE -> perMinute += v;
                case StatKeys.FLASK_LOCAL_COST_OTHER -> costOther += v;
                case StatKeys.CHARM_LOCAL_DURATION -> duration += pct(m);
                case StatKeys.CHARM_LOCAL_GUARD -> guard += v;
                case StatKeys.CHARM_LOCAL_LIFE -> life += v;
                case StatKeys.CHARM_LOCAL_MANA -> mana += v;
                default -> {
                }
            }
        }
        return new FlaskMods(Math.max(0, 1 + recovery), Math.max(0, 1 + recoveryLow), Math.max(0.1, 1 + speed),
                Math.max(0, Math.min(1, instant / 100.0)), Math.max(0, Math.min(1, minionHeal / 100.0)),
                Math.max(0, 1 + gained), Math.max(0, 1 + used), Math.max(0.1, 1 + max), Math.max(0, Math.min(1, onKill / 100.0)),
                Math.max(0, perMinute), Math.max(0, Math.min(1, costOther / 100.0)), Math.max(0.1, 1 + duration),
                Math.max(0, guard), Math.max(0, life), Math.max(0, mana));
    }

    /** INCREASED 的值是小數（0.45）；寫成 FLAT 的照百分比點數算。 */
    private static double pct(final Modifier m) {
        return m.type() == ModType.FLAT ? m.value() / 100.0 : m.value();
    }

    /** 這瓶的充能上限（基底 × 倍率，至少 1）。 */
    public int maxCharges(final int base) {
        return Math.max(1, (int) Math.round(base * maxCharges));
    }

    /** 這瓶每次用掉的充能（基底 × 倍率，至少 1）。 */
    public int perUse(final int base) {
        return Math.max(1, (int) Math.round(base * chargesUsed));
    }

    /** 拿到 amount 點充能實際進來幾點（至少 1）。 */
    public int gained(final int amount) {
        return amount <= 0 ? 0 : Math.max(1, (int) Math.round(amount * chargesGained));
    }

    /** 回復量（low：喝的時候生命／魔力是不是低於 35%）。 */
    public double recoveryAmount(final double base, final boolean low) {
        return base * recovery * (low ? recoveryLow : 1);
    }

    /** 回復時間（秒）。 */
    public double recoveryTime(final double base) {
        return base / speed;
    }

    public boolean isNone() {
        return equals(NONE);
    }

    /** 給說明用：哪些數值不是 1／0（鍵 → 值）。 */
    public Map<String, Double> summary() {
        final Map<String, Double> map = new java.util.LinkedHashMap<>();
        if (recovery != 1) map.put("recovery", recovery);
        if (recoveryLow != 1) map.put("recoveryLow", recoveryLow);
        if (speed != 1) map.put("speed", speed);
        if (instant > 0) map.put("instant", instant);
        if (maxCharges != 1) map.put("maxCharges", maxCharges);
        if (chargesUsed != 1) map.put("chargesUsed", chargesUsed);
        if (chargesGained != 1) map.put("chargesGained", chargesGained);
        return map;
    }
}
