package tw.exilecore.map;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.clientmod.MiniParts;
import tw.exilecore.clientmod.WorldHudProtocol;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemStats;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：地圖資訊框的內容（{@value WorldHudProtocol#MAP}）：名稱、階級、怪物等級、詞綴、剩幾門／幾次、
 * 刷過與清掉的比例、Boss 狀態、事件與進度。{@link tw.exilecore.clientmod.ClientModService} 每 10 tick 叫一次，有變才送。
 * 這次之前進圖只有 2.2 秒的大標題，詞綴只寫在地圖物品的說明裡、清了多少只有管理指令看得到。
 */
public final class MapHudFeed {

    /** 名稱的顏色（跟進圖的大標題一樣）。 */
    static final int NAME_COLOR = 0x9FD3FF;

    private MapHudFeed() {
    }

    /** 這個人現在所在的地圖的資訊框；不在地圖裡回傳 null（模組收到清除）。 */
    public static WorldHudProtocol.MapPanel panel(final ExileCore plugin, final Player player) {
        final MapService service = plugin.maps();
        if (service == null) {
            return null;
        }
        final MapInstance instance = service.instanceAt(player.getLocation());
        if (instance == null || instance.state() == MapInstance.State.CLOSING) {
            return null;
        }
        final List<List<MiniParts.Part>> mods = new ArrayList<>();
        for (final String text : instance.affixTexts()) {
            for (final String line : text.split("\n")) {
                if (!line.isBlank()) {
                    mods.add(MiniParts.parse(line));
                }
            }
        }
        final int portals = instance.singleEntry() ? -1 : instance.portalsLeft();
        final String unit = instance.usesAttempts() ? "門" : "次";
        int boss = WorldHudProtocol.BOSS_NONE;
        String bossName = "";
        int bossLife = -1;
        final MapConfig.Boss def = instance.bossDef();
        if (def != null) {
            bossName = def.name() == null ? "" : def.name();
            final LivingEntity entity = instance.boss();
            if (instance.bossKilled()) {
                boss = WorldHudProtocol.BOSS_KILLED;
            } else if (entity != null && entity.isValid() && !entity.isDead() && instance.fightStartTick() >= 0) {
                boss = WorldHudProtocol.BOSS_FIGHTING;
                final var max = entity.getAttribute(Attribute.MAX_HEALTH);
                final double top = max == null ? entity.getHealth() : max.getValue();
                bossLife = top <= 0 ? -1 : (int) Math.round(Math.max(0, Math.min(1, entity.getHealth() / top)) * 100);
            } else {
                boss = WorldHudProtocol.BOSS_WAITING;
            }
        }
        final long now = plugin.getServer().getCurrentTick();
        final List<WorldHudProtocol.EventLine> events = instance.bossOnly() ? List.of()
                : service.events().hudLines(instance, player.getUniqueId(), now);
        return new WorldHudProtocol.MapPanel(instance.label(), NAME_COLOR, instance.stats().tier(), instance.level(), mods, portals, unit,
                (int) Math.round(instance.explored() * 100), (int) Math.round(instance.cleared() * 100), boss, bossName, bossLife, events);
    }

    /** 這個人的模組畫地圖資訊框（祭壇、幻霧的進度就不用再另外用血條、動作列寫）。 */
    static boolean drawn(final ExileCore plugin, final Player player) {
        return plugin.clientMod() != null && plugin.clientMod().drawsMapPanel(player);
    }

    /** 地圖物品的詞綴（MiniMessage，照物品的稀有度上色；開圖時抄到實例上）。 */
    static List<String> affixTexts(final ExileCore plugin, final ItemData data) {
        final List<String> out = new ArrayList<>();
        try {
            final String color = data.rarity().color();
            for (final ItemStats.AffixLine line : ItemStats.affixLines(plugin.items().repository(), data)) {
                out.add("<" + color + ">" + line.text());
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().debug("讀不到地圖詞綴：{}", e.toString());
        }
        return out;
    }

    /** MiniMessage 的顏色標籤（「&lt;#9fe8c8&gt;」「gold」）→ 0xRRGGBB；讀不懂用 fallback。 */
    static int color(final String tag, final int fallback) {
        if (tag == null || tag.isBlank()) {
            return fallback;
        }
        final String text = tag.startsWith("<") ? tag : tag.startsWith("#") ? "<" + tag + ">" : "<" + tag + ">";
        final List<MiniParts.Part> parts = MiniParts.parse(text + "x");
        return parts.isEmpty() ? fallback : parts.getFirst().color();
    }
}
