package tw.exilecore.monster;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.List;

/** 沒裝 MythicMobs（或版本不相容）時的空實作：所有 mythic: 項目都當作不存在。 */
public enum NoMythic implements MythicHook {
    INSTANCE;

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean exists(final String mobId) {
        return false;
    }

    @Override
    public LivingEntity spawn(final String mobId, final Location location, final int level) {
        return null;
    }

    @Override
    public boolean spawningNow() {
        return false;
    }

    @Override
    public boolean isMythic(final Entity entity) {
        return false;
    }

    @Override
    public String displayName(final Entity entity) {
        return null;
    }

    @Override
    public Collection<String> mobIds() {
        return List.of();
    }
}
