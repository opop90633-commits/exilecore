package tw.exilecore.skill;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家身上有時限的增益（戰吼）：同一個代號再加一次就覆蓋、重新計時。
 * 修飾器透過 {@link tw.exilecore.character.PlayerStateService#addModifierSource} 進屬性表；加上、過期都讓屬性表重算。
 */
final class SkillBuffs {

    /** 一個增益：到哪個 tick 失效、給的修飾器、顯示的名字。 */
    record Buff(String id, String name, long untilTick, List<Modifier> modifiers) {
        boolean active(final long now) {
            return now < untilTick;
        }
    }

    private final ExileCore plugin;
    private final Map<UUID, Map<String, Buff>> buffs = new HashMap<>();

    SkillBuffs(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 加一個增益（同代號覆蓋）。 */
    void apply(final Player player, final String id, final String name, final int ticks, final List<Modifier> modifiers) {
        if (ticks <= 0 || modifiers.isEmpty()) {
            return;
        }
        buffs.computeIfAbsent(player.getUniqueId(), uuid -> new LinkedHashMap<>())
                .put(id, new Buff(id, name, now() + ticks, List.copyOf(modifiers)));
        plugin.players().markDirty(player);
    }

    /** 這個增益現在有沒有效（2026-10：銀月庇的月光要看增益還在不在）。 */
    boolean has(final UUID player, final String id) {
        final Map<String, Buff> mine = buffs.get(player);
        final Buff buff = mine == null ? null : mine.get(id);
        return buff != null && buff.active(now());
    }

    /** 提早拿掉一個增益（2026-10：獸血沸的狂暴提早結束）。 */
    void remove(final Player player, final String id) {
        final Map<String, Buff> mine = buffs.get(player.getUniqueId());
        if (mine != null && mine.remove(id) != null) {
            plugin.players().markDirty(player);
        }
    }

    /** 屬性表的來源：還有效的增益。 */
    List<Modifier> modifiers(final Player player) {
        final Map<String, Buff> mine = buffs.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return List.of();
        }
        final long now = now();
        final List<Modifier> list = new ArrayList<>();
        for (final Buff buff : mine.values()) {
            if (buff.active(now)) {
                list.addAll(buff.modifiers());
            }
        }
        return list;
    }

    /** 還有效的增益（顯示用）。 */
    List<Buff> active(final UUID player) {
        final Map<String, Buff> mine = buffs.get(player);
        if (mine == null) {
            return List.of();
        }
        final long now = now();
        final List<Buff> list = new ArrayList<>();
        for (final Buff buff : mine.values()) {
            if (buff.active(now)) {
                list.add(buff);
            }
        }
        return list;
    }

    /** 每隔一陣子：清掉過期的，有清到就讓那個玩家的屬性表重算。 */
    void tick() {
        if (buffs.isEmpty()) {
            return;
        }
        final long now = now();
        final Iterator<Map.Entry<UUID, Map<String, Buff>>> iterator = buffs.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Map<String, Buff>> entry = iterator.next();
            if (entry.getValue().values().removeIf(buff -> !buff.active(now))) {
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    plugin.players().markDirty(player);
                }
            }
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** 登出、換角色。 */
    void forget(final UUID player) {
        buffs.remove(player);
    }

    void clear() {
        buffs.clear();
    }
}
