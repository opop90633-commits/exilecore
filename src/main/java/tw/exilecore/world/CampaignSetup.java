package tw.exilecore.world;

import tw.exilecore.worldgen.CampaignLayout;
import tw.exilecore.zone.ZoneLayout;

import java.util.List;

/**
 * 把劇情世界的座標寫進區域版面（zone-layout.yml）：營地（含海灘、碼頭、押送船）與五個戰鬥區域的範圍、
 * 落點、三個檢查點、Boss 場地。純邏輯，跟區塊生成用同一套 {@link CampaignLayout}，所以點永遠在路面上。
 */
public final class CampaignSetup {

    private CampaignSetup() {
    }

    /**
     * @param layout    要改的版面
     * @param world     劇情世界的名稱
     * @param campId    營地（城鎮）的區域代號；null = 沒有營地
     * @param combatIds 戰鬥區域代號，依 zones.yml 的順序（前五個依序是海岸、森林、廢墟、沼澤、山脈）
     * @return 擺了幾個戰鬥區域（多出來的不擺）
     */
    public static int apply(final ZoneLayout layout, final String world, final String campId, final List<String> combatIds) {
        final int minY = CampaignLayout.FLOOR_Y - 16;
        final int maxY = 319;
        if (campId != null) {
            final ZoneLayout.Entry camp = layout.getOrCreate(campId);
            camp.setBox(ZoneLayout.Box.of(world, CampaignLayout.CAMP_BOX_MIN_X, minY, CampaignLayout.CAMP_BOX_MIN_Z,
                    CampaignLayout.CAMP_BOX_MAX_X, maxY, CampaignLayout.CAMP_BOX_MAX_Z));
            camp.setEntry(point(world, CampaignLayout.campSpawn()));
            camp.clearCheckpoints();
            camp.setArena(null);
        }
        final int count = Math.min(CampaignLayout.ZONE_COUNT, combatIds.size());
        for (int i = 0; i < count; i++) {
            final ZoneLayout.Entry entry = layout.getOrCreate(combatIds.get(i));
            entry.setBox(ZoneLayout.Box.of(world, CampaignLayout.zoneStart(i), minY, CampaignLayout.ZONE_MIN_Z,
                    CampaignLayout.zoneEnd(i), maxY, CampaignLayout.ZONE_MAX_Z));
            entry.setEntry(point(world, CampaignLayout.checkpoint(i, 0)));
            entry.clearCheckpoints();
            entry.putCheckpoint(new ZoneLayout.Checkpoint("entrance", "入口", point(world, CampaignLayout.checkpoint(i, 0))));
            entry.putCheckpoint(new ZoneLayout.Checkpoint("midway", "中途", point(world, CampaignLayout.checkpoint(i, 1))));
            entry.putCheckpoint(new ZoneLayout.Checkpoint("boss", "Boss 前", point(world, CampaignLayout.checkpoint(i, 2))));
            entry.setArena(point(world, CampaignLayout.arena(i)));
        }
        return count;
    }

    /** 版面的營地落點是不是已經在這個世界（擺過了）。 */
    public static boolean applied(final ZoneLayout layout, final String world, final String campId) {
        if (campId == null) {
            return false;
        }
        final ZoneLayout.Entry camp = layout.get(campId);
        return camp != null && camp.entry() != null && world.equals(camp.entry().world());
    }

    static ZoneLayout.Point point(final String world, final CampaignLayout.Spot spot) {
        return new ZoneLayout.Point(world, spot.x(), spot.y(), spot.z(), spot.yaw());
    }
}
