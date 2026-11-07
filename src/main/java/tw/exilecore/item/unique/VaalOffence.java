package tw.exilecore.item.unique;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-07 照站長「瓦爾傳奇照原作」：瓦爾傳奇（新的 18 件與瓦爾詞綴）要記狀態的進攻效果。戰鬥模組啟用時建立（{@link tw.exilecore.combat.CombatModule}）。
 * <ul>
 *   <li>{@link VaalKeys#CRIT_BONUS_PER_RECENT_SPELL_CRIT}：最近 4 秒每用法術暴擊一次，法術的暴擊傷害加成減少 x%（災變之種）——
 *       這裡記次數，CombatListener 打中之前疊上去（{@link #critBonus}）。</li>
 *   <li>{@link VaalKeys#KILL_EXPLODE_CRIT}：要知道致命一擊是不是暴擊（{@link #critKill}，KillEffects 問）。</li>
 *   <li>噬魂者（{@link VaalKeys#FLAG_SOUL_EATER}、{@link VaalKeys#SOUL_EATER_ON_UNIQUE_HIT}）：靈魂層數、衰退、技能速度（屬性表的增益來源）。</li>
 *   <li>皮囊偶符（{@link VaalKeys#SHAPESHIFT_COPY_MODS}）：變形成野獸型態時複製臨在範圍內敵人的詞綴（SkillService 變形時呼叫 {@link #shapeshifted}）。</li>
 * </ul>
 */
public final class VaalOffence implements Listener {

    /** 每幾 tick 檢查一次（噬魂者的衰退）。 */
    public static final long PERIOD = 10;
    /** 噬魂者：最多幾個靈魂、每個靈魂增加多少技能速度（小數）、多久沒吞到開始衰退、之後每幾 tick 少一個（照物品上的文字：50、1%、4 秒、0.5 秒）。 */
    public static final int SOUL_MAX = 50;
    public static final double SOUL_SPEED = 0.01;
    public static final long SOUL_DECAY_AFTER = StatKeys.RECENTLY_TICKS;
    public static final long SOUL_DECAY_EVERY = 10;
    /** 致命一擊：死亡之前多少 tick 內的那一下算（擊中跟死亡同一個 tick，多留一點給投射物）。 */
    static final long KILL_WINDOW = 1;

    private final ExileCore plugin;
    /** 玩家 → 最近幾次法術暴擊的 tick（舊的在前面）。 */
    private final Map<UUID, Deque<Long>> spellCrits = new HashMap<>();
    /** 敵人 → 玩家最後一下打中牠的那一下。 */
    private final Map<UUID, LastHit> lastHit = new HashMap<>();

    /** 最後一下：誰打的、哪個 tick、是不是暴擊。 */
    record LastHit(UUID player, long tick, boolean crit) {
    }
    /** 噬魂者的靈魂。 */
    private final Map<UUID, Souls> souls = new HashMap<>();
    /** 「擊中傳奇敵人時吞噬一個靈魂」下一次可以吞的 tick。 */
    private final Map<UUID, Long> uniqueHitReady = new HashMap<>();
    /** 皮囊偶符複製來的詞綴（增益代號），下一次變形時拿掉。 */
    private final Map<UUID, List<String>> copied = new HashMap<>();

    /** 靈魂層數；last = 最後一次吞的 tick，nextDecay = 下一次少一個的 tick。 */
    static final class Souls {
        int count;
        long last;
        long nextDecay;
    }

    public VaalOffence(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 最近的法術暴擊 ----

    /** 最近（4 秒內）有幾次法術暴擊；順便丟掉太舊的。純函式（測試用）。 */
    static int recentCount(final Deque<Long> ticks, final long now) {
        if (ticks == null) {
            return 0;
        }
        while (!ticks.isEmpty() && now - ticks.peekFirst() > StatKeys.RECENTLY_TICKS) {
            ticks.pollFirst();
        }
        return ticks.size();
    }

    /**
     * 打中之前（CombatListener 呼叫）：最近每用法術暴擊一次，法術的暴擊傷害加成減少 x%（INCREASED，帶 [spell]，最多減到 0）。
     * 值是每一次的量（物品寫 -5）；這一下自己不算。
     */
    public List<Modifier> critBonus(final Player player, final StatSheet sheet, final Set<String> hitTags) {
        final double per = sheet.increased(VaalKeys.CRIT_BONUS_PER_RECENT_SPELL_CRIT, hitTags);
        if (per == 0) {
            return List.of();
        }
        final int count = recentCount(spellCrits.get(player.getUniqueId()), now());
        return count <= 0 ? List.of()
                : List.of(new Modifier(StatKeys.CRIT_BONUS, ModType.INCREASED, per * count, Set.of(Tags.SPELL), "最近的法術暴擊 ×" + count));
    }

    // ---- 致命一擊是不是暴擊 ----

    /** 殺死這隻敵人的那一下是不是這個玩家的暴擊擊中（死亡處理裡問；死亡事件最後 {@link #onDeath} 才忘掉）。 */
    public boolean critKill(final Player killer, final LivingEntity victim) {
        return wasCritKill(lastHit.get(victim.getUniqueId()), killer.getUniqueId(), now());
    }

    /** 純函式（測試用）：最後一下是這個玩家在 {@value #KILL_WINDOW} tick 內打的暴擊。 */
    static boolean wasCritKill(final LastHit hit, final UUID killer, final long now) {
        return hit != null && hit.crit() && hit.player().equals(killer) && now - hit.tick() <= KILL_WINDOW;
    }

    // ---- 事件 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        if (!(event.attacker() instanceof Player player) || event.defender() instanceof Player || !result.hit() || result.blocked()) {
            return;
        }
        final long now = now();
        lastHit.put(event.defender().getUniqueId(), new LastHit(player.getUniqueId(), now, result.crit()));
        if (lastHit.size() > 4096) {
            lastHit.entrySet().removeIf(entry -> now - entry.getValue().tick() > 20); // 沒死的別一直留著
        }
        if (result.crit() && event.skill() != null && event.skill().tags().contains(Tags.SPELL)) {
            spellCrits.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>()).addLast(now);
        }
        // 擊中傳奇敵人時吞噬一個靈魂（每 x 秒最多一次）
        final StatSheet sheet = plugin.players().sheet(player);
        final double cooldown = sheet.flat(VaalKeys.SOUL_EATER_ON_UNIQUE_HIT, Set.of());
        if (cooldown > 0 && plugin.monsters() != null) {
            final MonsterData data = plugin.monsters().data(event.defender()).orElse(null);
            if (data != null && (data.boss() || data.rarity() == MonsterRarity.UNIQUE)
                    && uniqueHitReady.getOrDefault(player.getUniqueId(), 0L) <= now) {
                uniqueHitReady.put(player.getUniqueId(), now + Math.max(1, Math.round(cooldown * 20)));
                eat(player, now);
            }
        }
    }

    /** 敵人死掉：臨在範圍內有噬魂者的玩家吞一個靈魂。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        if (victim instanceof Player || plugin.monsters() == null || plugin.monsters().data(victim).isEmpty()) {
            return;
        }
        lastHit.remove(victim.getUniqueId());
        final long now = now();
        for (final Player player : victim.getWorld().getPlayers()) {
            if (player.isDead() || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            if (!StatKeys.flag(sheet, VaalKeys.FLAG_SOUL_EATER)) {
                continue;
            }
            final double radius = tw.exilecore.combat.Presence.radius(sheet);
            if (player.getLocation().distanceSquared(victim.getLocation()) <= radius * radius) {
                eat(player, now);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(final PlayerDeathEvent event) {
        if (souls.remove(event.getEntity().getUniqueId()) != null) {
            plugin.players().markDirty(event.getEntity());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        spellCrits.remove(id);
        souls.remove(id);
        uniqueHitReady.remove(id);
        copied.remove(id);
    }

    // ---- 噬魂者 ----

    /**
     * 吞一個靈魂（最多 {@value #SOUL_MAX} 個），重新計時衰退。
     * 「擊中傳奇敵人時吞噬一個靈魂」沒有噬魂者那條也吞（靈魂的效果照樣有：原作是同一個增益）。
     */
    private void eat(final Player player, final long now) {
        final Souls own = souls.computeIfAbsent(player.getUniqueId(), id -> new Souls());
        final int before = own.count;
        eat(own, now);
        if (own.count != before) {
            plugin.players().markDirty(player);
        }
    }

    /** 純函式（測試用）：吞一個。 */
    static void eat(final Souls own, final long now) {
        own.count = Math.min(SOUL_MAX, own.count + 1);
        own.last = now;
        own.nextDecay = now + SOUL_DECAY_AFTER;
    }

    /** 純函式（測試用）：到 now 為止衰退；回傳少了幾個。4 秒沒吞到之後每 0.5 秒少一個。 */
    static int decay(final Souls own, final long now) {
        int lost = 0;
        while (own.count > 0 && now >= own.nextDecay) {
            own.count--;
            own.nextDecay += SOUL_DECAY_EVERY;
            lost++;
        }
        return lost;
    }

    /** 現在有幾個靈魂（側欄、測試用）。 */
    public int souls(final Player player) {
        final Souls own = souls.get(player.getUniqueId());
        return own == null ? 0 : own.count;
    }

    /** 屬性表的來源（增益）：每個靈魂增加 1% 攻擊與施法速度。 */
    public List<Modifier> modifiers(final Player player) {
        final Souls own = souls.get(player.getUniqueId());
        if (own == null || own.count <= 0) {
            return List.of();
        }
        final String source = "噬魂者 ×" + own.count;
        return List.of(Modifier.increased(StatKeys.ATTACK_SPEED, own.count * SOUL_SPEED, source),
                Modifier.increased(StatKeys.CAST_SPEED, own.count * SOUL_SPEED, source));
    }

    /** 每 {@value #PERIOD} tick：靈魂衰退。 */
    public void tick() {
        final long now = now();
        for (final Map.Entry<UUID, Souls> entry : new ArrayList<>(souls.entrySet())) {
            if (decay(entry.getValue(), now) > 0) {
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    plugin.players().markDirty(player);
                }
            }
            if (entry.getValue().count <= 0) {
                souls.remove(entry.getKey());
            }
        }
        spellCrits.values().removeIf(ticks -> recentCount(ticks, now) == 0);
        lastHit.values().removeIf(hit -> now - hit.tick() > 40); // 2026-11-07：沒死就不見的怪（離開、被移除）不要一直留著
    }

    // ---- 皮囊偶符 ----

    /**
     * 變形了（SkillService.shapeshift；changed = 真的換了型態，同一個型態再放一次不算變形）：
     * 先拿掉上一次複製來的詞綴（「下一次變形時失去」），再從臨在範圍內每一個有詞綴的敵人身上各複製一條隨機詞綴，
     * {@link VaalKeys#SHAPESHIFT_COPY_MODS_DURATION} 秒後失去。我們的變形技能都是變成野獸型態。
     */
    public void shapeshifted(final Player player, final boolean changed) {
        if (!changed || plugin.skills() == null) {
            return;
        }
        final List<String> previous = copied.remove(player.getUniqueId());
        if (previous != null) {
            for (final String id : previous) {
                plugin.skills().removeBuff(player, id);
            }
        }
        final StatSheet sheet = plugin.players().sheet(player);
        if (!StatKeys.flag(sheet, VaalKeys.SHAPESHIFT_COPY_MODS) || plugin.monsters() == null) {
            return;
        }
        final double seconds = sheet.flat(VaalKeys.SHAPESHIFT_COPY_MODS_DURATION, Set.of());
        final int ticks = (int) Math.max(1, Math.round((seconds > 0 ? seconds : 30) * 20));
        final double radius = tw.exilecore.combat.Presence.radius(sheet);
        final List<String> ids = new ArrayList<>();
        for (final LivingEntity target : player.getWorld().getNearbyLivingEntities(player.getLocation(), radius)) {
            if (target instanceof Player || target.isDead() || target.getLocation().distanceSquared(player.getLocation()) > radius * radius) {
                continue;
            }
            final MonsterData data = plugin.monsters().data(target).orElse(null);
            if (data == null || data.mods().isEmpty()) {
                continue;
            }
            final List<String> pool = new ArrayList<>(data.mods());
            java.util.Collections.shuffle(pool, new java.util.Random(plugin.items().random().nextLong()));
            for (final String modId : pool) {
                final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(modId);
                final List<Modifier> modifiers = mod == null ? List.of()
                        : tw.exilecore.combat.KillEffects.stolenModifiers(mod, "皮囊偶符：" + mod.name());
                if (modifiers.isEmpty()) {
                    continue; // 只有招式、沒有數值的詞綴換下一條
                }
                final String id = "vaal.copy." + ids.size();
                plugin.skills().buff(player, id, mod.name() + "（皮囊偶符）", ticks, modifiers);
                ids.add(id);
                break;
            }
        }
        if (!ids.isEmpty()) {
            copied.put(player.getUniqueId(), ids);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#d27a5a>✦ 皮囊偶符：複製了 " + ids.size() + " 條詞綴（"
                    + Math.round(ticks / 20.0) + " 秒）"));
        }
    }
}
