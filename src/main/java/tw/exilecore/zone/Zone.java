package tw.exilecore.zone;

import java.util.Collection;
import java.util.List;

/**
 * 一個區域：遊戲內容（{@link ZoneConfig.ZoneDefinition}）加上它在世界裡的位置（{@link ZoneLayout.Entry}）。
 * 純資料，不依賴 Bukkit，等級的計算可以直接寫單元測試。
 */
public final class Zone {

    private final ZoneConfig.ZoneDefinition definition;
    private final ZoneLayout.Entry layout;

    public Zone(final ZoneConfig.ZoneDefinition definition, final ZoneLayout.Entry layout) {
        this.definition = definition;
        this.layout = layout == null ? new ZoneLayout.Entry() : layout;
    }

    public ZoneConfig.ZoneDefinition definition() {
        return definition;
    }

    public ZoneLayout.Entry layout() {
        return layout;
    }

    public String id() {
        return definition.id();
    }

    public String name() {
        return definition.name();
    }

    public boolean town() {
        return definition.town();
    }

    public int minLevel() {
        return definition.minLevel();
    }

    public int maxLevel() {
        return definition.maxLevel();
    }

    public List<ZoneConfig.SpawnEntry> monsters() {
        return definition.monsters();
    }

    public ZoneConfig.BossDefinition boss() {
        return definition.boss();
    }

    /** 範圍擺好了沒（沒擺的區域在世界裡不存在）。 */
    public boolean placed() {
        return layout.box() != null;
    }

    public boolean contains(final String world, final double x, final double y, final double z) {
        return layout.box() != null && layout.box().contains(world, x, y, z);
    }

    public Collection<ZoneLayout.Checkpoint> checkpoints() {
        return layout.checkpoints();
    }

    /**
     * 世界觀文字的顯示格式（MiniMessage）：「」開頭的是角色說的話（名字：台詞），其他是斜體的旁白。
     */
    public static String loreLine(final String speaker, final String text) {
        final String escaped = text.replace("<", "\\<");
        if (text.startsWith("「") && speaker != null) {
            return "<#af6025>" + speaker.replace("<", "\\<") + "</#af6025><gray>：</gray><white>" + escaped;
        }
        return "<gray><i>" + escaped + "</i>";
    }

    /** 「等級 1–12」這種文字。 */
    public String levelText() {
        return minLevel() == maxLevel() ? "等級 " + minLevel() : "等級 " + minLevel() + "–" + maxLevel();
    }

    /**
     * 這個位置的怪物等級：從入口（最低等級）往 Boss 場地（最高等級）線性增加，只看水平距離。
     * 沒有入口時從範圍中心算；沒有 Boss 場地時，終點是離起點最遠的範圍角落。
     */
    public int levelAt(final double x, final double z) {
        if (town() || minLevel() >= maxLevel()) {
            return minLevel();
        }
        final double startX;
        final double startZ;
        if (layout.entry() != null) {
            startX = layout.entry().x();
            startZ = layout.entry().z();
        } else if (layout.box() != null) {
            startX = (layout.box().minX() + layout.box().maxX() + 1) / 2.0;
            startZ = (layout.box().minZ() + layout.box().maxZ() + 1) / 2.0;
        } else {
            return minLevel();
        }
        final double full;
        if (layout.arena() != null) {
            full = Math.hypot(layout.arena().x() - startX, layout.arena().z() - startZ);
        } else if (layout.box() != null) {
            full = layout.box().farthestCorner2d(startX, startZ);
        } else {
            return minLevel();
        }
        if (full < 1) {
            return maxLevel();
        }
        final double t = Math.max(0, Math.min(1, Math.hypot(x - startX, z - startZ) / full));
        return (int) Math.round(minLevel() + (maxLevel() - minLevel()) * t);
    }
}
