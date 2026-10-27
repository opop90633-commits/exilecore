package tw.exilecore.charge;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 球（銳星、怒星、磐星）的執行期：誰身上有幾顆、什麼時候獲得、什麼時候消失，並把效果當成修飾器給屬性表。
 * <p>
 * 獲得：{@link #onCrit}、{@link #onKill}、{@link #onHit} 由戰鬥流程呼叫，照屬性表的 {@code charge.chance.代號}
 * 擲骰（百分比點數）；{@link #onDealtHit}：自己擊中敵人時照這一下的技能（含輔助寶石）擲 {@code charge.on_hit.代號}；
 * {@link #grant}：直接給（聚星戰吼）。上限 = charges.yml 的基礎值 + {@code charge.max.代號}；持續時間 = 基礎 ×（1 + {@code charge.duration}）。
 * 沒有任何來源（詞綴、天賦、輔助寶石、戰吼）的玩家一顆球也不會有。
 * 用掉：{@link #consume}（瞬影擊冷卻中消耗一顆怒星直接放）。
 * 球不存檔：登出、死亡就清掉（撐 15 秒的東西）。
 */
public final class ChargeService implements Listener {

    /** 多久檢查一次過期（tick）。 */
    public static final long TICK_INTERVAL = 10;

    private final ExileCore plugin;
    private final ChargeConfig config;
    private final Map<UUID, Map<String, ChargeBag>> bags = new HashMap<>();
    /** 事件給戰星的冷卻（2026-10）：玩家 → 事件 → 下一次可以給的 tick。 */
    private final Map<UUID, Map<String, Long>> eventReady = new HashMap<>();
    /** 定期給戰星（2026-10；2026-10-26 星律拿掉了，charge.periodic 屬性還在）：玩家 → 下一次給的 tick。 */
    private final Map<UUID, Long> periodicNext = new HashMap<>();

    /** 屬性鍵（2026-10）：某個事件給一顆戰星的機率，FLAT 百分比點數；charge.event.事件.代號（例如 charge.event.stun_applied.endurance）。 */
    public static final String EVENT = "charge.event.";
    /** 屬性鍵（2026-10）：某個事件給戰星的冷卻，FLAT 秒；charge.event_cooldown.事件。 */
    public static final String EVENT_COOLDOWN = "charge.event_cooldown.";
    /** 屬性鍵（2026-10）：獲得一顆戰星時多一顆同種的機率，FLAT 百分比點數；charge.extra（任何種類）或 charge.extra.代號。 */
    public static final String EXTRA = "charge.extra";
    /** 2026-10-15（照 PoE2 的充能滿盈 II）：獲得戰星時 x% 機率再多一顆隨機種類的。 */
    public static final String EXTRA_RANDOM = "charge.extra_random";
    /** 屬性鍵（2026-10）：每分鐘給幾顆戰星（照最高的能力值：力量 → 磐星、敏捷 → 怒星、智慧 → 銳星），FLAT；好幾個來源加起來。 */
    public static final String PERIODIC = "charge.periodic";

    // ---- 2026-10-24：戰星輔助（照 PoE2，數值也照 PoE2；FLAT 數值，都只對被輔助的技能） ----
    /** 恆星（Perpetual Charge）：消耗戰星時有 x% 機率不扣掉，但效果照拿。 */
    public static final String SUP_KEEP = "charge.sup.keep";
    /** 倍星（Heightened Charges）：消耗戰星的效果有 x% 機率加倍。 */
    public static final String SUP_DOUBLE = "charge.sup.double";
    /** 抑星（Inhibitor）：不消耗戰星與灌注；身上每有一種戰星或灌注更多 x% 傷害。 */
    public static final String SUP_INHIBIT = "charge.sup.inhibit";
    /** 狂速（Ferocity）：放的時候消耗一顆怒星，這一次更多 x% 技能速度；被輔助的技能不會產生怒星。 */
    public static final String SUP_FEROCITY = "charge.sup.ferocity";
    /** 久咒（Abiding Hex）：放的時候消耗一顆銳星，這一次更多 x% 持續時間。 */
    public static final String SUP_ABIDING_HEX = "charge.sup.abiding_hex";
    /** 碎地（Jagged Ground）：放的時候消耗一顆磐星，留下 x 秒的碎石地（裡面的敵人減速 20%）。 */
    public static final String SUP_JAGGED = "charge.sup.jagged_ground";
    /** 蔓寒（Creeping Chill）：造出冰晶時消耗一顆銳星，那顆冰晶周圍結出會變大的冰緩地。 */
    public static final String SUP_CREEPING = "charge.sup.creeping_chill";
    /** 雷星（Empowered Sparks）：被輔助的技能產生銳星時，強化接下來 x 次攻擊；強化的攻擊用出去時射出游雷。 */
    public static final String SUP_SPARKS = "charge.sup.empower_sparks";
    /** 雷星：每次射出幾顆游雷。 */
    public static final String SUP_SPARK_PROJECTILES = "charge.sup.spark_projectiles";
    /** 祭柱的銳星（Guatelitzi's Ablation）：祭柱還在的時候每 x 秒給一顆銳星。 */
    public static final String SUP_OFFERING_POWER = "charge.sup.offering_power";
    /** 同上：放的時候犧牲目前生命的 x%。 */
    public static final String SUP_OFFERING_SACRIFICE = "charge.sup.offering_sacrifice";
    /** 同上：祭柱倒下後，每秒回復 x% 生命與魔力，持續的時間等於祭柱撐了多久。 */
    public static final String SUP_OFFERING_REGEN = "charge.sup.offering_regen";
    /** 連段銳星（Ailith's Chimes）：用掉連擊時每一層 x% 機率獲得銳星（超過 100% 的部分必定給）。 */
    public static final String SUP_COMBO_POWER = "charge.sup.combo_power";
    /** 完磐（Perfected Endurance）：完美時機打中時獲得 x 顆磐星。 */
    public static final String SUP_PERFECT_ENDURANCE = "charge.sup.perfect_endurance";
    /** 對傳奇怪物的事件改成直接給幾顆（不擲機率）：charge.sup.unique.事件.代號（震磐：重暈傳奇怪給 1／3 顆磐星）。 */
    public static final String SUP_UNIQUE = "charge.sup.unique.";
    // ---- 2026-10-24：更多戰星來源（照 PoE2） ----
    /** 共鳴（Resonance 基石）：該拿怒星改拿銳星、該拿磐星改拿怒星、該拿銳星改拿磐星。FLAT 1。 */
    public static final String RESONANCE = "flag.resonance";
    /** 赤焰導管：某種戰星滿了就全部失去，並讓自己感電。charge.lose_at_max.代號，FLAT 1。 */
    public static final String LOSE_AT_MAX = "charge.lose_at_max.";
    /** 馭星訣（Charge Regulation）：每 x 秒消耗每種戰星各一顆。 */
    public static final String REGULATION_CONSUME = "charge.regulation_consume";
    /** 有寫的話，charge.event_cooldown.* 的冷卻是共用的（戰意：冰凍、電擊、釘住任一給了，三種都進冷卻）。FLAT 1。 */
    public static final String EVENT_COOLDOWN_SHARED = "charge.event_cooldown.shared";
    /** 怒氣滿的時候給一顆隨機的戰星，至少隔 x 秒（憎鑄）。 */
    public static final String ON_MAX_RAGE = "charge.on_max_rage";
    /** 地面效果（穢陣）自然結束時給的戰星：charge.zone_end.代號，FLAT 顆數。 */
    public static final String ZONE_END = "charge.zone_end.";
    /** 打中「被招架」的敵人時用掉招架給的戰星（回槍躍，PoE2 Disengage）：charge.on_parried.代號，FLAT 顆數；一次施放只給一次。 */
    public static final String ON_PARRIED = "charge.on_parried.";
    /** 召喚物撲殺時主人拿幾顆（霧鴉）：charge.minion_cull.代號，FLAT 顆數。 */
    public static final String MINION_CULL = "charge.minion_cull.";
    /** 撲殺、擊殺（技能自己的 charge.event.kill.*）：稀有怪多給一顆、傳奇怪多給兩顆（照 PoE2）。 */
    static final java.util.Set<String> RARITY_BONUS_EVENTS = java.util.Set.of("cull", "kill"); // 2026-10-24：剔殘（擊殺）

    public ChargeService(final ExileCore plugin, final ChargeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public ChargeConfig config() {
        return config;
    }

    // ---- 獲得 ----

    /** 玩家自己的攻擊或法術暴擊了。 */
    public void onCrit(final Player player) {
        trigger(player, ChargeType.Trigger.CRIT);
    }

    /** 玩家擊殺了怪物。 */
    public void onKill(final Player player) {
        trigger(player, ChargeType.Trigger.KILL);
    }

    /** 玩家被敵人的攻擊打中了。 */
    public void onHit(final Player player) {
        trigger(player, ChargeType.Trigger.HIT);
    }

    private void trigger(final Player player, final ChargeType.Trigger trigger) {
        final List<ChargeType> types = config.triggeredBy(trigger);
        if (types.isEmpty() || !player.isOnline()) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        boolean changed = false;
        for (final ChargeType type : types) {
            final double chance = type.chanceFor(sheet);
            if (chance <= 0 || plugin.items().random().nextDouble() >= chance) {
                continue;
            }
            changed |= gainRolled(player, type, sheet, 0, 0);
        }
        if (changed) {
            plugin.players().markDirty(player); // 屬性表重算：多了球的效果
        }
    }

    /**
     * 這一下擊中的「擊中時獲得」機率（0–1），照這個技能的屬性表（輔助寶石的也算，標籤照技能的）。
     * 在傷害事件裡先算好，等事件確定沒被取消再交給 {@link #onDealtHit}。沒有任何機率回傳空的。
     */
    public Map<String, Double> hitChances(final StatSheet effective, final java.util.Set<String> context) {
        Map<String, Double> chances = null;
        final boolean ferocity = effective.flat(SUP_FEROCITY, context) > 0; // 2026-10-24：狂速的技能不會產生怒星
        for (final ChargeType type : config.types()) {
            if (ferocity && "frenzy".equals(type.id()) || blocked(effective, context, type.id())) {
                continue;
            }
            final double chance = Math.max(0, Math.min(1, effective.flat(StatKeys.chargeOnHit(type.id()), context) / 100.0));
            if (chance > 0) {
                if (chances == null) {
                    chances = new HashMap<>();
                }
                chances.put(type.id(), chance);
            }
        }
        if (chances != null) {
            final double extra = effective.flat(EXTRA, context);
            if (extra > 0) {
                chances.put(EXTRA, extra / 100.0); // 2026-10：輔助寶石的「多一顆」只算這個技能給的
            }
            final double random = effective.flat(EXTRA_RANDOM, context);
            if (random > 0) {
                chances.put(EXTRA_RANDOM, random / 100.0);
            }
            final double sparks = effective.flat(SUP_SPARKS, context);
            if (sparks > 0) {
                chances.put(SUP_SPARKS, sparks); // 2026-10-24：雷星（不是機率，是要強化幾次）
                chances.put(SUP_SPARK_PROJECTILES, effective.flat(SUP_SPARK_PROJECTILES, context));
            }
        }
        return chances == null ? Map.of() : chances;
    }

    /** 2026-10-27 照 PoE2 Potential／Rally：被輔助的技能不會產生銳星／磐星。 */
    static boolean blocked(final StatSheet effective, final java.util.Set<String> context, final String chargeId) {
        return effective != null && ("power".equals(chargeId) && effective.flat(tw.exilecore.stats.SupportExtraKeys.POTENTIAL, context) > 0
                || "endurance".equals(chargeId) && effective.flat(tw.exilecore.stats.SupportExtraKeys.RALLY, context) > 0);
    }

    /** 玩家自己擊中了敵人（召喚物的不算）：照 {@link #hitChances} 算好的機率各擲一次。 */
    public void onDealtHit(final Player player, final Map<String, Double> chances) {
        if (chances.isEmpty() || !player.isOnline() || player.isDead()) {
            return; // 死了之後才打到的投射物不給
        }
        final StatSheet sheet = plugin.players().sheet(player);
        // 技能屬性表的 charge.extra 含玩家本身的（gainRolled 會再加一次），只留輔助寶石給的那份
        final double localExtra = Math.max(0, chances.getOrDefault(EXTRA, 0.0) - sheet.flat(EXTRA, java.util.Set.of()) / 100.0);
        final double localRandom = Math.max(0, chances.getOrDefault(EXTRA_RANDOM, 0.0) - sheet.flat(EXTRA_RANDOM, java.util.Set.of()) / 100.0);
        boolean changed = false;
        for (final Map.Entry<String, Double> entry : chances.entrySet()) {
            final ChargeType type = config.type(entry.getKey());
            if (type == null || plugin.items().random().nextDouble() >= entry.getValue()) {
                continue;
            }
            changed |= gainRolled(player, type, sheet, localExtra, localRandom);
            if ("power".equals(type.id())) {
                empowerSparks(player, chances.getOrDefault(SUP_SPARKS, 0.0), chances.getOrDefault(SUP_SPARK_PROJECTILES, 0.0));
            }
        }
        if (changed) {
            plugin.players().markDirty(player);
        }
    }

    /** 2026-10-24（雷星）：被輔助的技能產生了銳星：強化接下來幾次攻擊（打中時射出游雷，ChargeEffects 處理）。 */
    private void empowerSparks(final Player player, final double attacks, final double projectiles) {
        if (attacks > 0 && plugin.skills() != null) {
            plugin.skills().chargeEffects().empower(player, (int) Math.round(attacks), (int) Math.round(projectiles));
        }
    }

    /**
     * 直接獲得幾顆（聚星戰吼）：滿了就把最舊的那顆換成新的（等於刷新它的時間）。回傳實際多了幾顆。
     */
    public int grant(final Player player, final String chargeId, final int count) {
        final ChargeType type = config.type(chargeId);
        if (type == null || count <= 0 || !player.isOnline() || player.isDead()) {
            return 0;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        int grew = 0;
        for (int i = 0; i < count; i++) {
            if (gainRolled(player, type, sheet, 0, 0)) {
                grew++;
            }
        }
        // 顆數有變就要重算。2026-10-27 效能：滿了（只刷新時間、顆數沒變）不重算——屬性表只看顆數；
        // 「最近獲得過銳星」照樣由每 10 tick 的條件檢查補上。這次之前滿了也整張重算
        if (grew > 0) {
            plugin.players().markDirty(player);
        }
        return grew;
    }

    /**
     * 用掉一顆（最快消失的那顆）：瞬影擊冷卻中消耗一顆怒星直接放。沒有回傳 false。
     */
    public boolean consume(final Player player, final String chargeId) {
        final Map<String, ChargeBag> own = bags.get(player.getUniqueId());
        final ChargeBag bag = own == null ? null : own.get(chargeId);
        if (bag == null || !bag.consume(plugin.getServer().getCurrentTick())) {
            return false;
        }
        if (bag.isEmpty()) {
            own.remove(chargeId);
        }
        if (tw.exilecore.stats.Recently.EVENTS.contains(chargeId)) {
            plugin.players().get(player).markRecent(chargeId, plugin.getServer().getCurrentTick()); // 「最近消耗過銳星／怒星／磐星」
        }
        plugin.players().conditions().onChargeSpent(player, chargeId, plugin.getServer().getCurrentTick()); // 2026-10-26：天賦樹（消耗時回復、最近消耗幾顆）
        plugin.players().markDirty(player); // 屬性表重算：少了一顆的效果
        tw.exilecore.ascendancy.AscendancyEffects.chargeSpent(player, chargeId); // 2026-10-26（昇華・元素湧動：消耗戰星時）
        return true;
    }

    /**
     * 2026-10-24：技能消耗一顆戰星的統一入口（照 PoE2 的戰星輔助）。effective 是那個技能疊好輔助的屬性表（null = 沒有輔助）。
     * 回傳效果倍率：0 = 沒用掉（身上沒有、或掛了抑星不消耗）、1 = 一般、2 = 倍星讓效果加倍。
     * 恆星：機率不扣掉那顆，但一樣算「消耗了」（效果照拿、「最近消耗過」照記）。
     */
    public int spend(final Player player, final String chargeId, final StatSheet effective, final java.util.Set<String> tags) {
        final java.util.Set<String> context = tags == null ? java.util.Set.of() : tags;
        if (effective != null && effective.flat(SUP_INHIBIT, context) > 0) {
            return 0;
        }
        if (count(player, chargeId) <= 0) {
            return 0;
        }
        final double keep = effective == null ? 0 : effective.flat(SUP_KEEP, context);
        if (keep > 0 && plugin.items().random().nextDouble() * 100 < keep) {
            if (tw.exilecore.stats.Recently.EVENTS.contains(chargeId)) {
                plugin.players().get(player).markRecent(chargeId, plugin.getServer().getCurrentTick());
            }
            plugin.players().conditions().onChargeSpent(player, chargeId, plugin.getServer().getCurrentTick()); // 2026-10-26：恆星也算消耗
        } else if (!consume(player, chargeId)) {
            return 0;
        }
        final double twice = effective == null ? 0 : effective.flat(SUP_DOUBLE, context);
        if ("frenzy".equals(chargeId) && tw.exilecore.ascendancy.AscendancyEffects.doubleFrenzy(player)) {
            return 2; // 2026-10-26（昇華・驚險追逐）：消耗怒星的效益有 50% 機率加倍
        }
        int counted = twice > 0 && plugin.items().random().nextDouble() * 100 < twice ? 2 : 1;
        // 2026-10-26 照 PoE2 天賦樹：「長棍技能消耗銳星時多算一顆」——一次用掉的最後一顆多算（一次用一顆的技能就是每次多一顆）
        if ("power".equals(chargeId) && context.contains(tw.exilecore.stats.Tags.QUARTERSTAFF) && count(player, chargeId) <= 0) {
            counted += (int) Math.round(Math.max(0, plugin.players().sheet(player).flat(tw.exilecore.stats.KeystoneKeys.QUARTERSTAFF_EXTRA_POWER, context)));
        }
        return counted;
    }

    /** 2026-10-26 能量連結：正在把戰星轉給隊友（隊友也有能量連結時不再轉一次）。 */
    private boolean conduitPassing;

    /** 能量連結（Conduit）：這顆戰星給你存在範圍內的每個隊友（不給你；沒有隊友就沒了）。 */
    private void conduit(final Player player, final ChargeType type, final StatSheet sheet) {
        if (plugin.party() == null) {
            return;
        }
        final double radius = tw.exilecore.combat.Presence.radius(sheet);
        conduitPassing = true;
        try {
            for (final UUID id : plugin.party().groupMembers(player.getUniqueId())) {
                final Player ally = plugin.getServer().getPlayer(id);
                if (ally == null || ally == player || !ally.isOnline() || ally.isDead() || !ally.getWorld().equals(player.getWorld())
                        || ally.getLocation().distanceSquared(player.getLocation()) > radius * radius) {
                    continue;
                }
                if (gainOne(ally, type, plugin.players().sheet(ally))) {
                    plugin.players().markDirty(ally);
                }
            }
        } finally {
            conduitPassing = false;
        }
    }

    /** 2026-10-24：瞬影擊連打期間不能拿銳星（照 PoE2「使用這個技能時不能獲得銳星」）：玩家 → 到哪個 tick。 */
    private final Map<UUID, Long> noPowerUntil = new HashMap<>();

    public void blockPower(final Player player, final long untilTick) {
        noPowerUntil.put(player.getUniqueId(), untilTick);
    }

    /** 獲得一顆；回傳顆數有沒有變多（滿了只刷新時間 = false）。 */
    private boolean gainOne(final Player player, final ChargeType original, final StatSheet sheet) {
        if (!conduitPassing && tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_CONDUIT)) {
            conduit(player, original, sheet); // 2026-10-26 照 PoE2 基石：能量連結——改給存在範圍內的隊友
            return false;
        }
        final ChargeType type = resonate(original, sheet);
        if ("power".equals(type.id())) {
            final Long until = noPowerUntil.get(player.getUniqueId());
            if (until != null) {
                if (plugin.getServer().getCurrentTick() < until) {
                    return false; // 瞬影擊連打中
                }
                noPowerUntil.remove(player.getUniqueId());
            }
        }
        final ChargeBag bag = bags.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>()).computeIfAbsent(type.id(), id -> new ChargeBag());
        final int max = maxOf(type, sheet);
        final boolean grew = bag.gain(plugin.getServer().getCurrentTick(), max, durationTicks(type, sheet));
        plugin.players().conditions().onChargeGained(player, type.id(), plugin.getServer().getCurrentTick()); // 2026-10-26：天賦樹「最近獲得過銳星」
        if (grew && bag.count() >= max && tw.exilecore.stats.StatKeys.flag(sheet, LOSE_AT_MAX + type.id())) {
            // 2026-10-24（赤焰導管）：滿了就全部失去，並讓自己感電
            bag.clear();
            if (plugin.ailments() != null) {
                plugin.ailments().groundAilment(player, tw.exilecore.ailment.AilmentType.SHOCK, SELF_SHOCK, SELF_SHOCK_SECONDS);
            }
            plugin.players().get(player).notice("<" + type.color() + ">" + type.name() + "<gray>滿了：全部失去，你感電了",
                    plugin.getServer().getCurrentTick() + 40);
        }
        return grew;
    }

    /** 赤焰導管讓自己感電：受到的傷害增加 20%、4 秒（PoE2 感電的基礎值）。 */
    static final double SELF_SHOCK = 0.2;
    static final double SELF_SHOCK_SECONDS = 4;

    /** 共鳴（2026-10-24，PoE2 Resonance 基石）：該拿怒星改拿銳星、該拿磐星改拿怒星、該拿銳星改拿磐星。 */
    private ChargeType resonate(final ChargeType type, final StatSheet sheet) {
        if (!tw.exilecore.stats.StatKeys.flag(sheet, RESONANCE)) {
            return type;
        }
        final ChargeType swapped = config.type(resonance(type.id()));
        return swapped == null ? type : swapped;
    }

    /** 共鳴的對應（純函式，測試用）。 */
    public static String resonance(final String id) {
        return switch (id) {
            case "frenzy" -> "power";
            case "endurance" -> "frenzy";
            case "power" -> "endurance";
            default -> id;
        };
    }

    /** 2026-10-26：這次獲得要不要改成直接補滿（roll 是 0–1 的亂數；機率 = charge.fill.代號 %）。 */
    public static boolean fills(final StatSheet sheet, final String chargeId, final double roll) {
        final double chance = sheet.flat(tw.exilecore.stats.TreeMechanicKeys.CHARGE_FILL + chargeId, java.util.Set.of()) / 100.0;
        return chance > 0 && roll < Math.min(1, chance);
    }

    /**
     * 獲得一顆，再擲「多一顆同種的」（2026-10，照 PoE2 的「當你獲得一顆 X 時，有 N% 機率多一顆」）：
     * 機率 = 屬性表的 charge.extra 與 charge.extra.代號（百分比點數）＋ localExtra（輔助寶石只對被輔助的技能，0–1）。多出來的那顆不會再擲。
     */
    private boolean gainRolled(final Player player, final ChargeType type, final StatSheet sheet, final double localExtra,
                               final double localRandom) {
        boolean grew = gainOne(player, type, sheet);
        // 2026-10-26 照 PoE2（天賦「獲得 X 時有 N% 機率改成直接補滿」）：charge.fill.代號
        if (fills(sheet, type.id(), plugin.items().random().nextDouble())) {
            final int max = maxOf(type, sheet);
            for (int i = 0; i < max && gainOne(player, type, sheet); i++) {
                grew = true;
            }
        }
        final double extra = (sheet.flat(EXTRA, java.util.Set.of()) + sheet.flat(EXTRA + "." + type.id(), java.util.Set.of())) / 100.0 + localExtra;
        if (extra > 0 && plugin.items().random().nextDouble() < Math.min(1, extra)) {
            grew |= gainOne(player, type, sheet);
        }
        // 2026-10-15（充能滿盈 II）：再擲一次「多一顆隨機種類的」
        final double random = sheet.flat(EXTRA_RANDOM, java.util.Set.of()) / 100.0 + localRandom;
        if (random > 0 && plugin.items().random().nextDouble() < Math.min(1, random)) {
            final java.util.List<ChargeType> all = java.util.List.copyOf(config.types());
            if (!all.isEmpty()) {
                grew |= gainOne(player, all.get(plugin.items().random().nextInt(all.size())), sheet);
            }
        }
        return grew;
    }

    /**
     * 事件給的戰星（2026-10，照 PoE2）：重暈（stun_applied）、完全破甲（armour_broken）、冰凍（freeze）、感電（shock）、點燃（ignite）、
     * 撲殺（cull）、擊殺稀有或傳奇怪（kill_rare）、翻滾（dodge）。
     * 每種戰星照 charge.event.事件.代號 擲一次；effective 是那一下技能的屬性表（輔助寶石只對被輔助的技能），null 用玩家的。
     * 寫了 charge.event_cooldown.事件（秒）的，同一種事件給的戰星至少隔這麼久（戰意這種常駐技能）。
     * 撲殺：稀有怪多一顆、傳奇怪多兩顆（照 PoE2「稀有度越高的怪物會賦予額外的能量球」）。
     */
    public void onEvent(final Player player, final String event, final StatSheet effective, final java.util.Set<String> context,
                        final org.bukkit.entity.LivingEntity target) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final StatSheet source = effective != null ? effective : sheet;
        final java.util.Set<String> tags = context != null ? context : java.util.Set.of();
        final long now = plugin.getServer().getCurrentTick();
        final Map<String, Long> ready = eventReady.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        if (ready.getOrDefault(event, 0L) > now) {
            return;
        }
        int bonus = 0;
        if (RARITY_BONUS_EVENTS.contains(event) && target != null && plugin.monsters() != null) {
            bonus = plugin.monsters().data(target).map(data -> switch (data.rarity()) {
                case RARE -> 1;
                case UNIQUE -> 2;
                default -> 0;
            }).orElse(0);
        }
        final double localExtra = effective != null ? effective.flat(EXTRA, tags) / 100.0 - sheet.flat(EXTRA, java.util.Set.of()) / 100.0 : 0;
        final double localRandom = effective != null
                ? effective.flat(EXTRA_RANDOM, tags) / 100.0 - sheet.flat(EXTRA_RANDOM, java.util.Set.of()) / 100.0 : 0;
        boolean changed = false;
        boolean gave = false;
        final boolean unique = target != null && plugin.monsters() != null && plugin.monsters().data(target)
                .map(data -> data.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE).orElse(false);
        final boolean ferocity = effective != null && effective.flat(SUP_FEROCITY, tags) > 0;
        for (final ChargeType type : config.types()) {
            if (ferocity && "frenzy".equals(type.id()) || blocked(effective, tags, type.id())) {
                continue; // 2026-10-24：狂速的技能不會產生怒星；2026-10-27：潛力、重整的技能不會產生銳星、磐星
            }
            // 2026-10-24（震磐）：對傳奇怪物直接給幾顆，不擲機率
            final int forUnique = unique ? (int) Math.round(source.flat(SUP_UNIQUE + event + "." + type.id(), tags)) : 0;
            final double chance = source.flat(EVENT + event + "." + type.id(), tags) / 100.0;
            if (forUnique <= 0 && (chance <= 0 || plugin.items().random().nextDouble() >= Math.min(1, chance))) {
                continue;
            }
            gave = true;
            for (int i = 0; i < Math.max(1 + bonus, forUnique); i++) {
                changed |= gainRolled(player, type, sheet, Math.max(0, localExtra), Math.max(0, localRandom));
            }
            if ("power".equals(type.id()) && effective != null) {
                empowerSparks(player, effective.flat(SUP_SPARKS, tags), effective.flat(SUP_SPARK_PROJECTILES, tags));
            }
        }
        if (gave) {
            final double cooldown = source.flat(EVENT_COOLDOWN + event, tags);
            if (cooldown > 0) {
                ready.put(event, now + Math.round(cooldown * 20));
                if (source.flat(EVENT_COOLDOWN_SHARED, tags) > 0) {
                    // 2026-10-24（戰意，PoE2）：冰凍、電擊、釘住共用一個冷卻
                    for (final String other : ChargeText.EVENTS.keySet()) {
                        if (source.flat(EVENT_COOLDOWN + other, tags) > 0) {
                            ready.put(other, now + Math.round(source.flat(EVENT_COOLDOWN + other, tags) * 20));
                        }
                    }
                }
            }
        }
        if (changed) {
            plugin.players().markDirty(player);
        }
    }

    /** 馭星訣：玩家 → 下一次消耗的 tick。 */
    private final Map<UUID, Long> regulationNext = new HashMap<>();

    /** 2026-10-24（馭星訣，PoE2 Charge Regulation）：開著的人每 x 秒消耗每種戰星各一顆。 */
    private void regulation(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final double seconds = plugin.players().get(player).hasCharacter()
                    ? plugin.players().sheet(player).flat(REGULATION_CONSUME, java.util.Set.of()) : 0;
            if (seconds <= 0) {
                regulationNext.remove(player.getUniqueId());
                continue;
            }
            final Long next = regulationNext.get(player.getUniqueId());
            if (next == null) {
                regulationNext.put(player.getUniqueId(), now + Math.round(seconds * 20));
                continue;
            }
            if (now < next) {
                continue;
            }
            regulationNext.put(player.getUniqueId(), now + Math.round(seconds * 20));
            boolean changed = false;
            for (final ChargeType type : config.types()) {
                changed |= consume(player, type.id());
            }
            if (changed) {
                plugin.players().markDirty(player);
            }
        }
    }

    /** 定期給的戰星（2026-10；星律 2026-10-26 拿掉了，屬性還在）：屬性表有 charge.periodic（每分鐘幾顆）的人，時間到就照最高的能力值給一顆。 */
    private void periodic(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isDead() || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final double perMinute = sheet.flat(PERIODIC, java.util.Set.of());
            final double seconds = perMinute <= 0 ? 0 : 60.0 / perMinute;
            if (seconds <= 0) {
                periodicNext.remove(player.getUniqueId());
                continue;
            }
            final Long next = periodicNext.get(player.getUniqueId());
            if (next == null) {
                periodicNext.put(player.getUniqueId(), now + Math.round(Math.max(1, seconds) * 20));
                continue;
            }
            if (now < next) {
                continue;
            }
            periodicNext.put(player.getUniqueId(), now + Math.round(Math.max(1, seconds) * 20));
            final double str = sheet.get(StatKeys.STR);
            final double dex = sheet.get(StatKeys.DEX);
            final double intel = sheet.get(StatKeys.INT);
            final String id = str >= dex && str >= intel ? "endurance" : dex >= intel ? "frenzy" : "power";
            final ChargeType type = config.type(id);
            if (type != null && gainRolled(player, type, sheet, 0, 0)) {
                plugin.players().markDirty(player);
            }
        }
    }

    /** 這個玩家這種球最多幾顆（見 {@link ChargeType#maxFor}）。 */
    public static int maxOf(final ChargeType type, final StatSheet sheet) {
        return type.maxFor(sheet);
    }

    /** 這個玩家這種球每顆撐幾 tick（見 {@link ChargeType#durationTicksFor}）。 */
    public static long durationTicks(final ChargeType type, final StatSheet sheet) {
        return type.durationTicksFor(sheet);
    }

    // ---- 狀態 ----

    /** 這種球現在有幾顆（已經過期、還沒清掉的不算）。 */
    public int count(final Player player, final String chargeId) {
        final Map<String, ChargeBag> own = bags.get(player.getUniqueId());
        final ChargeBag bag = own == null ? null : own.get(chargeId);
        return bag == null ? 0 : bag.count(plugin.getServer().getCurrentTick());
    }

    /** 這種球的名字（charges.yml；沒有這種回傳代號）。 */
    public String nameOf(final String chargeId) {
        final ChargeType type = config.type(chargeId);
        return type == null ? ChargeText.name(chargeId) : type.name();
    }

    /** 這種球的顏色（MiniMessage 的顏色；沒有這種是白色）。 */
    public String colorOf(final String chargeId) {
        final ChargeType type = config.type(chargeId);
        return type == null ? "white" : type.color();
    }

    /** 屬性表的來源：身上每種球的效果（一顆的量 × 顆數）。 */
    public List<Modifier> modifiers(final Player player) {
        final Map<String, ChargeBag> own = bags.get(player.getUniqueId());
        if (own == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final Map.Entry<String, ChargeBag> entry : own.entrySet()) {
            final ChargeType type = config.type(entry.getKey());
            if (type != null) {
                final int count = entry.getValue().count();
                list.addAll(type.modifiers(count));
                if (count > 0) {
                    list.add(Modifier.flat(tw.exilecore.stats.StatKeys.CHARGE_HELD + entry.getKey(), count, "charges"));
                }
            }
        }
        return list;
    }

    /** 每半秒：過期的球掉掉、上限變小的多出來的丟掉；有變就重算屬性。 */
    public void tick() {
        final long now = plugin.getServer().getCurrentTick();
        periodic(now); // 2026-10：定期給戰星（charge.periodic）
        regulation(now); // 2026-10-24：馭星訣（定期消耗戰星）
        final List<UUID> gone = new ArrayList<>();
        for (final Map.Entry<UUID, Map<String, ChargeBag>> owner : bags.entrySet()) {
            final Player player = plugin.getServer().getPlayer(owner.getKey());
            if (player == null) {
                gone.add(owner.getKey());
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            boolean changed = false;
            for (final Map.Entry<String, ChargeBag> entry : owner.getValue().entrySet()) {
                final ChargeType type = config.type(entry.getKey());
                changed |= entry.getValue().expire(now) > 0;
                if (type != null) {
                    changed |= entry.getValue().trim(maxOf(type, sheet)) > 0;
                }
            }
            owner.getValue().values().removeIf(ChargeBag::isEmpty);
            if (owner.getValue().isEmpty()) {
                gone.add(owner.getKey());
            }
            if (changed) {
                plugin.players().markDirty(player);
            }
        }
        for (final UUID id : gone) {
            bags.remove(id);
        }
    }

    /** 動作列上的一小段：「銳×2 怒×1」，各種戰星用自己的顏色；沒有球是空字串。 */
    public String hudText(final Player player) {
        final Map<String, ChargeBag> own = bags.get(player.getUniqueId());
        if (own == null || own.isEmpty()) {
            return "";
        }
        final long now = plugin.getServer().getCurrentTick();
        final StringBuilder text = new StringBuilder();
        for (final ChargeType type : config.types()) {
            final ChargeBag bag = own.get(type.id());
            final int count = bag == null ? 0 : bag.count(now); // 剛過期、還沒清掉的不算（跟瞬影擊能不能用一致）
            if (count <= 0) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append('<').append(type.color()).append('>').append(type.symbol()).append("×").append(count).append("</").append(type.color()).append('>');
        }
        return text.toString();
    }

    /** /stats 的「效果」區：每種有的球一行（顆數、效果、還撐多久）。 */
    public List<String> lines(final Player player) {
        final Map<String, ChargeBag> own = bags.get(player.getUniqueId());
        if (own == null) {
            return List.of();
        }
        final long now = plugin.getServer().getCurrentTick();
        final StatSheet sheet = plugin.players().sheet(player);
        final List<String> lines = new ArrayList<>();
        for (final ChargeType type : config.types()) {
            final ChargeBag bag = own.get(type.id());
            final int count = bag == null ? 0 : bag.count(now);
            if (count <= 0) {
                continue;
            }
            final double seconds = Math.max(0, (bag.nextExpiry() - now) / 20.0);
            final List<String> effects = type.describe();
            final String each = effects.isEmpty() ? "" : "每顆：" + escape(String.join("、", effects)) + "；"; // 2026-10：戰星本身預設沒有屬性
            lines.add(String.format(Locale.ROOT, "<%s>%s %d/%d<gray>（%s%.0f 秒後全部消失）", type.color(), escape(type.name()), count,
                    maxOf(type, sheet), each, seconds)); // 2026-10-27 照 PoE2：同一種一起到期（這次之前「秒後掉一顆」）
        }
        return lines;
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }

    // ---- 清掉 ----

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        bags.remove(event.getPlayer().getUniqueId());
        eventReady.remove(event.getPlayer().getUniqueId());
        periodicNext.remove(event.getPlayer().getUniqueId());
        regulationNext.remove(event.getPlayer().getUniqueId());
        rageReady.remove(event.getPlayer().getUniqueId());
    }

    /** 憎鑄：玩家 → 下一次怒氣滿可以給戰星的 tick。 */
    private final Map<UUID, Long> rageReady = new HashMap<>();

    /** 2026-10-24（憎鑄，PoE2 Hateforge）：怒氣滿了：有 charge.on_max_rage（秒）的給一顆隨機戰星，冷卻內不給。 */
    public void onMaxRage(final Player player, final StatSheet sheet) {
        final double seconds = sheet.flat(ON_MAX_RAGE, java.util.Set.of());
        final long now = plugin.getServer().getCurrentTick();
        if (seconds <= 0 || rageReady.getOrDefault(player.getUniqueId(), 0L) > now || config.types().isEmpty()) {
            return;
        }
        rageReady.put(player.getUniqueId(), now + Math.round(seconds * 20));
        final List<ChargeType> all = List.copyOf(config.types());
        if (gainRolled(player, all.get(plugin.items().random().nextInt(all.size())), sheet, 0, 0)) {
            plugin.players().markDirty(player);
        }
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        if (bags.remove(event.getEntity().getUniqueId()) != null) {
            plugin.players().markDirty(event.getEntity());
        }
    }

    public void clearAll() {
        bags.clear();
    }
}
