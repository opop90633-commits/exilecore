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
 * 基底數值依物品等級線性放大（items.yml 的 scaling；寫的數值是 base_level 那一級的值，見 {@link ItemBase}），
 * 品質再加成一次。傳奇的基底數值固定在它那一階（{@link #statLevel}）；只有傳奇武器的基底傷害會照物品等級
 * 從那一階再往上長（{@link #damageFactor}）。
 */
public final class ItemStats {

    /** 沒有需求（珠寶）。 */
    public static final ItemBase.Requirements NO_REQUIREMENTS = new ItemBase.Requirements(1, 0, 0, 0);

    private ItemStats() {
    }

    /**
     * 顯示與計算共用的數值快照（武器的數值已經算進本地詞綴，見 {@link LocalMods}）。
     *
     * @param local 武器本身的詞綴加成（顯示時把變過的數字畫成藍色用）
     */
    public record Snapshot(Map<DamageType, double[]> damage, double aps, double crit,
                           double armour, double evasion, double energyShield, double block,
                           ItemBase.Requirements requirements, LocalMods local) {
    }

    public static Snapshot snapshot(final ItemRepository repository, final ItemBase itemBase, final ItemData data) {
        final ItemBase base = statBase(repository, itemBase, data);
        final ItemRepository.Scaling scaling = repository.scaling();
        final int statLevel = statLevel(repository, base, data);
        final double levelFactorDamage = damageFactor(base, scaling.damagePerLevel(), statLevel, scaling.cap(data.ilvl()), data);
        final double levelFactorDefence = levelFactor(base, scaling.defencePerLevel(), statLevel);
        final double qualityWeapon = 1 + data.quality() * scaling.qualityWeaponPercent() / 100.0;
        final double qualityArmour = 1 + data.quality() * scaling.qualityArmourPercent() / 100.0;

        // 武器本身的詞綴（本地加成）：先改武器自己的數值，角色的全域增加再乘上去
        // 物理：(基底 + 攻擊附加物理) × 品質 × (1 + 本地增加物理)；其他類型：基底 + 攻擊附加
        final LocalMods local = LocalMods.of(repository, base, data);
        final Map<DamageType, double[]> damage = new EnumMap<>(DamageType.class);
        for (final DamageType type : DamageType.values()) {
            final int[] baseRange = base.damage().get(type);
            final double[] added = local.added().get(type);
            if (baseRange == null && added == null) {
                continue;
            }
            final boolean physical = type == DamageType.PHYSICAL;
            final double quality = physical ? qualityWeapon : 1.0;
            final double extra = physical ? 1 + local.physical() : 1.0; // 沒有本地詞綴時乘 1，數字跟以前一模一樣
            double min = baseRange == null ? 0 : baseRange[0] * levelFactorDamage;
            double max = baseRange == null ? 0 : baseRange[1] * levelFactorDamage;
            if (added != null) {
                min += added[0];
                max += added[1];
            }
            damage.put(type, new double[] {Math.round(min * quality * extra), Math.round(max * quality * extra)});
        }
        final double aps = local.attackSpeed() == 0 ? base.aps() : roundTo(base.aps() * (1 + local.attackSpeed()), 100);
        final double critBase = base.crit() + (base.isWeapon() ? localSum(repository, base, data, LocalMods.CRIT_BASE, ModType.FLAT) : 0);
        final double crit = local.crit() == 0 && critBase == base.crit() ? base.crit() : roundTo(critBase * (1 + local.crit()), 100);
        // 防具、盾本身的防禦詞綴是本地的（2026-10 PoE2）：(基底 + 附加) ×（1 + 增加）
        final double armour = defence(repository, base, data, StatKeys.ARMOUR, base.armour() * levelFactorDefence);
        final double evasion = defence(repository, base, data, StatKeys.EVASION, base.evasion() * levelFactorDefence);
        final double energyShield = defence(repository, base, data, StatKeys.ES_MAX, base.energyShield() * levelFactorDefence);
        return new Snapshot(damage, aps, crit,
                Math.round(armour * qualityArmour),
                Math.round(evasion * qualityArmour),
                Math.round(energyShield * qualityArmour),
                base.block() <= 0 ? 0 : roundTo(base.block() * Math.max(0, 1 + localSum(repository, base, data, StatKeys.BLOCK_CHANCE, ModType.INCREASED)), 10),
                requirements(repository, itemBase, data), local);
    }

    /**
     * 基底數值照哪個物品等級算（2026-10）：一般就是物品等級；傳奇固定在基底寫的那一階（base_level）
     * （舊的基底沒有 base_level 的照物品等級，跟以前一樣；寫了 stat_base 的見 {@link #statBase}）。
     */
    public static int statLevel(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.unique() != null && base.baseLevel() > 0) {
            return base.baseLevel();
        }
        return Math.max(1, repository.scaling().cap(data.ilvl())); // 2026-10-18：超過 stat_level_cap 的照上限算（商人照角色等級賣，會到 120）
    }

    /** 算數值用的基底：傳奇寫了 stat_base 的用那個（原本那一階、固定數值），其他就是物品本身的基底。 */
    public static ItemBase statBase(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.unique() == null) {
            return base;
        }
        final ItemBase statBase = repository.uniqueStatBase(data.unique());
        return statBase == null || !repository.canonicalBase(statBase.id()).equals(repository.canonicalBase(base.id())) ? base : statBase;
    }

    /** 基底數值的倍率：固定的基底 1；其他 = 曲線(物品等級) ÷ 曲線(寫的數值是哪一級)。 */
    public static double levelFactor(final ItemBase base, final double perLevel, final int level) {
        if (base.fixed()) {
            return 1;
        }
        return (1 + perLevel * (Math.max(1, level) - 1)) / (1 + perLevel * (base.statLevel() - 1));
    }

    /**
     * 武器基底傷害的倍率。一般物品就是 {@link #levelFactor}。
     * 傳奇武器（2026-10）：基底傷害照物品等級往上長——跟稀有武器同一條曲線，從它那一階（{@link #uniqueTier}）算起，
     * 物品等級比那一階高多少就多多少，不會比那一階低（低物品等級的舊傳奇數值不變）。
     * 等級與屬性需求、固定詞綴、防禦照舊固定在那一階；詞綴的附加傷害不跟著長。
     */
    public static double damageFactor(final ItemBase base, final double perLevel, final int statLevel, final int itemLevel, final ItemData data) {
        final int tier = data.unique() != null && base.isWeapon() ? uniqueTier(base) : 0;
        if (tier <= 0) {
            return levelFactor(base, perLevel, statLevel);
        }
        final int level = Math.max(tier, itemLevel); // itemLevel 已經夾過 stat_level_cap
        return (1 + perLevel * (level - 1)) / (1 + perLevel * (tier - 1));
    }

    /**
     * 傳奇的基底數值是哪一階的：寫了 base_level 的就是那一級；固定數值的基底（fixed，含傳奇 stat_base 指的那些）是它的等級需求。
     * 舊的基底（兩個都沒寫，數值本來就照物品等級算）回傳 0。
     */
    static int uniqueTier(final ItemBase base) {
        if (base.baseLevel() > 0) {
            return base.baseLevel();
        }
        return base.fixed() ? Math.max(1, base.requirements().level()) : 0;
    }

    /** 這件物品（照它的物品等級，傳奇照它那一階）的固定詞綴；沒有回傳 null。 */
    public static Implicit implicit(final ItemRepository repository, final ItemBase itemBase, final ItemData data) {
        final ItemBase base = statBase(repository, itemBase, data);
        return base.implicitAt(statLevel(repository, base, data), repository.scaling().damagePerLevel());
    }

    /** 防具（有基礎護甲、閃避或能量護盾的非武器）身上的護甲／閃避／能量護盾詞綴（沒有標籤的固定值與增加）是本地的。 */
    public static boolean isArmourLocal(final ItemBase base, final AffixMod mod) {
        if (base == null || base.isWeapon() || base.armour() + base.evasion() + base.energyShield() <= 0 || !mod.tags().isEmpty()) {
            return false;
        }
        if (mod.type() != ModType.FLAT && mod.type() != ModType.INCREASED) {
            return false;
        }
        final String stat = mod.stat();
        if (StatKeys.BLOCK_CHANCE.equals(stat)) {
            return mod.type() == ModType.INCREASED && base.block() > 0; // 2026-10-23：盾的「增加 x% 格擋率」是本地的（照 PoE2），算進盾上寫的格擋率
        }
        return StatKeys.ARMOUR.equals(stat) && base.armour() > 0
                || StatKeys.EVASION.equals(stat) && base.evasion() > 0
                || StatKeys.ES_MAX.equals(stat) && base.energyShield() > 0;
    }

    private static double defence(final ItemRepository repository, final ItemBase base, final ItemData data, final String stat, final double baseValue) {
        if (baseValue <= 0) {
            return 0;
        }
        final double flat = localSum(repository, base, data, stat, ModType.FLAT);
        final double increased = localSum(repository, base, data, stat, ModType.INCREASED);
        return (baseValue + flat) * Math.max(0, 1 + increased);
    }

    /** 這件物品固定詞綴＋已鑑定詞綴裡，某個本地屬性的加總（防具的防禦詞綴、武器的基礎暴擊率）。 */
    private static double localSum(final ItemRepository repository, final ItemBase base, final ItemData data, final String stat, final ModType type) {
        double total = 0;
        if (base.implicit() != null) {
            for (final AffixMod mod : base.implicit().mods()) {
                if (stat.equals(mod.stat()) && mod.type() == type && (mod.isLocal() || isArmourLocal(base, mod))) {
                    total += mod.toModifier(data.implicitVars(), "").value();
                }
            }
        }
        if (data.identified()) {
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                if (affix == null) {
                    continue;
                }
                for (final AffixMod mod : affix.mods()) {
                    if (stat.equals(mod.stat()) && mod.type() == type && (mod.isLocal() || isArmourLocal(base, mod))) {
                        total += mod.toModifier(rolled.vars(), "").value();
                    }
                }
            }
        }
        return total;
    }

    /** 四捨五入到小數（scale = 100 → 兩位）。 */
    static double roundTo(final double value, final int scale) {
        return Math.round(value * scale) / (double) scale;
    }

    /** 裝備需求：等級 = max(基底等級, 物品等級 × 0.9)；屬性需求隨物品等級放大。 */
    public static ItemBase.Requirements requirements(final ItemRepository repository, final ItemBase base, final int itemLevel) {
        final int capped = repository.scaling().cap(itemLevel);
        return requirements(repository, base, capped, capped, 0);
    }

    /** 同上，這件物品的（傳奇的屬性需求照它那一階、等級需求至少是那一階）。 */
    public static ItemBase.Requirements requirements(final ItemRepository repository, final ItemBase itemBase, final ItemData data) {
        final ItemBase base = statBase(repository, itemBase, data);
        final int statLevel = statLevel(repository, base, data);
        final ItemBase.Requirements plain = requirements(repository, base, repository.scaling().cap(data.ilvl()), statLevel,
                data.unique() != null && base.baseLevel() > 0 ? statLevel : 0);
        // 2026-10-21：腐化「減少 x% 屬性需求」（本地；等級需求不變）
        final double scale = 1 + localSum(repository, itemBase, data, LocalMods.REQUIREMENTS, ModType.INCREASED);
        if (Math.abs(scale - 1) < 1e-9) {
            return plain;
        }
        return new ItemBase.Requirements(plain.level(), scaleRequirement(plain.str(), scale), scaleRequirement(plain.dex(), scale),
                scaleRequirement(plain.intelligence(), scale));
    }

    static int scaleRequirement(final int value, final double scale) {
        return value <= 0 ? 0 : (int) Math.max(1, Math.round(value * Math.max(0, scale)));
    }

    private static ItemBase.Requirements requirements(final ItemRepository repository, final ItemBase base, final int itemLevel,
                                                      final int statLevel, final int floor) {
        if (base.isJewel()) {
            return NO_REQUIREMENTS; // 珠寶放進天賦樹就生效，沒有等級與屬性需求（PoE 的做法）
        }
        final double factor = levelFactor(base, repository.scaling().requirementPerLevel(), statLevel);
        final int level = Math.max(Math.max(base.requirements().level(), floor), (int) Math.round(itemLevel * 0.9));
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
        // 詞綴（固定詞綴與詞綴）：飾品打了催化劑的話，那一類的數值乘上品質
        final List<Modifier> affixMods = new ArrayList<>();
        if (base.implicit() != null && !base.implicit().mods().isEmpty()) {
            for (final AffixMod mod : base.implicit().mods()) {
                if (!(base.isWeapon() && LocalMods.isWeaponLocal(mod)) && !mod.isLocal() && !isArmourLocal(base, mod)) { // 本地詞綴已經算進數值（snapshot）
                    affixMods.add(mod.toModifier(data.implicitVars(), source + " 固定詞綴"));
                }
            }
        }
        if (data.identified()) {
            final double unveiledScale = 1 + unveiledMagnitude(repository, data); // INCREASED 的值已經是比例（0.8 = 80%）
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                if (affix == null) {
                    continue;
                }
                for (final AffixMod mod : affix.mods()) {
                    if (mod.isLocal() || base.isWeapon() && LocalMods.isWeaponLocal(mod) || isArmourLocal(base, mod)) {
                        continue; // 本地詞綴已經算進武器、防具的數值（snapshot）
                    }
                    Modifier modifier = mod.toModifier(rolled.vars(), source + " " + affix.id() + " T" + affix.rank(rolled.tier()));
                    if (unveiledScale != 1 && Veiled.isUnveiled(rolled.id())) {
                        modifier = new Modifier(modifier.stat(), modifier.type(), modifier.value() * unveiledScale, modifier.tags(), modifier.source());
                    }
                    affixMods.add(modifier);
                }
            }
        }
        final Catalyst catalyst = base.isJewellery() && data.quality() > 0 ? Catalyst.parse(data.catalyst()) : null;
        for (final Modifier mod : affixMods) {
            list.add(catalyst != null && catalyst.matches(mod) ? catalysed(mod, data.quality()) : mod);
        }
        // 符文（2026-10）
        list.addAll(Runes.modifiers(repository, base, data));
        // 注入的天賦核心（項鍊；催化劑不影響，跟天賦一樣直接加）
        final Instil instil = instilOf(data);
        if (instil != null) {
            list.addAll(instil.modifiers());
        }
        return list;
    }

    /** 權杖的精魂（2026-10，PoE2）：基礎 100，乘上權杖自己的「增加 x% 精魂」（本地，只放大權杖這 100）。不是權杖回傳 0。 */
    public static double sceptreSpirit(final ItemBase base, final List<Modifier> itemModifiers) {
        if (base == null || !base.tags().contains("sceptre")) {
            return 0;
        }
        double increased = 0;
        for (final Modifier modifier : itemModifiers) {
            if (StatKeys.SPIRIT_MAX.equals(modifier.stat()) && modifier.type() == ModType.INCREASED) {
                increased += modifier.value();
            }
        }
        return SCEPTRE_SPIRIT * Math.max(0, 1 + increased);
    }

    /** 權杖的基礎精魂。 */
    public static final double SCEPTRE_SPIRIT = 100;

    /** 這件物品的「增加 x% 已揭露詞綴的幅度」（傳奇詞綴 local.unveiled_magnitude 加總；回傳比例，0.8 = 80%）。 */
    public static double unveiledMagnitude(final ItemRepository repository, final ItemData data) {
        double total = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            for (final AffixMod mod : affix.mods()) {
                if (UNVEILED_MAGNITUDE.equals(mod.stat())) {
                    total += mod.toModifier(rolled.vars(), "").value();
                }
            }
        }
        return total;
    }

    /** 傳奇詞綴：增加這件物品上揭露詞綴的數值（%）。 */
    public static final String UNVEILED_MAGNITUDE = "local.unveiled_magnitude";

    /** 注入在項鍊上的核心（給顯示與屬性表用）：名稱、修飾器、說明行。 */
    public record Instil(String name, List<Modifier> modifiers, List<String> lines) {
        public Instil {
            modifiers = List.copyOf(modifiers);
            lines = List.copyOf(lines);
        }
    }

    /** 查天賦核心的方法（passive 模組啟動時接上；沒接上時注入沒有效果，顯示「已失效」）。 */
    private static volatile java.util.function.Function<String, Instil> instils;

    public static void setInstils(final java.util.function.Function<String, Instil> resolver) {
        instils = resolver;
    }

    /** 這件物品注入的核心；沒注入、找不到那個核心（樹改版）為 null。 */
    public static Instil instilOf(final ItemData data) {
        final java.util.function.Function<String, Instil> resolver = instils;
        if (data.instilled() == null || resolver == null) {
            return null;
        }
        return resolver.apply(data.instilled());
    }

    /** 催化劑：這條詞綴的數值 ×(1 + 品質%)（旗標類的不動）。 */
    static Modifier catalysed(final Modifier mod, final int quality) {
        if (mod.stat().startsWith("flag.")) {
            return mod;
        }
        return new Modifier(mod.stat(), mod.type(), mod.value() * (1 + quality / 100.0), mod.tags(), mod.source() + "（催化 +" + quality + "%）");
    }

    /**
     * 地圖物品的階級：物品上寫的（T1–T16）；舊的地圖沒寫，照基底的 map_tN。不是地圖回傳 0。
     */
    public static int mapTier(final ItemBase base, final ItemData data) {
        if (base == null || !base.isMap()) {
            return 0;
        }
        if (data.mapTier() != null) {
            return Math.max(1, data.mapTier());
        }
        return Math.max(1, base.mapTier());
    }

    /** 物品的顯示名稱（不含顏色）；地圖後面加上階級（「沉船灣 T7」）。 */
    public static String displayName(final ItemRepository repository, final ItemData data) {
        final ItemBase base = repository.base(data.base());
        final String name = name(base, repository, data);
        return base != null && base.isMap() ? name + " T" + mapTier(base, data) : name;
    }

    private static String name(final ItemBase base, final ItemRepository repository, final ItemData data) {
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

    /**
     * 每條詞綴的顯示文字與階級（排名，1 = 這條詞綴最強的階級），依前綴在前、後綴在後排序。
     *
     * @param text   純文字（搜尋、比對用）
     * @param ranged 物品描述用：數值後面接著這一階的範圍（MiniMessage，已跳脫）
     */
    /** 一條詞綴的顯示：catalysed = 這條被飾品的催化劑加強了（數值已經是加成後的）。 */
    public record AffixLine(String text, int tier, AffixType type, String ranged, boolean catalysed, int unveiledBoost,
                            java.util.Set<AffixChips.Chip> chips, String mark) {
        /** 2026-10-21：mark 是 {@link #FRACTURED}（定星石）、{@link #ENCHANT}（蝕變石的腐化詞綴）或 null。 */
        public static final String FRACTURED = "fractured";
        public static final String ENCHANT = "enchant";

        public AffixLine(final String text, final int tier, final AffixType type, final String ranged, final boolean catalysed,
                         final int unveiledBoost, final java.util.Set<AffixChips.Chip> chips) {
            this(text, tier, type, ranged, catalysed, unveiledBoost, chips, null);
        }

        public AffixLine(final String text, final int tier, final AffixType type, final String ranged) {
            this(text, tier, type, ranged, false, 0);
        }

        public AffixLine(final String text, final int tier, final AffixType type, final String ranged, final boolean catalysed) {
            this(text, tier, type, ranged, catalysed, 0);
        }

        public AffixLine(final String text, final int tier, final AffixType type, final String ranged, final boolean catalysed,
                         final int unveiledBoost) {
            this(text, tier, type, ranged, catalysed, unveiledBoost, java.util.Set.of());
        }

        /** 這條是揭露詞綴，被「增加 x% 已揭露詞綴的幅度」加強了（數值已經是加成後的；x 是幾 %）。 */
        public boolean unveiled() {
            return unveiledBoost > 0;
        }
    }

    public static List<AffixLine> affixLines(final ItemRepository repository, final ItemData data) {
        final List<AffixLine> prefixes = new ArrayList<>();
        final List<AffixLine> suffixes = new ArrayList<>();
        // 2026-10：飾品的催化劑——受影響的詞綴直接顯示加成後的數值（PoE 的做法），跟屬性表算的一樣
        final ItemBase base = repository.base(data.base());
        final Catalyst catalyst = base != null && base.isJewellery() && data.quality() > 0 ? Catalyst.parse(data.catalyst()) : null;
        final double factor = 1 + data.quality() / 100.0;
        // 2026-10：「增加 x% 已揭露詞綴的幅度」（骷印長杖）——揭露詞綴也直接顯示加成後的數值，跟屬性表算的一樣
        final double unveiled = data.identified() ? unveiledMagnitude(repository, data) : 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            final boolean boosted = catalyst != null && catalysed(affix, rolled.vars(), catalyst);
            final boolean veiledBoost = unveiled > 1e-9 && Veiled.isUnveiled(rolled.id());
            final double scale = (boosted ? factor : 1) * (veiledBoost ? 1 + unveiled : 1);
            final Map<String, Integer> vars = scale != 1 ? scaled(rolled.vars(), scale) : rolled.vars();
            final Map<String, int[]> ranges = scale != 1 ? scaledRanges(affix.ranges(rolled.tier()), scale) : affix.ranges(rolled.tier());
            final AffixLine line = new AffixLine(affix.render(vars), affix.rank(rolled.tier()), affix.type(),
                    Affix.substituteWithRanges(affix.text(), vars, ranges), boosted, veiledBoost ? (int) Math.round(unveiled * 100) : 0,
                    repository.chipsOf(affix), // 2026-10：詞綴標籤（資料檔寫了 chips 的照寫的）
                    affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG) ? AffixLine.ENCHANT
                            : data.isFractured(rolled) ? AffixLine.FRACTURED : null); // 2026-10-21
            if (affix.type() == AffixType.PREFIX) {
                prefixes.add(line);
            } else {
                suffixes.add(line);
            }
        }
        prefixes.addAll(suffixes);
        return prefixes;
    }

    /** 這條詞綴有沒有修飾器吃這種催化劑（跟 {@link #catalysed(Modifier, int)} 的判斷一樣）。 */
    private static boolean catalysed(final Affix affix, final Map<String, Integer> vars, final Catalyst catalyst) {
        for (final AffixMod mod : affix.mods()) {
            if (catalyst.matches(mod.toModifier(vars, "")) && !mod.stat().startsWith("flag.")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> scaled(final Map<String, Integer> vars, final double factor) {
        final Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Integer> entry : vars.entrySet()) {
            out.put(entry.getKey(), (int) Math.round(entry.getValue() * factor));
        }
        return out;
    }

    private static Map<String, int[]> scaledRanges(final Map<String, int[]> ranges, final double factor) {
        final Map<String, int[]> out = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : ranges.entrySet()) {
            final int[] range = entry.getValue();
            out.put(entry.getKey(), range == null || range.length != 2 ? range
                    : new int[] {(int) Math.round(range[0] * factor), (int) Math.round(range[1] * factor)});
        }
        return out;
    }
}
