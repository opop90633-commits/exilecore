package tw.exilecore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;

/**
 * 2026-11-05（模組 1.5.0）：自己的德魯伊變形外觀。伺服器把每個人的外觀放進隊伍「ecc_＋UUID（沒有 -）」，
 * 隊伍是自己的就是自己的變形：第一人稱不畫，第三人稱照畫（伺服器那邊本人是隱形的，外觀就是自己的樣子）。
 */
public final class CostumeView {

    /** 跟伺服器的 ShapeshiftCostumes.OWN_TEAM_PREFIX 一樣。 */
    static final String TEAM_PREFIX = "ecc_";

    private CostumeView() {
    }

    public static boolean hidden(final Entity entity) {
        if (!ExileCoreClient.connected) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == mc.player || !mc.options.getCameraType().isFirstPerson()) {
            return false;
        }
        final PlayerTeam team = entity.getTeam();
        return team != null && team.getName().equals(teamOf(mc.player.getUUID()));
    }

    static String teamOf(final java.util.UUID owner) {
        return TEAM_PREFIX + owner.toString().replace("-", "");
    }
}
