package tw.exilecore.item;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 武器「本身」的詞綴（本地加成）：改的是這把武器的基礎數值（各類型傷害、攻擊速度、暴擊率），不是角色的全域屬性。
 * 角色的全域增加（天賦、其他裝備）再乘上去，武器面板上的數字也會直接變（變過的數字顯示成藍色）。
 * <p>
 * 出在武器上時，下面這些都算本地詞綴（PoE 的規則）：
 * <ul>
 *   <li>stat 以 {@code local.} 開頭的修飾器：{@value #PHYSICAL}、{@value #ATTACK_SPEED}、{@value #CRIT}（「增加 x%」）</li>
 *   <li>「增加 x% 物理傷害」（{@code damage} INCREASED，標籤只有 physical）：跟 {@value #PHYSICAL} 相加，改武器的物理傷害</li>
 *   <li>「增加 x% 攻擊速度」「增加 x% 暴擊率」（{@code attack.speed}／{@code crit.chance} INCREASED，沒有標籤）：
 *       改武器的每秒攻擊次數與基礎暴擊率（PoE2：5% 基礎、增加 22% → 面板 6.1%）</li>
 *   <li>「攻擊附加 x 到 y 某類型傷害」（{@code damage.added.min/max}，標籤是 attack + 傷害類型）：
 *       直接加到武器的那一類傷害上（物理的附加會再吃本地的物理增加）</li>
 * </ul>
 * 同樣的詞綴出在戒指之類的非武器上，照舊是全域的。沒有鑑定的物品不算詞綴。
 *
 * @param physical    物理傷害增加（小數，0.3 = 30%）
 * @param attackSpeed 攻擊速度增加
 * @param crit        暴擊率增加
 * @param added       各類型的附加傷害 {最小, 最大}
 */
public record LocalMods(double physical, double attackSpeed, double crit, Map<DamageType, double[]> added) {

    public static final String PREFIX = "local.";
    public static final String PHYSICAL = "local.physical";
    public static final String ATTACK_SPEED = "local.attack_speed";
    public static final String CRIT = "local.crit";
    /** FLAT：武器的基礎暴擊率 +x%（PoE2「+x% 暴擊率」）。 */
    public static final String CRIT_BASE = "local.crit_base";
    /** INCREASED：這件物品的屬性需求（2026-10-21，PoE2 腐化「減少 x% 屬性需求」；武器、防具都可以）。 */
    public static final String REQUIREMENTS = "local.requirements";

    private static final String ATTACK_TAG = "attack";

    /** 認得的本地屬性（資料檢查用）。 */
    public static final Set<String> KNOWN = Set.of(PHYSICAL, ATTACK_SPEED, CRIT, CRIT_BASE, REQUIREMENTS);

    public static final LocalMods NONE = new LocalMods(0, 0, 0, Map.of());

    public LocalMods {
        added = added.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(added));
    }

    /** 舊的建構方式（沒有附加傷害）。 */
    public LocalMods(final double physical, final double attackSpeed, final double crit) {
        this(physical, attackSpeed, crit, Map.of());
    }

    public static boolean isLocal(final String stat) {
        return stat != null && stat.startsWith(PREFIX);
    }

    /** 出在武器上時，這條修飾器是不是只改武器自己的數值（不進角色屬性表）。 */
    public static boolean isWeaponLocal(final AffixMod mod) {
        return mod.isLocal() || isLocalPhysicalIncrease(mod.stat(), mod.type(), mod.tags())
                || isUntaggedIncrease(mod.stat(), mod.type(), mod.tags(), StatKeys.ATTACK_SPEED)
                || isUntaggedIncrease(mod.stat(), mod.type(), mod.tags(), StatKeys.CRIT_CHANCE)
                || addedType(mod.stat(), mod.type(), mod.tags()) != null;
    }

    /** 「增加 x% 攻擊速度」「增加 x% 暴擊率」：沒有標籤的 INCREASED（有標籤的，例如法術暴擊、召喚物攻速，照舊是全域的）。 */
    private static boolean isUntaggedIncrease(final String stat, final ModType type, final Set<String> tags, final String key) {
        return key.equals(stat) && type == ModType.INCREASED && tags.isEmpty();
    }

    /** 「增加 x% 物理傷害」：damage、INCREASED、標籤只有 physical。 */
    private static boolean isLocalPhysicalIncrease(final String stat, final ModType type, final Set<String> tags) {
        return StatKeys.DAMAGE.equals(stat) && type == ModType.INCREASED
                && tags.size() == 1 && tags.contains(DamageType.PHYSICAL.tag());
    }

    /** 「攻擊附加 x 到 y 某類型傷害」的傷害類型；不是這種修飾器回傳 null。 */
    private static DamageType addedType(final String stat, final ModType type, final Set<String> tags) {
        if (type != ModType.FLAT || !(StatKeys.DAMAGE_ADDED_MIN.equals(stat) || StatKeys.DAMAGE_ADDED_MAX.equals(stat))
                || tags.size() != 2 || !tags.contains(ATTACK_TAG)) {
            return null;
        }
        for (final String tag : tags) {
            if (!ATTACK_TAG.equals(tag)) {
                return DamageType.fromTag(tag);
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return physical == 0 && attackSpeed == 0 && crit == 0 && added.isEmpty();
    }

    /** 這個類型有沒有附加傷害（面板上畫成藍色用）。 */
    public boolean hasAdded(final DamageType type) {
        return added.containsKey(type);
    }

    /**
     * 這件物品的本地加成：固定詞綴＋（已鑑定的）詞綴裡的本地修飾器加總（見 {@link #isWeaponLocal}）。
     * 非武器、找不到基底回傳 {@link #NONE}。
     */
    public static LocalMods of(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (base == null || !base.isWeapon()) {
            return NONE;
        }
        final List<Modifier> all = new java.util.ArrayList<>();
        if (base.implicit() != null) {
            for (final AffixMod mod : base.implicit().mods()) {
                if (isWeaponLocal(mod)) {
                    all.add(mod.toModifier(data.implicitVars(), "固定詞綴"));
                }
            }
        }
        if (data.identified()) {
            final double unveiledScale = 1 + ItemStats.unveiledMagnitude(repository, data); // 比例（0.8 = 80%）
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                if (affix == null) {
                    continue;
                }
                for (final AffixMod mod : affix.mods()) {
                    if (isWeaponLocal(mod)) {
                        Modifier modifier = mod.toModifier(rolled.vars(), affix.id());
                        if (unveiledScale != 1 && Veiled.isUnveiled(rolled.id())) {
                            modifier = new Modifier(modifier.stat(), modifier.type(), modifier.value() * unveiledScale, modifier.tags(), modifier.source());
                        }
                        all.add(modifier);
                    }
                }
            }
        }
        if (repository != null) {
            all.addAll(Runes.weaponLocal(repository, base, data)); // 2026-10-17：符文的本地加成也改武器面板
        }
        double physical = 0;
        double attackSpeed = 0;
        double crit = 0;
        final Map<DamageType, double[]> added = new EnumMap<>(DamageType.class);
        for (final Modifier modifier : all) {
            if (isLocalPhysicalIncrease(modifier.stat(), modifier.type(), modifier.tags())) {
                physical += modifier.value();
                continue;
            }
            if (isUntaggedIncrease(modifier.stat(), modifier.type(), modifier.tags(), StatKeys.ATTACK_SPEED)) {
                attackSpeed += modifier.value();
                continue;
            }
            if (isUntaggedIncrease(modifier.stat(), modifier.type(), modifier.tags(), StatKeys.CRIT_CHANCE)) {
                crit += modifier.value();
                continue;
            }
            final DamageType addedType = addedType(modifier.stat(), modifier.type(), modifier.tags());
            if (addedType != null) {
                final double[] range = added.computeIfAbsent(addedType, ignored -> new double[2]);
                range[StatKeys.DAMAGE_ADDED_MIN.equals(modifier.stat()) ? 0 : 1] += modifier.value();
                continue;
            }
            switch (modifier.stat()) {
                case PHYSICAL -> physical += modifier.value();
                case ATTACK_SPEED -> attackSpeed += modifier.value();
                case CRIT -> crit += modifier.value();
                default -> {
                    // 不認得的本地屬性：資料檢查會擋下；執行時忽略
                }
            }
        }
        added.values().removeIf(range -> range[0] <= 0 && range[1] <= 0);
        final LocalMods result = new LocalMods(physical, attackSpeed, crit, added);
        return result.isEmpty() ? NONE : result;
    }
}
