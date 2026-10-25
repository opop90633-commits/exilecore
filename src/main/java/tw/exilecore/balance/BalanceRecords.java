package tw.exilecore.balance;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.DeathRecap;
import tw.exilecore.map.MapInstance;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 平衡報表第二步的兩種紀錄（2026-10-25）：
 * <ul>
 *   <li>死亡（{@code death_records.jsonl}）：誰、幾級、昇華、在哪（地圖與階級、區域）、被什麼打死（最後一下的來源與招式、傷害類型）、
 *       最後幾下合計最多的來源；給報表算死因與每階死亡率。</li>
 *   <li>地圖（{@code map_records.jsonl}）：每張地圖打倒 Boss 時，裡面每個人一筆：地圖、階級、從開圖到 Boss 倒下幾秒、擊殺數、
 *       這張圖裡死了幾次；給報表算清圖效率。</li>
 * </ul>
 */
public final class BalanceRecords {

    public record Death(long at, String player, String uuid, String character, String className, int level, String ascendancy,
                        String where, int tier, String killer, String skill, String type, boolean dot, double maxLife, double maxEs,
                        List<String> lastSources) {
    }

    public record MapRun(long at, String player, String uuid, String character, String className, int level, String ascendancy,
                         String map, int tier, boolean isFinal, int players, double clearSeconds, int kills, int deaths) {
    }

    private static BalanceRecords instance;

    private final ExileCore plugin;
    private final RecordLog<Death> deaths;
    private final RecordLog<MapRun> maps;
    /** 這個實例裡每個人死了幾次（實例關掉就清）。 */
    private final Map<Integer, Map<UUID, Integer>> mapDeaths = new HashMap<>();

    private BalanceRecords(final ExileCore plugin) {
        this.plugin = plugin;
        this.deaths = new RecordLog<>(plugin, "death_records.jsonl", Death.class, 20_000, Death::at);
        this.maps = new RecordLog<>(plugin, "map_records.jsonl", MapRun.class, 20_000, MapRun::at);
    }

    public static synchronized BalanceRecords get(final ExileCore plugin) {
        if (instance == null || instance.plugin != plugin) {
            instance = new BalanceRecords(plugin);
        }
        return instance;
    }

    public List<Death> recentDeaths(final int days) {
        return deaths.recent(days);
    }

    public List<MapRun> recentMaps(final int days) {
        return maps.recent(days);
    }

    // ---- 死亡 ----

    /** 玩家死了（DeathRecap 的 MONITOR 死亡事件呼叫，傳最近的幾下）。 */
    public void death(final Player player, final List<DeathRecap.Hit> hits) {
        final PlayerState state = plugin.players() == null ? null : plugin.players().get(player);
        if (state == null || !state.hasCharacter()) {
            return;
        }
        final MapInstance map = plugin.maps() == null ? null : plugin.maps().instanceOf(player);
        String where;
        int tier = 0;
        if (map != null) {
            where = map.label();
            tier = map.stats() == null ? 0 : map.stats().tier();
            mapDeaths.computeIfAbsent(map.id(), k -> new HashMap<>()).merge(player.getUniqueId(), 1, Integer::sum);
        } else {
            where = plugin.zones() == null ? null : plugin.zones().zoneAt(player.getLocation()).map(tw.exilecore.zone.Zone::name).orElse(null);
            if (where == null) {
                where = player.getWorld().getName();
            }
        }
        final DeathRecap.Hit last = hits.isEmpty() ? null : hits.get(hits.size() - 1);
        String type = null;
        if (last != null) {
            double best = 0;
            for (final Map.Entry<DamageType, Double> e : last.dealt().entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    type = e.getKey().name().toLowerCase(Locale.ROOT);
                }
            }
        }
        final Map<String, Double> bySource = new HashMap<>();
        for (final DeathRecap.Hit hit : hits) {
            bySource.merge(hit.source() == null ? "?" : hit.source(), hit.es() + hit.life(), Double::sum);
        }
        final List<Map.Entry<String, Double>> sources = new ArrayList<>(bySource.entrySet());
        sources.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        final List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sources.size()); i++) {
            top.add(sources.get(i).getKey());
        }
        deaths.add(new Death(System.currentTimeMillis(), player.getName(), player.getUniqueId().toString(), state.character().name(),
                state.characterClass() == null ? null : state.characterClass().displayName(), state.level(), ascendancy(state), where, tier,
                last == null ? null : last.source(), last == null ? null : last.skill(), type, last != null && last.dot(),
                state.maxLife(), state.maxEnergyShield(), top));
    }

    // ---- 地圖 ----

    /** 一張地圖的 Boss 倒了：裡面每個人一筆。 */
    public void mapCompleted(final MapInstance map, final List<Player> players) {
        final double clear = Math.max(1, (plugin.getServer().getCurrentTick() - map.createdTick()) / 20.0);
        final Map<UUID, Integer> deathsHere = mapDeaths.getOrDefault(map.id(), Map.of());
        final long at = System.currentTimeMillis();
        for (final Player player : players) {
            final PlayerState state = plugin.players().get(player);
            if (!state.hasCharacter()) {
                continue;
            }
            maps.add(new MapRun(at, player.getName(), player.getUniqueId().toString(), state.character().name(),
                    state.characterClass() == null ? null : state.characterClass().displayName(), state.level(), ascendancy(state),
                    map.map().name(), map.stats() == null ? 0 : map.stats().tier(), map.map().isFinal(), players.size(), clear, map.kills(),
                    deathsHere.getOrDefault(player.getUniqueId(), 0)));
        }
    }

    /** 實例關掉：清掉它的死亡計數。 */
    public void mapClosed(final int instanceId) {
        mapDeaths.remove(instanceId);
    }

    private String ascendancy(final PlayerState state) {
        if (plugin.ascendancy() == null) {
            return null;
        }
        final tw.exilecore.ascendancy.AscendancyConfig.Ascendancy chosen = plugin.ascendancy().ascension(state).chosen();
        return chosen == null ? null : chosen.name();
    }
}
