package tw.exilecore.monster;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;

/**
 * 跟 MythicMobs 溝通的介面。沒裝 MythicMobs 時是 {@link NoMythic}（什麼都不做），
 * 裝了就是 {@link MythicBridge}。其他模組只認這個介面，不直接碰 MythicMobs 的類別。
 */
public interface MythicHook {

    /** MythicMobs 有裝而且連接成功。 */
    boolean available();

    /** MythicMobs 裡有沒有這個怪物代號。 */
    boolean exists(String mobId);

    /**
     * 生成一隻 MythicMobs 怪物，失敗回傳 null。
     * 這條路徑生成的怪物不會被自動等級化，呼叫端要自己呼叫 {@link MonsterService#decorate}。
     */
    LivingEntity spawn(String mobId, Location location, int level);

    /** 現在是不是正在生成 MythicMobs 怪物（區域擋原版生成時要放行）。 */
    boolean spawningNow();

    /** 這隻是不是 MythicMobs 怪物。 */
    boolean isMythic(Entity entity);

    /** MythicMobs 怪物的名字（去掉顏色）；不是 MythicMobs 怪物回傳 null。 */
    String displayName(Entity entity);

    /** MythicMobs 目前載入的所有怪物代號。 */
    Collection<String> mobIds();
}
