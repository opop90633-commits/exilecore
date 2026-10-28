package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 主要寶石的執行期（2026-10，照 PoE2 的 Meta Gem；設定見 {@link MetaSpec}）。
 * <ul>
 *   <li>插槽（2026-10-22）：法術的技能寶石放進主要寶石自己的孔（{@link SkillLoadout.Entry#spells}），其他孔的輔助都套在那些法術上
 *       （跟英靈圖騰放錘子技能一樣的做法）。以前是「面板上它後面那一格」。2026-10-27 照 PoE2：星咒圖騰也可以放好幾個（這次之前只用第 1 孔）。</li>
 *   <li>能量：常駐的主要寶石（暴擊時施放這類）開著（綁在按鍵格、精魂付得起）時，條件成立就累積能量；
 *       到上限（插槽法術的基礎施法時間每 0.1 秒 10 點，全部加起來）就觸發所有插槽法術、失去全部能量
 *       （2026-10-26 照 PoE2「Triggers all Socketed Spells and loses all Energy on reaching maximum Energy」；
 *       這次之前只觸發第 1 孔、多的能量留著（最多 99%）、兩次觸發至少隔 4 tick）。能量不存檔（登出、換角色歸零）。</li>
 *   <li>2026-10-26 照 PoE2：常駐的主要寶石第 2～5 孔也能放法術（{@link SkillLoadout.Entry#spells}），全部一起觸發；輔助套在每一個上。</li>
 *   <li>2026-10-26 照 PoE2 Invocation（祈喚：元祈、刈祈、障祈引星、咒擲）：能量存到 max_energy，按下去才花掉觸發（{@link #invoke}）。</li>
 *   <li>被觸發的法術不花魔力；它打出來的暴擊不會再幫暴擊時施放充能（避免自己觸發自己）。</li>
 * </ul>
 */
public final class MetaGems {

    /** 被主要寶石觸發的法術，技能描述的代號後面接這個（打中時認得出來：不再充能、傷害統計分開）。 */
    public static final String TRIGGERED_SUFFIX = ".meta";
    /**
     * 祈喚（Invocation）兩次按下至少隔幾 tick（PoE2 的冷卻 0.2 秒）。2026-10-26：自動觸發的不再用這個間隔
     * （照 PoE2 滿了就觸發、失去全部能量；這次之前兩次觸發至少隔 4 tick）。
     */
    static final long MIN_GAP = 4;

    /**
     * 插槽：主要寶石在面板的第幾格、孔裡的法術（2026-10-26 照 PoE2：可以好幾個，照孔的順序；
     * 2026-10-27 照 PoE2 Spell Totem「uses socketed Spells」：星咒圖騰也是，圖騰輪流放；這次之前星咒圖騰只用第 1 孔）。
     */
    record Socket(int metaPosition, SkillLoadout.Entry metaEntry, MetaSpec spec, List<SkillLoadout.Entry> spellEntries, List<SkillDefinition> spells) {

        Socket(final int metaPosition, final SkillLoadout.Entry metaEntry, final MetaSpec spec, final SkillLoadout.Entry spellEntry, final SkillDefinition spell) {
            this(metaPosition, metaEntry, spec, List.of(spellEntry), List.of(spell));
        }

        /** 第一個插槽法術（說明用）。 */
        SkillLoadout.Entry spellEntry() {
            return spellEntries.getFirst();
        }

        SkillDefinition spell() {
            return spells.getFirst();
        }

        /** 2026-10-26 照 PoE2（generic_ongoing_trigger_maximum_energy_is_total_of_socketed_skills）：能量上限＝每個插槽法術的上限加起來。 */
        double maxEnergy() {
            return totalMaxEnergy(spells);
        }

        /** 能量記在「主要寶石:插槽法術」上（好幾個用 + 連起來）：換了插槽法術就從 0 開始。 */
        String key() {
            final StringBuilder text = new StringBuilder(metaEntry.skillId()).append(':');
            for (int i = 0; i < spells.size(); i++) {
                text.append(i == 0 ? "" : "+").append(spells.get(i).id());
            }
            return text.toString();
        }

        /** 插槽法術的名字（、連起來）。 */
        String names() {
            final List<String> names = new ArrayList<>();
            for (final SkillDefinition spell : spells) {
                names.add(spell.name());
            }
            return String.join("、", names);
        }

        /** 有沒有哪個插槽法術有冷卻。 */
        boolean anyCooldown() {
            for (final SkillDefinition spell : spells) {
                if (spell.cooldown() > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 2026-10-26：好幾個插槽法術的能量上限（各自的基礎施法時間每 0.1 秒 10 點，加起來）。 */
    static double totalMaxEnergy(final List<SkillDefinition> spells) {
        double total = 0;
        for (final SkillDefinition spell : spells) {
            total += MetaSpec.maxEnergy(spell);
        }
        return total;
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Map<String, Double>> energy = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastFired = new HashMap<>();

    MetaGems(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // ---- 插槽 ----

    /** 面板第 position 格的主要寶石的插槽；那一格不是主要寶石、或下一格的法術不合格，回傳 null。 */
    Socket socket(final PlayerState state, final int position) {
        final SkillLoadout loadout = state.skills();
        final SkillLoadout.Entry metaEntry = loadout.skill(position);
        final MetaSpec spec = metaEntry == null ? null : service.config().meta(metaEntry.skillId());
        if (spec == null) {
            return null;
        }
        // 2026-10-26 照 PoE2：孔裡所有的法術（不合格的略過）；2026-10-27 星咒圖騰也是（這次之前只用第 1 孔）
        final List<SkillLoadout.Entry> entries = new ArrayList<>();
        final List<SkillDefinition> spells = new ArrayList<>();
        // 2026-10-27 照 PoE2：屬性需求不足的插槽法術也略過（這次之前只要有一個夠用，不足的也一起觸發）
        for (final SkillLoadout.Entry spellEntry : GemAttributes.usable(service.config(), SkillService.innerEntries(metaEntry), state.sheet())) {
            final SkillDefinition spell = service.config().skill(spellEntry.skillId());
            if (spell != null && problem(spec, spell) == null) {
                entries.add(spellEntry);
                spells.add(spell);
            }
        }
        return spells.isEmpty() ? null : new Socket(position, metaEntry, spec, List.copyOf(entries), List.copyOf(spells));
    }

    /** 這個主要寶石（面板第 position 格）的插槽有什麼問題；沒問題回傳 null（說明、按下去時的提示用）。 */
    String socketProblem(final PlayerState state, final int position) {
        final SkillLoadout loadout = state.skills();
        final SkillLoadout.Entry metaEntry = loadout.skill(position);
        final MetaSpec spec = metaEntry == null ? null : service.config().meta(metaEntry.skillId());
        if (spec == null) {
            return null;
        }
        final SkillDefinition metaDefinition = service.config().skill(metaEntry.skillId());
        final String name = metaDefinition == null ? metaEntry.skillId() : metaDefinition.name();
        final List<SkillLoadout.Entry> entries = SkillService.innerEntries(metaEntry); // 2026-10-26：每一孔的法術都看（2026-10-27 星咒圖騰也是）
        if (entries.isEmpty()) {
            return name + "：孔裡要放法術的技能寶石（/skills 點它，拿技能寶石點一個孔；可以放好幾個）";
        }
        for (final SkillLoadout.Entry spellEntry : entries) {
            final SkillDefinition spell = service.config().skill(spellEntry.skillId());
            if (spell == null) {
                return name + "：插槽裡的" + spellEntry.skillId() + "不認得";
            }
            final String why = problem(spec, spell);
            if (why != null) {
                return name + "：插槽裡的" + spell.name() + why;
            }
        }
        if (GemAttributes.usable(service.config(), entries, state.sheet()).isEmpty()) { // 2026-10-27：插槽法術全都屬性不足
            final String why = GemAttributes.problem(service.config(), entries.get(0), state.sheet());
            final SkillDefinition spell = service.config().skill(entries.get(0).skillId());
            return name + "：插槽裡的" + (spell == null ? entries.get(0).skillId() : spell.name()) + (why == null ? "屬性不足" : why);
        }
        return null;
    }

    /** 2026-10-28：武器、昇華賦予的技能不能放進宿主寶石孔的原因（接在技能名字後面）。 */
    static final String GRANTED_ONLY = "是武器／昇華賦予的技能（不是寶石），不能插進主要寶石";

    /** 這個法術能不能插進這種主要寶石；可以回傳 null，不行回傳原因（接在法術名字後面）。 */
    static String problem(final MetaSpec spec, final SkillDefinition spell) {
        final String why = castProblem(spec, spell);
        if (why == null && spell.grantedOnly()) {
            return GRANTED_ONLY; // 2026-10-28 照 PoE2：武器、昇華賦予的技能不是寶石，不能插進主要寶石（星咒圖騰、引星…都一樣）
        }
        return why;
    }

    /**
     * 2026-10-28：只看機制（能不能被觸發／由圖騰放）；不管是不是寶石。SkillService.metaCast 的保險用
     * （命運同調、預告射擊免費放面板上的法術，武器賦予的也可以）。
     */
    static String castProblem(final MetaSpec spec, final SkillDefinition spell) {
        if (spell.hidden() || spell.type() != SkillType.SPELL || !spell.tags().contains(tw.exilecore.stats.Tags.SPELL)) {
            return "不是法術（只能插法術）";
        }
        // 2026-10-28 照 PoE2：先看我們的引擎放不放得出來（engineProblem），再照原作輔助那一面的技能類型（MetaSocketRules、TotemRules）；
        //   這次之前一律照機制擋（常駐、召喚、戰吼、位移、增益、主要寶石不行），雷遁（原作 Triggerable）插不進暴擊引星
        final String engine = engineProblem(spell);
        if (engine != null) {
            return engine;
        }
        if (spell.tags().contains("command")) {
            return "不能插（指令召喚物的技能要有召喚物才能放）"; // 2026-10-17：以前觸發時沒有召喚物也從自己身上放出來
        }
        if (spec.totem()) {
            // 2026-10-28 照 PoE2 Spell Totem（Spell 而且 Totemable，不能有冷卻）：雷遁、骸爆、詛咒、縛根也交給圖騰（TotemRules.spellTotemCastable）；
            //   這次之前位移、印記、屍體一律擋（我們的圖騰放不出來）
            if (spell.cooldown() > 0 || !TotemRules.spellTotemCastable(spell) || spell.deploy() != null) {
                return "不能交給法術圖騰放（有冷卻、本來就是圖騰、一般位移、印記的不行）";
            }
            return TotemRules.spellTotemProblem(spell); // 2026-10-27 照 PoE2 Spell Totem：原作不能由圖騰施放的法術（穢陣、骨綻、雷流場）也擋掉
        }
        if (!MetaSocketRules.triggerable(spell)) {
            // 2026-10-28 照 PoE2（allowed_types Spell + Triggerable）：引導、印記、本來就放圖騰的、原作不能被觸發的；這次之前這些都插得進去
            return "不能插（這個法術不能被觸發：引導、印記、放圖騰的…不行）";
        }
        if (spec.kind() == MetaSpec.Kind.MINION_DEATH && MetaSocketRules.createsMinion(spell)) {
            return "不能插（殞從引星不能放會產生召喚物的法術）"; // 2026-10-28 照 PoE2 excluded_types CreatesMinion；這次之前可以
        }
        if (spec.kind() == MetaSpec.Kind.MELEE_HIT && !MetaSocketRules.fire(spell)) {
            return "只能插火焰法術"; // 2026-10-26（昇華・擊中時火焰法術）；2026-10-28 照 PoE2 Fire 類型：元素要害也算（這次之前只看 fire 標籤）
        }
        return null;
    }

    /**
     * 2026-10-28：被觸發的路徑（{@link SkillService#metaCast} → SkillExecutor.execute）放不出來的機制；可以回傳 null。
     * 跟原作類型無關，是我們的引擎做不到（或放出來不安全）的：
     * <ul>
     *   <li>常駐（光環、精魂召喚物）、召喚、戰吼、集結、褻瀆、主要寶石、增益（guard：跟著你的增益，力印、聖地這些）：
     *       都是 SkillService 自己的施放路徑，execute 不認得（會丟例外）。原作的力印、聖地、噬屍宴是 Triggerable，但都是武器賦予的，本來就插不進來。</li>
     *   <li>跳躍、瞬影：要鎖住玩家滯空／衝到目標身邊的流程（readyAt、滯空 tick），被觸發時沒有人接，原作也沒有能被觸發的。</li>
     *   <li>一般的衝刺（閃現、鏡影穿霧）：被觸發時會把你往準心方向拉走；原作的閃現不是 Triggerable、鏡影穿霧原作沒有。
     *       雷遁（PoE2 Triggerable）例外：被觸發時對觸發的目標（撲殺得到就瞬移進去，不然掛標記）。</li>
     * </ul>
     * metaCast 的保險只看這個（命運同調、預告射擊免費放面板上的法術也走 metaCast）。這次之前保險用暴擊引星的整套規則（含位移一律不行）。
     */
    static String engineProblem(final SkillDefinition spell) {
        return switch (spell.mechanic()) {
            case AURA -> "不能插（常駐技能不能被觸發）";
            case SUMMON -> "不能插（召喚技能不能被觸發）";
            case WARCRY, RALLY -> "不能插（戰吼不能被觸發）";
            case GUARD -> "不能插（增益技能不能被觸發）";
            case DESECRATE -> "不能插（褻瀆不能被觸發）";
            case META -> "不能插（主要寶石不能插主要寶石）";
            case LEAP, FLICKER -> "不能插（跳躍、瞬影類的位移不能被觸發）";
            case DASH -> SkillExecutor.hasFlag(spell, tw.exilecore.stats.StatKeys.FLAG_LIGHTNING_WARP) ? null
                    : "不能插（衝刺類的位移不能被觸發；雷遁可以）";
            default -> spell.tags().contains("persistent") ? "不能插（常駐技能不能被觸發）" : null;
        };
    }

    // ---- 能量 ----

    /** 怪物強度（照 PoE2 的 power）：一般 1、魔法 2、稀有 5、傳奇 20（2026-10-26 照 poe2db Monster Power；這次之前 10）。不是怪物回傳 1。 */
    double power(final LivingEntity target) {
        if (target == null || plugin.monsters() == null) {
            return 1;
        }
        return plugin.monsters().data(target).map(data -> power(data.rarity())).orElse(1.0);
    }

    /** 2026-10-26：算不出異常門檻時（不是怪物…）暴擊當作打掉幾 %（＝這次之前固定的每點強度 10 能量）。 */
    public static final double CRIT_PERCENT_FALLBACK = 10;

    /**
     * 2026-10-26 照 PoE2 Cast on Critical：暴擊打掉牠元素異常門檻的百分比（每點強度每 1% 給 1 能量；最多算 100%，
     * 打超過的不再多給）。怪物的門檻跟元素異常一樣：等級表 × 稀有度倍率（傳奇 0.5）。
     */
    double critPercent(final LivingEntity target, final double damage) {
        if (target == null || plugin.monsters() == null || plugin.ailments() == null) {
            return CRIT_PERCENT_FALLBACK;
        }
        return plugin.monsters().data(target).map(data -> critPercent(damage, plugin.monsters().config().ailmentThresholdAt(Math.max(1, data.level()))
                * plugin.ailments().config().monsterThreshold(data.rarity()))).orElse(CRIT_PERCENT_FALLBACK);
    }

    static double critPercent(final double damage, final double threshold) {
        if (threshold <= 0) {
            return CRIT_PERCENT_FALLBACK;
        }
        return Math.max(0, Math.min(100, damage / threshold * 100));
    }

    static double power(final MonsterRarity rarity) {
        return switch (rarity) {
            case NORMAL -> 1;
            case MAGIC -> 2;
            case RARE -> 5;
            case UNIQUE -> 20;
        };
    }

    /**
     * 條件成立：這種的主要寶石（開著的）各得到 base × 怪物強度…的能量，滿了就觸發插槽法術。
     *
     * @param multiplier 照種類乘上去的倍數（怪物強度、冰凍 ×3…）
     */
    void gain(final Player player, final MetaSpec.Kind kind, final double multiplier, final LivingEntity target) {
        if (!service.eligible(player) || service.inTown(player)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout loadout = state.skills();
        final long now = plugin.getServer().getCurrentTick();
        prune(player.getUniqueId(), loadout); // 2026-10-27：拿掉的主要寶石的能量也清掉（這次之前一直留著）
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = loadout.skill(i);
            final MetaSpec spec = entry == null ? null : service.config().meta(entry.skillId());
            if (spec == null || spec.kind() != kind || !service.auras().isActive(player.getUniqueId(), entry.skillId())) {
                continue; // 主要寶石要開著（綁在按鍵格、精魂付得起）才會充能
            }
            final Socket socket = socket(state, i);
            if (socket == null) {
                warnProblem(player, state, i, now); // 2026-10：插槽放錯（不是法術、增益技能…）以前完全沒反應，看不出為什麼不觸發
                continue;
            }
            final SkillDefinition cadenceCheck = service.config().skill(entry.skillId());
            if (cadenceCheck != null && cadenceCheck.tags().contains(tw.exilecore.stats.TreeMechanicKeys.INVOCATION)
                    && tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneKeys.FLAG_RITUAL_CADENCE)) {
                continue; // 2026-10-26 照 PoE2 基石：祭祀終章——祈喚技能不會獲得能量（改成每 2 秒觸發，見 cadence）
            }
            final int level = usableLevel(state, entry);
            final double[] mods = energyModifiers(entry); // 2026-10-25：掛在引星上的能量輔助
            final SkillDefinition metaDefinition = service.config().skill(entry.skillId());
            final java.util.Set<String> metaTags = metaDefinition == null ? java.util.Set.of() : metaDefinition.tags();
            // 2026-10-26 照 PoE2：天賦的「主要寶石獲得的能量」「Invocation 技能的最大能量」「觸發技能退還一半能量的機率」（以前只看輔助）
            final double[] own = sheetEnergy(plugin.players().sheet(player), metaTags);
            final double gained = spec.energy() * multiplier * (1 + spec.energyMore().at(level) / 100.0) * (1 + mods[0] / 100.0) * own[0];
            final Map<String, Double> mine = energy.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
            // 能量記在「主要寶石:插槽法術」上：換了插槽法術就從 0 開始
            final String key = socket.key();
            mine.keySet().removeIf(k -> k.startsWith(entry.skillId() + ":") && !k.equals(key));
            if (spec.invocation()) {
                // 2026-10-26 照 PoE2 Invocation：存到上限（500，擴能、天賦加大）為止，不自動觸發；按下去才花（invoke）
                mine.put(key, Math.min(mine.getOrDefault(key, 0.0) + gained, invocationMax(spec, mods, own,
                        service.qualityFlat(player, entry.skillId(), tw.exilecore.stats.GemQualityKeys.META_MAX_ENERGY))));
                continue;
            }
            final double max = socket.maxEnergy() * (1 + mods[1] / 100.0) * own[1];
            final double raw = mine.getOrDefault(key, 0.0) + gained;
            final double refundChance = mods[2] + own[2];
            mine.put(key, afterGain(raw, max, refundChance > 0 && plugin.items().random().nextDouble() * 100 < refundChance));
            if (raw >= max - 1e-9) {
                lastFired.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>()).put(entry.skillId(), now);
                fireAll(player, socket, target != null && target.isValid() && !target.isDead() ? target : null,
                        metaDefinition == null ? "主要寶石" : metaDefinition.name(), metaTags);
            }
        }
    }

    /**
     * 2026-10-26 照 PoE2「Triggers all Socketed Spells and loses all Energy on reaching maximum Energy」：加完能量後剩多少。
     * 到上限就觸發、失去全部能量（返能中了退還上限的一半）；沒到就照存（這次之前多出來的留著，最多 99%）。
     */
    static double afterGain(final double raw, final double max, final boolean refunded) {
        if (raw >= max - 1e-9) {
            return refunded ? max / 2 : 0;
        }
        return Math.max(0, raw);
    }

    /** 2026-10-26 照 PoE2：觸發所有插槽法術（照孔的順序，打同一個目標）。 */
    private void fireAll(final Player player, final Socket socket, final LivingEntity target, final String source, final java.util.Set<String> metaTags) {
        final double scale = (1 - socket.spec().lessDamage() / 100.0) * (1 + socket.spec().moreDamage() / 100.0);
        for (int i = 0; i < socket.spells().size(); i++) {
            service.metaCast(player, socket.metaEntry(), socket.spells().get(i), socket.spellEntries().get(i), target, scale, source, metaTags);
        }
    }

    /** 2026-10-26：Invocation 的能量上限（寶石的 max_energy × 擴能 × 天賦的「Invocation 技能的最大能量」）。 */
    static double invocationMax(final MetaSpec spec, final double[] mods, final double[] own) {
        return invocationMax(spec, mods, own, 0);
    }

    /** 2026-10-28 照 PoE2 Elemental Invocation 的品質：最大能量 +(0—100)（固定值，加在 500 上，再乘擴能與天賦）。 */
    static double invocationMax(final MetaSpec spec, final double[] mods, final double[] own, final double flat) {
        return (spec.maxEnergy() + Math.max(0, flat)) * (1 + mods[1] / 100.0) * own[1];
    }

    /** 2026-10-26：按一次祈喚最多觸發幾次（能量剩的留著；原作沒有上限，這裡防一次排太多發）。 */
    static final int MAX_INVOKES = 20;
    /** 2026-10-26：同一次祈喚連續觸發的間隔（tick）。 */
    static final long INVOKE_GAP = 2;

    /** 2026-10-26：一次祈喚能觸發幾次：存的能量 ÷ 插槽法術的上限（無條件捨去）；有冷卻的法術一次只放一發。 */
    static int invokeTimes(final double stored, final double cost, final boolean cooldown) {
        if (cost <= 0) {
            return 0;
        }
        final int times = Math.min(MAX_INVOKES, (int) Math.floor((stored + 1e-9) / cost));
        return cooldown ? Math.min(1, times) : times;
    }

    /**
     * 2026-10-26 照 PoE2 Invocation（祈喚）：按下祈喚類主要寶石的按鍵——把存著的能量花掉，觸發插槽法術
     * （每次花插槽法術的上限那麼多，夠幾次就連續觸發幾次；返能每次觸發有機率退還一半）。冷卻 0.2 秒。
     *
     * @return 給玩家看的提示；不是祈喚類回傳 null
     */
    String invoke(final Player player, final int position, final LivingEntity preferred) {
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout.Entry entry = position < 0 ? null : state.skills().skill(position);
        final MetaSpec spec = entry == null ? null : service.config().meta(entry.skillId());
        if (spec == null || !spec.invocation()) {
            return null;
        }
        if (!service.eligible(player) || service.inTown(player)) {
            return null; // 營地裡不放
        }
        final SkillDefinition metaDefinition = service.config().skill(entry.skillId());
        final String name = ItemRenderer.escape(metaDefinition == null ? entry.skillId() : metaDefinition.name());
        final String problem = socketProblem(state, position);
        if (problem != null) {
            return "<red>" + ItemRenderer.escape(problem);
        }
        final Socket socket = socket(state, position);
        if (socket == null) {
            return null;
        }
        final java.util.Set<String> metaTags = metaDefinition == null ? java.util.Set.of() : metaDefinition.tags();
        if (metaTags.contains(tw.exilecore.stats.TreeMechanicKeys.INVOCATION)
                && tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneKeys.FLAG_RITUAL_CADENCE)) {
            return "<gray>" + name + "：祭祀終章——每 2 秒自動觸發插槽法術，不用按";
        }
        final long now = plugin.getServer().getCurrentTick();
        final Map<String, Long> fired = lastFired.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        if (now - fired.getOrDefault(entry.skillId(), Long.MIN_VALUE / 2) < MIN_GAP) {
            return null; // 冷卻 0.2 秒
        }
        // 2026-10-26 照 PoE2：每次觸發所有插槽法術，花它們的上限加起來（每個基礎施法時間每 0.1 秒 10 點）
        final double cost = socket.maxEnergy();
        final Map<String, Double> mine = energy.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        final String key = socket.key();
        final double stored = mine.getOrDefault(key, 0.0);
        final int times = invokeTimes(stored, cost, socket.anyCooldown());
        if (times <= 0) {
            return String.format(Locale.ROOT, "<gray>%s：能量 <white>%s</white>，觸發一次%s要 <white>%s</white>", name,
                    number(Math.floor(stored)), ItemRenderer.escape(socket.names()), number(cost));
        }
        final double[] mods = energyModifiers(entry);
        final double[] own = sheetEnergy(plugin.players().sheet(player), metaTags);
        final double refundChance = mods[2] + own[2];
        double left = stored - times * cost;
        for (int i = 0; i < times; i++) {
            if (refundChance > 0 && plugin.items().random().nextDouble() * 100 < refundChance) {
                left += cost / 2; // 返能：退還這一次花的一半
            }
        }
        mine.put(key, Math.max(0, Math.min(left, invocationMax(spec, mods, own, service.qualityFlat(player, entry.skillId(), tw.exilecore.stats.GemQualityKeys.META_MAX_ENERGY)))));
        fired.put(entry.skillId(), now);
        final String source = metaDefinition == null ? "主要寶石" : metaDefinition.name();
        final LivingEntity first = preferred != null && preferred.isValid() && !preferred.isDead() ? preferred : nearestEnemy(player);
        fireAll(player, socket, first, source, metaTags);
        for (int i = 1; i < times; i++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead() || !service.auras().isActive(player.getUniqueId(), entry.skillId())) {
                    return;
                }
                final LivingEntity target = first != null && first.isValid() && !first.isDead() ? first : nearestEnemy(player);
                fireAll(player, socket, target, source, metaTags);
            }, i * INVOKE_GAP);
        }
        return String.format(Locale.ROOT, "<#c9a7ff>✦ %s：觸發%s <white>%d</white> 次（剩 %s 能量）", name,
                ItemRenderer.escape(socket.names()), times, number(Math.floor(mine.get(key))));
    }

    /** 12 格內最近的敵人（祈喚、祭祀終章找目標用）；沒有回傳 null。 */
    private LivingEntity nearestEnemy(final Player player) {
        LivingEntity target = null;
        double best = 12 * 12;
        for (final org.bukkit.entity.Entity near : player.getNearbyEntities(12, 6, 12)) {
            if (near instanceof LivingEntity monster && monster.isValid() && !monster.isDead() && plugin.monsters() != null
                    && plugin.monsters().data(monster).isPresent()) {
                final double distance = near.getLocation().distanceSquared(player.getLocation());
                if (distance < best) {
                    best = distance;
                    target = monster;
                }
            }
        }
        return target;
    }

    /** 2026-10-26 祭祀終章：每幾 tick 觸發一次（PoE2：每 2 秒）。 */
    static final long CADENCE_TICKS = 40;

    /**
     * 2026-10-26 照 PoE2 基石：祭祀終章（Ritual Cadence）——開著的祈喚技能（Invocation）改成每 2 秒觸發插槽法術
     * （不看能量；照原作的「Invocated Spells 消耗的能量更少 50%」在這裡沒有意義：觸發不用能量）。目標是 12 格內最近的敵人。
     */
    void cadence(final Player player, final long now) {
        if (now % 10 != 0 || !tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneKeys.FLAG_RITUAL_CADENCE)
                || !service.eligible(player) || service.inTown(player)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout loadout = state.skills();
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = loadout.skill(i);
            final MetaSpec spec = entry == null ? null : service.config().meta(entry.skillId());
            final SkillDefinition metaDefinition = entry == null ? null : service.config().skill(entry.skillId());
            if (spec == null || metaDefinition == null || !metaDefinition.tags().contains(tw.exilecore.stats.TreeMechanicKeys.INVOCATION)
                    || !service.auras().isActive(player.getUniqueId(), entry.skillId())) {
                continue;
            }
            final Socket socket = socket(state, i);
            if (socket == null) {
                continue;
            }
            final Map<String, Long> fired = lastFired.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
            if (now - fired.getOrDefault(entry.skillId(), Long.MIN_VALUE / 2) < CADENCE_TICKS) {
                continue;
            }
            fired.put(entry.skillId(), now);
            LivingEntity target = null;
            double best = 12 * 12;
            for (final org.bukkit.entity.Entity near : player.getNearbyEntities(12, 6, 12)) {
                if (near instanceof LivingEntity monster && monster.isValid() && !monster.isDead() && plugin.monsters() != null
                        && plugin.monsters().data(monster).isPresent()) {
                    final double distance = near.getLocation().distanceSquared(player.getLocation());
                    if (distance < best) {
                        best = distance;
                        target = monster;
                    }
                }
            }
            if (target == null) {
                continue; // 附近沒有敵人就不放（不浪費）
            }
            fireAll(player, socket, target, metaDefinition.name(), metaDefinition.tags()); // 2026-10-26：所有插槽法術
        }
    }

    /**
     * 2026-10-26：玩家屬性表上給主要寶石能量的（天賦、裝備）：{能量獲得倍率, 能量上限倍率, 退還一半的機率 %}；
     * 用主要寶石的標籤查（「Invocation 技能的最大能量」只給 invocation 的）。
     */
    static double[] sheetEnergy(final tw.exilecore.stats.StatSheet sheet, final java.util.Set<String> metaTags) {
        if (sheet == null) {
            return new double[] {1, 1, 0};
        }
        return new double[] {
            Math.max(0, 1 + sheet.increased(tw.exilecore.stats.StatKeys.META_ENERGY_GAIN, metaTags)) * sheet.more(tw.exilecore.stats.StatKeys.META_ENERGY_GAIN, metaTags),
            Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.StatKeys.META_ENERGY_MAX, metaTags)) * sheet.more(tw.exilecore.stats.StatKeys.META_ENERGY_MAX, metaTags),
            sheet.flat(tw.exilecore.stats.StatKeys.META_ENERGY_REFUND, metaTags)};
    }

    /** 2026-10-25：掛在這顆引星上的輔助給的 {能量獲得 %, 能量上限 %, 退還機率 %}（共鳴的 ~ 去掉；孔裡的法術 @ 跳過）。 */
    double[] energyModifiers(final SkillLoadout.Entry entry) {
        final double[] sum = new double[3];
        // 2026-10-28 照 PoE2：引星、咒擲自己的品質（Energy gained、gem_quality.yml）也算；這次之前品質照通用規則加光環效果，對能量沒有作用
        final java.util.List<tw.exilecore.stats.Modifier> quality = tw.exilecore.currency.GemQuality.modifiers(entry.skillId(), entry.quality(), "品質");
        if (quality != null) {
            addEnergy(sum, quality);
        }
        for (String id : entry.supportIds()) {
            if (id.startsWith(SkillLoadout.Entry.INNER_PREFIX)) {
                continue;
            }
            if (id.endsWith(SkillLoadout.Entry.RESONANT_SUFFIX)) {
                id = id.substring(0, id.length() - 1);
            }
            final SupportDefinition support = service.config().support(id);
            if (support == null) {
                continue;
            }
            addEnergy(sum, support.modifiers());
        }
        return sum;
    }

    /** 能量的修飾器加到 {能量獲得 %, 能量上限 %, 退還機率 %}。 */
    static void addEnergy(final double[] sum, final java.util.List<tw.exilecore.stats.Modifier> modifiers) {
        for (final tw.exilecore.stats.Modifier modifier : modifiers) {
            switch (modifier.stat()) {
                case tw.exilecore.stats.StatKeys.META_ENERGY_GAIN -> sum[0] += modifier.value() * (modifier.type() == tw.exilecore.stats.ModType.INCREASED ? 100 : 1);
                case tw.exilecore.stats.StatKeys.META_ENERGY_MAX -> sum[1] += modifier.value() * (modifier.type() == tw.exilecore.stats.ModType.INCREASED ? 100 : 1);
                case tw.exilecore.stats.StatKeys.META_ENERGY_REFUND -> sum[2] += modifier.value();
                default -> { }
            }
        }
    }

    /** 主要寶石實際用幾級算能量（2026-10-17：Lv.21 寶石照 21 級算，跟 SkillBuild 一樣：角色到 20 級寶石的需求等級就能用 21 級）。 */
    int usableLevel(final PlayerState state, final SkillLoadout.Entry entry) {
        final int usable = entry.level() > SkillLoadout.MAX_LEVEL
                && state.level() >= service.config().requiredLevel(SkillLoadout.MAX_LEVEL)
                ? Math.min(entry.level(), SkillLoadout.GEM_CAP) : service.config().maxLevelFor(state.level());
        // 2026-10-27 照站長：屬性不夠時也照自動降到的等級算能量（跟 SkillBuild 一樣；這次之前只看角色等級）
        final int attributes = GemAttributes.usableLevel(service.config(), entry, state.sheet());
        return Math.max(1, Math.min(Math.min(entry.level(), usable), attributes));
    }

    /** 上次提醒插槽有問題的時間（每 15 秒最多提醒一次）。 */
    private final Map<UUID, Long> warned = new HashMap<>();

    private void warnProblem(final Player player, final PlayerState state, final int position, final long now) {
        final String problem = socketProblem(state, position);
        if (problem == null || now - warned.getOrDefault(player.getUniqueId(), Long.MIN_VALUE / 2) < 300) {
            return;
        }
        warned.put(player.getUniqueId(), now);
        state.notice("<red>" + ItemRenderer.escape(problem), now + 60);
        plugin.players().display(player, state);
    }

    /**
     * 2026-10-28 照站長：畫面上方資源條的主要寶石能量（「擊中時火焰術 12/40」；圖騰不算）。
     * 開著的（常駐）或祈喚（按下去才花能量）的才寫；沒有回傳空字串。
     */
    String hudText(final Player player, final PlayerState state) {
        final SkillLoadout loadout = state.skills();
        final List<String> parts = new ArrayList<>();
        for (int position = 0; position < loadout.size(); position++) {
            final Socket socket = socket(state, position);
            if (socket == null || socket.spec().totem()) {
                continue;
            }
            final SkillDefinition meta = service.config().skill(socket.metaEntry().skillId());
            if (meta == null) {
                continue;
            }
            final double stored = energyOf(player.getUniqueId(), socket);
            final boolean invocation = socket.spec().invocation();
            if (!invocation && !service.auras().isActive(player.getUniqueId(), meta.id()) && stored <= 0) {
                continue;
            }
            final double max = invocation
                    ? invocationMax(socket.spec(), energyModifiers(socket.metaEntry()), sheetEnergy(plugin.players().sheet(player), meta.tags()),
                    service.qualityFlat(player, socket.metaEntry().skillId(), tw.exilecore.stats.GemQualityKeys.META_MAX_ENERGY))
                    : socket.maxEnergy();
            if (max <= 0) {
                continue;
            }
            final boolean full = invocation ? stored + 1e-9 >= socket.maxEnergy() : stored + 1e-9 >= max;
            parts.add("<#c9a7ff>" + ItemRenderer.escape(meta.name()) + " " + (full ? "<#ffd060>" : "<white>")
                    + number(Math.floor(stored)) + "<gray>/" + number(max));
        }
        return String.join("  ", parts);
    }

    /** 這顆主要寶石現在累積的能量（說明用）。 */
    double energyOf(final UUID player, final String metaId, final String spellId) {
        final Map<String, Double> mine = energy.get(player);
        return mine == null ? 0 : mine.getOrDefault(metaId + ":" + spellId, 0.0);
    }

    /** 2026-10-26：同上，照插槽（好幾個法術）。 */
    double energyOf(final UUID player, final Socket socket) {
        final Map<String, Double> mine = energy.get(player);
        return mine == null ? 0 : mine.getOrDefault(socket.key(), 0.0);
    }

    /** 2026-10-27：面板上已經沒有的主要寶石的能量、上次觸發時間清掉。 */
    private void prune(final UUID player, final SkillLoadout loadout) {
        final Map<String, Double> mine = energy.get(player);
        if (mine != null) {
            mine.keySet().removeIf(key -> loadout.find(key.substring(0, Math.max(0, key.indexOf(':')))) < 0);
        }
        final Map<String, Long> fired = lastFired.get(player);
        if (fired != null) {
            fired.keySet().removeIf(key -> loadout.find(key) < 0);
        }
    }

    void forget(final UUID player) {
        energy.remove(player);
        lastFired.remove(player);
        warned.remove(player);
    }

    // ---- 法術圖騰 ----

    /**
     * 法術圖騰放下去的那個法術的數值：插槽法術（含它自己的輔助）加上法術圖騰的效果——
     * 由圖騰施放、每消耗一顆銳星增加傷害、每消耗一顆磐星圖騰更多生命與持續時間、施法速度更少、圖騰上限照寶石等級。
     */
    SkillBuild totemBuild(final PlayerState state, final tw.exilecore.stats.StatSheet sheet, final Socket socket, final int metaLevel,
                          final int powerUsed, final int enduranceUsed) {
        return totemBuild(state, sheet, socket, 0, metaLevel, powerUsed, enduranceUsed);
    }

    /** 2026-10-27 照 PoE2 Spell Totem「uses socketed Spells」：每個插槽法術各一個（照孔的順序；圖騰輪流放）。 */
    List<SkillBuild> totemBuilds(final PlayerState state, final tw.exilecore.stats.StatSheet sheet, final Socket socket, final int metaLevel,
                                 final int powerUsed, final int enduranceUsed) {
        final List<SkillBuild> builds = new ArrayList<>();
        for (int i = 0; i < socket.spells().size(); i++) {
            builds.add(totemBuild(state, sheet, socket, i, metaLevel, powerUsed, enduranceUsed));
        }
        return builds;
    }

    /** 2026-10-27：第 index 個插槽法術交給圖騰放的數值。 */
    SkillBuild totemBuild(final PlayerState state, final tw.exilecore.stats.StatSheet sheet, final Socket socket, final int index, final int metaLevel,
                          final int powerUsed, final int enduranceUsed) {
        final SkillDefinition spell = socket.spells().get(index);
        final SkillLoadout.Entry spellEntry = socket.spellEntries().get(index);
        final MetaSpec spec = socket.spec();
        final SkillDefinition metaDefinition = service.config().skill(socket.metaEntry().skillId());
        final String source = metaDefinition == null ? "法術圖騰" : metaDefinition.name();
        final List<Modifier> mods = new ArrayList<>();
        mods.add(Modifier.flat(StatKeys.FLAG_SPELL_TOTEM, 1, source));
        if (powerUsed > 0 && spec.perPower() > 0) {
            // 2026-10-26 照 PoE2 Spell Totem（poe2db：10% more damage per Power Charge consumed）：「更多」，照原作的「每顆」數值相加成一條（3 顆 = 更多 30%）；
            //   這次之前（2026-10-22 起）是「增加」
            mods.add(Modifier.more(StatKeys.DAMAGE, spec.perPower() * powerUsed / 100.0, source));
        }
        if (enduranceUsed > 0 && spec.perEndurance() > 0) {
            mods.add(Modifier.more(StatKeys.TOTEM_LIFE, spec.perEndurance() * enduranceUsed / 100.0, source));
            mods.add(Modifier.more(StatKeys.SKILL_DURATION, spec.perEndurance() * enduranceUsed / 100.0, source));
        }
        if (spec.lessCastSpeed() > 0) {
            mods.add(Modifier.more(StatKeys.CAST_SPEED, -spec.lessCastSpeed() / 100.0, source));
        }
        final int extraTotems = Math.max(0, spec.totems().intAt(metaLevel) - 1);
        if (extraTotems > 0) {
            mods.add(Modifier.flat(StatKeys.SKILL_TOTEMS, extraTotems, source));
        }
        if (metaDefinition != null) {
            // 2026-10-26 照 PoE2 Spell Totem：主要寶石自己的逐級修飾器（圖騰元素抗性 0→75%、混沌抗性 0→35%）套到放下去的圖騰；這次之前沒有
            for (final ScaledMod mod : service.config().levelModifiers(metaDefinition.id())) {
                mods.add(mod.toModifier(metaLevel, 1.0, source));
            }
            // 2026-10-26 照 PoE2：主要寶石自己的品質（gem_quality.yml：+秒圖騰持續時間、每根圖騰增加施法速度）；這次之前只算插槽法術的品質
            if (tw.exilecore.currency.GemQuality.has(metaDefinition.id())) {
                mods.addAll(SkillService.qualityModifiers(metaDefinition, socket.metaEntry().quality() + SkillService.bonusQuality(sheet)));
            }
        }
        // 2026-10-22：寶石其他孔的輔助全部套在插槽法術上（spellEntry 的輔助就是它們；要圖騰的疾樁、樁怒也在裡面）
        final SupportDefinition carrier = new SupportDefinition("meta." + socket.metaEntry().skillId(), source, "", "PAPER", 1,
                java.util.Set.of(), java.util.Set.of(), 1.0, mods, List.of());
        final List<SupportDefinition> supports = new ArrayList<>();
        supports.add(carrier); // 放第一個：圖騰施法優先（插槽法術自己掛了陷阱施法的話那顆不算）
        // 2026-10-22：要圖騰的輔助（疾樁、樁怒…）插槽法術本身沒有圖騰標籤，innerSupportsOf 把它們併成一顆不挑技能的
        supports.addAll(service.innerSupportsOf(socket.metaEntry(), metaDefinition, spell));
        // 2026-10-23：放進去的寶石的品質一起算（詛咒效果的品質要在算倍率前放進去）
        return SkillBuild.of(service.config(), spell, spellEntry.level(), state.level(), false, supports,
                state.weaponTags(), sheet, SkillService.resonantOf(spellEntry), SkillService.qualityModifiers(spell,
                spellEntry.quality() + SkillService.bonusQuality(sheet))); // 2026-10-26：天賦樹的技能品質
    }

    // ---- 說明 ----

    /** 主要寶石的說明多幾行：插槽裡是什麼、能量上限、現在的能量。 */
    List<String> lines(final Player player, final SkillDefinition meta) {
        final MetaSpec spec = service.config().meta(meta.id());
        if (spec == null) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(meta.id());
        // 2026-10-27 照 PoE2：星咒圖騰也可以放好幾個法術（圖騰輪流放）；這次之前寫「放進第 1 孔」
        lines.add(spec.totem() ? "<#c9a7ff>主要寶石<gray>：把法術的技能寶石放進它的孔（<white>可以放好幾個</white>，圖騰照孔的順序輪流放），其他孔的輔助都套在那些法術上"
                : "<#c9a7ff>主要寶石<gray>：把法術的技能寶石放進它的孔（<white>可以放好幾個</white>，全部一起觸發），其他孔的輔助都套在那些法術上");
        if (spec.totem()) {
            lines.add(String.format(Locale.ROOT, "<gray>放的時候消耗 <white>%d</white> 顆銳星或磐星（銳星先用）：每顆銳星插槽法術更多 <white>%s%%</white> 傷害、"
                    + "每顆磐星圖騰更多 <white>%s%%</white> 生命與持續時間", spec.charges(), number(spec.perPower()), number(spec.perEndurance())));
            lines.add("<gray>插槽法術施法速度更少 <white>" + number(spec.lessCastSpeed()) + "%</white>；不能插有冷卻的法術");
        } else if (spec.invocation()) {
            // 2026-10-26 照 PoE2 Invocation
            lines.add("<gray>開著時，" + spec.kind().condition() + "獲得能量，最多存 <white>" + number(spec.maxEnergy()) + "</white> 點");
            lines.add("<gray>按它的按鍵（冷卻 0.2 秒）花掉能量觸發所有插槽法術：每次花它們的上限加起來（基礎施法時間每 0.1 秒 10 點），夠幾次就連續觸發幾次");
        } else {
            lines.add("<gray>開著時，" + spec.kind().condition() + "獲得能量；能量到上限（插槽法術的基礎施法時間每 0.1 秒 10 點，加起來）就觸發所有插槽法術、失去全部能量");
            if (spec.lessDamage() > 0) {
                lines.add("<gray>插槽法術更少 <white>" + number(spec.lessDamage()) + "%</white> 傷害");
            }
            if (spec.moreDamage() > 0) {
                lines.add("<gray>插槽法術更多 <white>" + number(spec.moreDamage()) + "%</white> 傷害");
            }
        }
        if (position < 0) {
            return lines;
        }
        final String problem = socketProblem(state, position);
        if (problem != null) {
            lines.add("<red>" + ItemRenderer.escape(problem));
            return lines;
        }
        final Socket socket = socket(state, position);
        if (socket != null) {
            if (spec.totem()) {
                lines.add("<gray>插槽：<white>" + ItemRenderer.escape(socket.names()) + (socket.spells().size() > 1 ? "<gray>（圖騰照順序輪流放）" : ""));
                // 2026-10-21：圖騰替你放插槽法術的速度（以前只寫放圖騰的速度，看不出圖騰放得多快）
                final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
                final SkillBuild metaBuild = service.build(state, sheet, meta, socket.metaEntry()); // 跟真的放的時候一樣用實際等級（含裝備加的）
                final List<SkillBuild> builds = totemBuilds(state, sheet, socket, metaBuild.level(), spec.charges(), 0);
                final SkillBuild build = builds.getFirst();
                final double ticks = rotationTicks(builds, sheet);
                if (ticks > 0) {
                    // 2026-10-26：含品質的每根圖騰施法速度（照滿圖騰算）；2026-10-27：好幾個法術時是輪流放的平均
                    final double rate = 20.0 / ticks;
                    lines.add(String.format(Locale.ROOT, "<gray>圖騰施放%s：每根每秒 <white>%.2f</white> 次（最多 %d 根，合計 <white>%.2f</white> 次）",
                            ItemRenderer.escape(socket.names()), rate, build.totems(), rate * build.totems()));
                }
                if (build.deploy() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>圖騰持續 <white>%s</white> 秒", number(build.durationTicks(build.deploy().duration()) / 20.0)));
                }
                lines.add("<dark_gray>其他孔的輔助都套在插槽法術上；要圖騰的（疾樁、過剩 II、樁怒…）、加施法速度與持續時間的也套到圖騰本身");
            } else if (spec.invocation()) {
                final double cost = socket.maxEnergy();
                final double stored = energyOf(player.getUniqueId(), socket);
                lines.add(String.format(Locale.ROOT, "<gray>插槽：<white>%s</white>（能量 %s／%s，觸發一次要 %s，現在按可觸發 %d 次）",
                        ItemRenderer.escape(socket.names()), number(Math.floor(stored)),
                        number(invocationMax(spec, energyModifiers(socket.metaEntry()), sheetEnergy(plugin.players().sheet(player), meta.tags()),
                                service.qualityFlat(player, socket.metaEntry().skillId(), tw.exilecore.stats.GemQualityKeys.META_MAX_ENERGY))),
                        number(cost), invokeTimes(stored, cost, socket.anyCooldown())));
            } else {
                lines.add(String.format(Locale.ROOT, "<gray>插槽：<white>%s</white>（能量 %s／%s）", ItemRenderer.escape(socket.names()),
                        number(Math.floor(energyOf(player.getUniqueId(), socket))), number(socket.maxEnergy())));
            }
        }
        return lines;
    }

    /**
     * 2026-10-27：圖騰輪流放這幾個法術時，平均放一次隔幾 tick（照滿圖騰算）；放不了（沒有 interval）的回傳 0。
     */
    static double rotationTicks(final List<SkillBuild> builds, final tw.exilecore.stats.StatSheet sheet) {
        double total = 0;
        for (final SkillBuild build : builds) {
            if (build.deploy() == null || build.deploy().interval() <= 0) {
                return 0;
            }
            total += SkillDeployables.totemTicks(build, sheet, build.totems());
        }
        return builds.isEmpty() ? 0 : total / builds.size();
    }

    private static String number(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
