package tw.exilecore.combat;

import java.util.Set;

/**
 * Boss 只吃玩家（與玩家的召喚物）的傷害（2026-10）。純函式，不依賴 Bukkit。
 * <p>
 * Boss 的原版血量上限是 1024，真正的生命幾十萬到幾百萬：玩家打的傷害會照比例換算，
 * 但原版自己的傷害（窒息、摔落、爆炸、別的怪物的箭與近戰）沒有經過換算——原版 1 點血等於 Boss 幾百、幾千點生命，
 * 而且怪物沒有受傷後的無敵時間，卡在牆裡每 tick 都扣。所以 Boss 不吃這些，只留虛空、/kill、世界邊界與其他插件的。
 */
public final class BossDamageRules {

    /** 環境傷害裡 Boss 照吃的（掉出世界、指令、世界邊界、其他插件直接打的）。 */
    private static final Set<String> TAKEN = Set.of("VOID", "KILL", "SUICIDE", "WORLD_BORDER", "CUSTOM");

    private BossDamageRules() {
    }

    /** 這種環境傷害（原版 DamageCause 的名字）Boss 吃不吃。 */
    public static boolean takesEnvironment(final String cause) {
        return TAKEN.contains(cause);
    }
}
