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
 * 球不存檔：登出、死亡就清掉（撐 10 秒的東西）。
 */
public final class ChargeService implements Listener {

    /** 多久檢查一次過期（tick）。 */
    public static final long TICK_INTERVAL = 10;

    private final ExileCore plugin;
    private final ChargeConfig config;
    private final Map<UUID, Map<String, ChargeBag>> bags = new HashMap<>();
    /** 事件給戰星的冷卻（2026-10）：玩家 → 事件 → 下一次可以給的 tick。 */
    private final Map<UUID, Map<String, Long>> eventReady = new HashMap<>();
    /** 定期給戰星（2026-10，星律）：玩家 → 下一次給的 tick。 */
    private final Map<UUID, Long> periodicNext = new HashMap<>();

    /** 屬性鍵（2026-10）：某個事件給一顆戰星的機率，FLAT 百分比點數；charge.event.事件.代號（例如 charge.event.stun_applied.endurance）。 */
    public static final String EVENT = "charge.event.";
    /** 屬性鍵（2026-10）：某個事件給戰星的冷卻，FLAT 秒；charge.event_cooldown.事件。 */
    public static final String EVENT_COOLDOWN = "charge.event_cooldown.";
    /** 屬性鍵（2026-10）：獲得一顆戰星時多一顆同種的機率，FLAT 百分比點數；charge.extra（任何種類）或 charge.extra.代號。 */
    public static final String EXTRA = "charge.extra";
    /** 屬性鍵（2026-10）：每分鐘給幾顆戰星（照最高的能力值：力量 → 磐星、敏捷 → 怒星、智慧 → 銳星），FLAT；好幾個來源加起來。 */
    public static final String PERIODIC = "charge.periodic";
    /** 撲殺：稀有怪多給一顆、傳奇怪多給兩顆（照 PoE2）。 */
    static final java.util.Set<String> RARITY_BONUS_EVENTS = java.util.Set.of("cull");

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
            changed |= gainRolled(player, type, sheet, 0);
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
        for (final ChargeType type : config.types()) {
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
        }
        return chances == null ? Map.of() : chances;
    }

    /** 玩家自己擊中了敵人（召喚物的不算）：照 {@link #hitChances} 算好的機率各擲一次。 */
    public void onDealtHit(final Player player, final Map<String, Double> chances) {
        if (chances.isEmpty() || !player.isOnline() || player.isDead()) {
            return; // 死了之後才打到的投射物不給
        }
        final StatSheet sheet = plugin.players().sheet(player);
        // 技能屬性表的 charge.extra 含玩家本身的（gainRolled 會再加一次），只留輔助寶石給的那份
        final double localExtra = Math.max(0, chances.getOrDefault(EXTRA, 0.0) - sheet.flat(EXTRA, java.util.Set.of()) / 100.0);
        boolean changed = false;
        for (final Map.Entry<String, Double> entry : chances.entrySet()) {
            final ChargeType type = config.type(entry.getKey());
            if (type == null || plugin.items().random().nextDouble() >= entry.getValue()) {
                continue;
            }
            changed |= gainRolled(player, type, sheet, localExtra);
        }
        if (changed) {
            plugin.players().markDirty(player);
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
            if (gainRolled(player, type, sheet, 0)) {
                grew++;
            }
        }
        plugin.players().markDirty(player); // 滿了也刷新了時間；顆數有變就要重算
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
        plugin.players().markDirty(player); // 屬性表重算：少了一顆的效果
        return true;
    }

    /** 獲得一顆；回傳顆數有沒有變多（滿了只刷新時間 = false）。 */
    private boolean gainOne(final Player player, final ChargeType type, final StatSheet sheet) {
        final ChargeBag bag = bags.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>()).computeIfAbsent(type.id(), id -> new ChargeBag());
        return bag.gain(plugin.getServer().getCurrentTick(), maxOf(type, sheet), durationTicks(type, sheet));
    }

    /**
     * 獲得一顆，再擲「多一顆同種的」（2026-10，照 PoE2 的「當你獲得一顆 X 時，有 N% 機率多一顆」）：
     * 機率 = 屬性表的 charge.extra 與 charge.extra.代號（百分比點數）＋ localExtra（輔助寶石只對被輔助的技能，0–1）。多出來的那顆不會再擲。
     */
    private boolean gainRolled(final Player player, final ChargeType type, final StatSheet sheet, final double localExtra) {
        boolean grew = gainOne(player, type, sheet);
        final double extra = (sheet.flat(EXTRA, java.util.Set.of()) + sheet.flat(EXTRA + "." + type.id(), java.util.Set.of())) / 100.0 + localExtra;
        if (extra > 0 && plugin.items().random().nextDouble() < Math.min(1, extra)) {
            grew |= gainOne(player, type, sheet);
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
        boolean changed = false;
        boolean gave = false;
        for (final ChargeType type : config.types()) {
            final double chance = source.flat(EVENT + event + "." + type.id(), tags) / 100.0;
            if (chance <= 0 || plugin.items().random().nextDouble() >= Math.min(1, chance)) {
                continue;
            }
            gave = true;
            for (int i = 0; i <= bonus; i++) {
                changed |= gainRolled(player, type, sheet, Math.max(0, localExtra));
            }
        }
        if (gave) {
            final double cooldown = source.flat(EVENT_COOLDOWN + event, tags);
            if (cooldown > 0) {
                ready.put(event, now + Math.round(cooldown * 20));
            }
        }
        if (changed) {
            plugin.players().markDirty(player);
        }
    }

    /** 定期給的戰星（2026-10，星律）：屬性表有 charge.periodic（每分鐘幾顆）的人，時間到就照最高的能力值給一顆。 */
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
            if (type != null && gainRolled(player, type, sheet, 0)) {
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
        periodic(now); // 2026-10：星律（定期給戰星）
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
            lines.add(String.format(Locale.ROOT, "<%s>%s %d/%d<gray>（%s%.0f 秒後掉一顆）", type.color(), escape(type.name()), count,
                    maxOf(type, sheet), each, seconds));
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
