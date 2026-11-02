package tw.exilecore.client.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 2026-11-02（模組 1.4 B 組）：收到的怪物血條（實體 id → 狀態）。伺服器只送有變化的，這裡記著；完整的包先清掉舊的。
 * 也決定哪些要畫、名牌什麼時候顯示。純 Java（測試用），只在遊戲主執行緒用。
 */
public final class MobBars {

    /** 準心沒指著時，名牌多近才顯示（格）。 */
    public static final double NAME_NEAR = 6;

    private final Map<Integer, WorldCodec.MobBar> bars = new HashMap<>();

    public void apply(final WorldCodec.Mobs packet) {
        if (packet == null) {
            return;
        }
        if (packet.full()) {
            bars.clear();
        }
        for (final int id : packet.removed()) {
            bars.remove(id);
        }
        for (final WorldCodec.MobBar bar : packet.bars()) {
            bars.put(bar.entityId(), bar);
        }
    }

    public void clear() {
        bars.clear();
    }

    public WorldCodec.MobBar get(final int entityId) {
        return bars.get(entityId);
    }

    public boolean has(final int entityId) {
        return bars.containsKey(entityId);
    }

    public Collection<WorldCodec.MobBar> all() {
        return bars.values();
    }

    public int size() {
        return bars.size();
    }

    /**
     * 要不要畫這隻的血條：死了（0）不畫；普通怪滿血、沒有護盾、沒有狀態時不畫（showFull = true 時照畫）；
     * 魔法以上、Boss、準心指著的一定畫。
     */
    public static boolean shows(final WorldCodec.MobBar bar, final boolean aimed, final boolean showFull) {
        if (bar.life() <= 0) {
            return false;
        }
        if (aimed || showFull || bar.rarity() > WorldCodec.RARITY_NORMAL || bar.boss() || bar.invulnerable()) {
            return true;
        }
        return bar.life() < 255 || bar.shield() >= 0 && bar.shield() < 255 || bar.marks() != 0;
    }

    /** 原版名牌要不要藏（有血條的怪：準心指著或 {@link #NAME_NEAR} 格內才顯示）。 */
    public boolean hidesName(final int entityId, final int aimedId, final double distanceSq) {
        return bars.containsKey(entityId) && entityId != aimedId && distanceSq > NAME_NEAR * NAME_NEAR;
    }

    /** 血條的寬（格）：普通 0.8、魔法 0.9、稀有 1.2、傳奇 1.4、Boss 1.8。 */
    public static float width(final WorldCodec.MobBar bar) {
        if (bar.boss()) {
            return 1.8f;
        }
        return switch (bar.rarity()) {
            case WorldCodec.RARITY_MAGIC -> 0.9f;
            case WorldCodec.RARITY_RARE -> 1.2f;
            case WorldCodec.RARITY_UNIQUE -> 1.4f;
            default -> 0.8f;
        };
    }

    /** 生命的顏色（ARGB，照稀有度）：普通紅、魔法藍、稀有金、傳奇橘；無敵灰。 */
    public static int lifeColor(final WorldCodec.MobBar bar) {
        if (bar.invulnerable()) {
            return 0xFF8A8A8A;
        }
        return switch (bar.rarity()) {
            case WorldCodec.RARITY_MAGIC -> 0xFF5A6CFF;
            case WorldCodec.RARITY_RARE -> 0xFFE6C832;
            case WorldCodec.RARITY_UNIQUE -> 0xFFD0702A;
            default -> 0xFFD03030;
        };
    }

    /** 外框（ARGB）：無敵金色、其他照稀有度（普通是黑）。 */
    public static int frameColor(final WorldCodec.MobBar bar) {
        if (bar.invulnerable()) {
            return 0xFFFFD040;
        }
        return switch (bar.rarity()) {
            case WorldCodec.RARITY_MAGIC -> 0xFF8888FF;
            case WorldCodec.RARITY_RARE -> 0xFFFFFF77;
            case WorldCodec.RARITY_UNIQUE -> 0xFFAF6025;
            default -> 0xFF101010;
        };
    }

    /** 小圖示的顏色（ARGB），照 {@link WorldCodec#MARK_ENRAGED}…{@link WorldCodec#MARK_SHOCK} 的順序。 */
    public static final int[] MARK_COLORS = {0xFFFF3A1A, 0xFF5CE0A0, 0xFFB8B8FF, 0xFF40FF60, 0xFFC060FF, 0xFFFF7A1A, 0xFF9FD6FF,
            0xFF55FFFF, 0xFFFFE066};
}
