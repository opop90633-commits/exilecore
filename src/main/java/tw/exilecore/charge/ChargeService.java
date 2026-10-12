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
            changed |= gainOne(player, type, sheet);
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
        return chances == null ? Map.of() : chances;
    }

    /** 玩家自己擊中了敵人（召喚物的不算）：照 {@link #hitChances} 算好的機率各擲一次。 */
    public void onDealtHit(final Player player, final Map<String, Double> chances) {
        if (chances.isEmpty() || !player.isOnline() || player.isDead()) {
            return; // 死了之後才打到的投射物不給
        }
        final StatSheet sheet = plugin.players().sheet(player);
        boolean changed = false;
        for (final Map.Entry<String, Double> entry : chances.entrySet()) {
            final ChargeType type = config.type(entry.getKey());
            if (type == null || plugin.items().random().nextDouble() >= entry.getValue()) {
                continue;
            }
            changed |= gainOne(player, type, sheet);
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
            if (gainOne(player, type, sheet)) {
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
            lines.add(String.format(Locale.ROOT, "<%s>%s %d/%d<gray>（每顆：%s；%.0f 秒後掉一顆）", type.color(), escape(type.name()), count,
                    maxOf(type, sheet), escape(String.join("、", type.describe())), seconds));
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
