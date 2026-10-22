package tw.exilecore.quest;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.zone.Zone;
import tw.exilecore.zone.ZoneLayout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 劇情人物在劇情世界裡的擺放：quests.yml 的 place（營地落點或區域檢查點的相對位置）。
 * 那個區塊的實體載入時檢查一次——沒有就放一個、多的拿掉、跑掉的移回去，所以舊世界、新世界都一樣。
 * 只管擺在劇情世界的版面（落點、檢查點在劇情世界裡）；手動擺版面的伺服器用 /ec npc story。
 */
final class StoryNpcs implements Listener {

    private final ExileCore plugin;
    private final QuestService service;
    private final World campaign;

    StoryNpcs(final ExileCore plugin, final QuestService service, final World campaign) {
        this.plugin = plugin;
        this.service = service;
        this.campaign = campaign;
    }

    /** 劇情人物 → 站的位置（只有擺得出來的；劇情世界）。 */
    Map<String, Location> spots() {
        return spots(campaign);
    }

    /**
     * 劇情人物在這個世界的站位。分流的主城世界（2026-10-22）只擺營地（camp）的人物——
     * 區域檢查點的人物只在劇情世界。座標照劇情世界的版面算，分流世界是整塊複製的所以一樣。
     */
    Map<String, Location> spots(final World world) {
        final Map<String, Location> spots = new LinkedHashMap<>();
        if (plugin.zones() == null || world == null) {
            return spots;
        }
        final boolean channel = !world.equals(campaign);
        for (final QuestConfig.Npc npc : service.config().npcs().values()) {
            final QuestConfig.Placement place = npc.placement();
            if (place == null) {
                continue;
            }
            final ZoneLayout.Point anchor;
            if (QuestConfig.CAMP.equals(place.zone())) {
                anchor = plugin.zones().camp().map(zone -> zone.layout().entry()).orElse(null);
            } else if (channel) {
                continue;
            } else {
                final Zone zone = plugin.zones().zone(place.zone()).orElse(null);
                final ZoneLayout.Checkpoint checkpoint = zone == null ? null : zone.layout().checkpoint(place.checkpoint());
                anchor = checkpoint == null ? null : checkpoint.point();
            }
            if (anchor == null || !anchor.world().equals(campaign.getName())) {
                continue;
            }
            spots.put(npc.id(), new Location(world, anchor.x() + place.x(), anchor.y() + place.y(), anchor.z() + place.z(), place.yaw(), 0f));
        }
        return spots;
    }

    /** 劇情世界 + 分流的主城世界。 */
    private List<World> worlds() {
        final List<World> worlds = new ArrayList<>();
        worlds.add(campaign);
        if (plugin.world() != null && plugin.world().worlds() != null) {
            worlds.addAll(plugin.world().worlds().channels().towns());
        }
        return worlds;
    }

    private boolean handles(final World world) {
        if (world.equals(campaign)) {
            return true;
        }
        return plugin.world() != null && plugin.world().worlds() != null && plugin.world().worlds().channels().isChannel(world);
    }

    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        if (plugin.npcs() == null || !handles(event.getWorld())) {
            return;
        }
        final Chunk chunk = event.getChunk();
        final Map<String, Location> spots = spots(event.getWorld());
        final Map<String, Location> mine = new LinkedHashMap<>();
        for (final Map.Entry<String, Location> entry : spots.entrySet()) {
            if (inChunk(entry.getValue(), chunk)) {
                mine.put(entry.getKey(), entry.getValue());
            }
        }
        // 不該在這裡的劇情人物：設定檔裡已經沒有、或位置改到別的區塊了（舊的那一個）
        final List<Entity> strays = new ArrayList<>();
        for (final Entity entity : event.getEntities()) {
            final String id = plugin.npcs().storyId(entity);
            if (id == null) {
                continue;
            }
            final Location spot = spots.get(id);
            if (service.config().npc(id) == null || (spot != null && !inChunk(spot, chunk))) {
                strays.add(entity);
            }
        }
        if (mine.isEmpty() && strays.isEmpty()) {
            return;
        }
        // 下一個 tick 再處理（不在載入事件裡生成、移除實體）
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (final Entity stray : strays) {
                if (stray.isValid()) {
                    stray.remove();
                }
            }
            if (chunk.isLoaded() && chunk.isEntitiesLoaded()) {
                for (final Map.Entry<String, Location> entry : mine.entrySet()) {
                    ensure(entry.getKey(), entry.getValue(), List.of(chunk.getEntities()));
                }
            }
        });
    }

    private static boolean inChunk(final Location spot, final Chunk chunk) {
        return spot.getBlockX() >> 4 == chunk.getX() && spot.getBlockZ() >> 4 == chunk.getZ();
    }

    /** 啟動、重讀設定時：已經載入的區塊先做一次。 */
    void applyLoaded() {
        for (final World world : worlds()) {
            for (final Map.Entry<String, Location> entry : spots(world).entrySet()) {
                final Location spot = entry.getValue();
                final int chunkX = spot.getBlockX() >> 4;
                final int chunkZ = spot.getBlockZ() >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                final Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (chunk.isEntitiesLoaded()) {
                    ensure(entry.getKey(), spot, List.of(chunk.getEntities()));
                }
            }
        }
    }

    /** 這個區塊裡：沒有就放一個、多的拿掉、跑掉的移回去、名字跟上設定檔。 */
    private void ensure(final String id, final Location spot, final Collection<Entity> entities) {
        final QuestConfig.Npc definition = service.config().npc(id);
        if (definition == null || plugin.npcs() == null) {
            return;
        }
        final List<Entity> found = new ArrayList<>();
        for (final Entity entity : entities) {
            if (entity.isValid() && id.equals(plugin.npcs().storyId(entity))) {
                found.add(entity);
            }
        }
        if (found.isEmpty()) {
            plugin.npcs().spawnStory(spot, definition);
            return;
        }
        final Entity keep = found.getFirst();
        for (int i = 1; i < found.size(); i++) {
            found.get(i).remove();
        }
        if (keep.getLocation().distanceSquared(spot) > 0.5) {
            keep.teleport(spot);
        }
        plugin.npcs().dressStory(keep, definition);
    }
}
