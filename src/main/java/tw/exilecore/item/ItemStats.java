package tw.exilecore.item;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 從物品資料算出「這件物品提供什麼」：顯示用的數值、裝備需求、給屬性表的修飾器。
 * 純函式，不依賴 Bukkit。
 * <p>
 * 基底數值是物品等級 1 的值，依物品等級線性放大（items.yml 的 scaling），
 * 用來取代 PoE 那幾百個材質階級不同的基底；品質再加成一次。
 */
public final class ItemStats {

    private ItemStats() {
    }

    /** 顯示與計算共用的數值快照。 */
    public record Snapshot(Map<DamageType, double[]> damage, double aps, double crit,
                           double armour, double evasion, double energyShield, double block,
                           ItemBase.Requirements requirements) {
    }

    public static Snapshot snapshot(final ItemRepository repository, final ItemBase base, final ItemData data) {
        final ItemRepository.Scaling scaling = repository.scaling();
        final double levelFactorDamage = 1 + scaling.damagePerLevel() * (data.ilvl() - 1);
        final double levelFactorDefence = 1 + scaling.defencePerLevel() * (data.ilvl() - 1);
        final double qualityWeapon = 1 + data.quality() * scaling.qualityWeaponPercent() / 100.0;
        final double qualityArmour = 1 + data.quality() * scaling.qualityArmourPercent() / 100.0;

        final Map<DamageType, double[]> damage = new EnumMap<>(DamageType.class);
        for (final Map.Entry<DamageType, int[]> entry : base.damage().entrySet()) {
            final double quality = entry.getKey() == DamageType.PHYSICAL ? qualityWeapon : 1.0;
            damage.put(entry.getKey(), new double[] {
                    Math.round(entry.getValue()[0] * levelFactorDamage * quality),
                    Math.round(entry.getValue()[1] * levelFactorDamage * quality)});
        }
        return new Snapshot(damage, base.aps(), base.crit(),
                Math.round(base.armour() * levelFactorDefence * qualityArmour),
                Math.round(base.evasion() * levelFactorDefence * qualityArmour),
                Math.round(base.energyShield() * levelFactorDefence * qualityArmour),
                base.block(),
                requirements(repository, base, data.ilvl()));
    }

    /** 裝備需求：等級 = max(基底等級, 物品等級 × 0.9)；屬性需求隨物品等級放大。 */
    public static ItemBase.Requirements requirements(final ItemRepository repository, final ItemBase base, final int itemLevel) {
        final double factor = 1 + repository.scaling().requirementPerLevel() * (itemLevel - 1);
        final int level = Math.max(base.requirements().level(), (int) Math.round(itemLevel * 0.9));
        return new ItemBase.Requirements(level,
                (int) Math.round(base.requirements().str() * factor),
                (int) Math.round(base.requirements().dex() * factor),
                (int) Math.round(base.requirements().intelligence() * factor));
    }

    /**
     * 這件物品給屬性表的所有修飾器：基底數值（武器傷害、防禦）、固定詞綴、已鑑定的詞綴。
     * 未鑑定的物品只給基底數值與固定詞綴。
     */
    public static List<Modifier> modifiers(final ItemRepository repository, final ItemData data) {
        final ItemBase base = repository.base(data.base());
        if (base == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final Snapshot snapshot = snapshot(repository, base, data);
        final String source = base.name();

        for (final Map.Entry<DamageType, double[]> entry : snapshot.damage().entrySet()) {
            final Set<String> tags = Set.of(entry.getKey().tag());
            list.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, entry.getValue()[0], tags, source));
            list.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, entry.getValue()[1], tags, source));
        }
        if (snapshot.aps() > 0) {
            list.add(Modifier.flat(StatKeys.WEAPON_APS, snapshot.aps(), source));
        }
        if (snapshot.crit() > 0) {
            list.add(Modifier.flat(StatKeys.WEAPON_CRIT, snapshot.crit(), source));
        }
        if (snapshot.armour() > 0) {
            list.add(Modifier.flat(StatKeys.ARMOUR, snapshot.armour(), source));
        }
        if (snapshot.evasion() > 0) {
            list.add(Modifier.flat(StatKeys.EVASION, snapshot.evasion(), source));
        }
        if (snapshot.energyShield() > 0) {
            list.add(Modifier.flat(StatKeys.ES_MAX, snapshot.energyShield(), source));
        }
        if (snapshot.block() > 0) {
            list.add(Modifier.flat(StatKeys.BLOCK_CHANCE, snapshot.block(), source));
        }
        if (base.implicit() != null && !base.implicit().mods().isEmpty()) {
            list.addAll(base.implicit().modifiers(data.implicitVars(), source + " 固定詞綴"));
        }
        if (data.identified()) {
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                if (affix == null) {
                    continue;
                }
                for (final AffixMod mod : affix.mods()) {
                    list.add(mod.toModifier(rolled.vars(), source + " " + affix.id() + " T" + rolled.tier()));
                }
            }
        }
        return list;
    }

    /** 物品的顯示名稱（不含顏色）。 */
    public static String displayName(final ItemRepository repository, final ItemData data) {
        final ItemBase base = repository.base(data.base());
        final String baseName = base == null ? data.base() : base.name();
        if (!data.identified()) {
            return "未鑑定的" + baseName;
        }
        switch (data.rarity()) {
            case MAGIC -> {
                String prefix = "";
                String suffix = "";
                for (final RolledAffix rolled : data.affixes()) {
                    final Affix affix = repository.affix(rolled.id());
                    if (affix == null) {
                        continue;
                    }
                    if (affix.type() == AffixType.PREFIX) {
                        prefix = affix.nameFor(rolled.tier());
                    } else {
                        suffix = affix.nameFor(rolled.tier());
                    }
                }
                return prefix + baseName + suffix;
            }
            case RARE, UNIQUE -> {
                return data.name() == null || data.name().isBlank() ? baseName : data.name();
            }
            default -> {
                return baseName;
            }
        }
    }

    /** 每條詞綴的顯示文字與階級，依前綴在前、後綴在後排序。 */
    public record AffixLine(String text, int tier, AffixType type) {
    }

    public static List<AffixLine> affixLines(final ItemRepository repository, final ItemData data) {
        final List<AffixLine> prefixes = new ArrayList<>();
        final List<AffixLine> suffixes = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            final AffixLine line = new AffixLine(affix.render(rolled.vars()), rolled.tier(), affix.type());
            if (affix.type() == AffixType.PREFIX) {
                prefixes.add(line);
            } else {
                suffixes.add(line);
            }
        }
        prefixes.addAll(suffixes);
        return prefixes;
    }
}
