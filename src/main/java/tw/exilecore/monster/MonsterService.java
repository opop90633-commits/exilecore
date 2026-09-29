package tw.exilecore.monster;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 怪物的等級化：決定等級與稀有度、寫進實體 PDC、改名、把原版血量與護甲換成我們的數值。
 * 階段 1 對象是原版生物；MythicMobs 橋接在下一批加進來時，只是多一個「誰來決定等級」的入口。
 */
public final class MonsterService {

    private final ExileCore plugin;
    private final MonsterConfig config;
    private final RandomGenerator random;
    private final NamespacedKey levelKey;
    private final NamespacedKey rarityKey;
    private final NamespacedKey modsKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey speedKey;
    private final Map<UUID, StatSheet> sheets = new HashMap<>();

    public MonsterService(final ExileCore plugin, final MonsterConfig config, final RandomGenerator random) {
        this.plugin = plugin;
        this.config = config;
        this.random = random;
        this.levelKey = new NamespacedKey(plugin, "monster_level");
        this.rarityKey = new NamespacedKey(plugin, "monster_rarity");
        this.modsKey = new NamespacedKey(plugin, "monster_mods");
        this.nameKey = new NamespacedKey(plugin, "monster_name");
        this.speedKey = new NamespacedKey(plugin, "monster_speed");
    }

    public MonsterConfig config() {
        return config;
    }

    /** 這隻是不是我們等級化過的怪物。 */
    public boolean isMonster(final LivingEntity entity) {
        return !(entity instanceof Player) && entity.getPersistentDataContainer().has(levelKey);
    }

    public Optional<MonsterData> data(final LivingEntity entity) {
        final PersistentDataContainer container = entity.getPersistentDataContainer();
        final Integer level = container.get(levelKey, PersistentDataType.INTEGER);
        if (level == null) {
            return Optional.empty();
        }
        final MonsterRarity rarity = MonsterRarity.parse(container.get(rarityKey, PersistentDataType.STRING), MonsterRarity.NORMAL);
        final String modsText = container.get(modsKey, PersistentDataType.STRING);
        final List<String> mods = new ArrayList<>();
        if (modsText != null && !modsText.isBlank()) {
            for (final String id : modsText.split(",")) {
                mods.add(id.trim());
            }
        }
        final String name = container.get(nameKey, PersistentDataType.STRING);
        return Optional.of(new MonsterData(level, rarity, mods, name == null ? typeName(entity) : name));
    }

    /** 這隻怪物的屬性表（快取；死亡時清掉）。 */
    public StatSheet sheet(final LivingEntity entity, final MonsterData data) {
        return sheets.computeIfAbsent(entity.getUniqueId(), uuid -> MonsterSheets.build(config, data, entity.getType().name()));
    }

    public void forget(final UUID uuid) {
        sheets.remove(uuid);
    }

    /** 依位置決定等級（階段 2 改成區域）。 */
    public int levelFor(final Location location) {
        final double distance = location.distance(location.getWorld().getSpawnLocation());
        return config.levelForDistance(distance);
    }

    /** 自然生成：抽稀有度與詞綴後裝飾。 */
    public MonsterData decorateNatural(final LivingEntity entity, final int level) {
        final MonsterRarity rarity = config.rollRarity(random);
        return decorate(entity, level, rarity, null);
    }

    /**
     * 把一隻生物變成我們的怪物。{@code mods} 為 null 時依稀有度隨機抽。
     */
    public MonsterData decorate(final LivingEntity entity, final int level, final MonsterRarity rarity, final List<String> mods) {
        final MonsterConfig.RaritySettings settings = config.rarity(rarity);
        final List<String> chosenMods = mods != null ? mods : rollMods(settings.minMods(), settings.maxMods());
        final String baseName = typeName(entity);
        final String name = rarity == MonsterRarity.RARE ? rareName(baseName) : baseName;
        final MonsterData data = new MonsterData(level, rarity, chosenMods, name);

        final PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(levelKey, PersistentDataType.INTEGER, level);
        container.set(rarityKey, PersistentDataType.STRING, rarity.name());
        container.set(modsKey, PersistentDataType.STRING, String.join(",", chosenMods));
        container.set(nameKey, PersistentDataType.STRING, name);
        sheets.remove(entity.getUniqueId());

        final StatSheet sheet = sheet(entity, data);
        applyVanilla(entity, data, sheet);
        entity.customName(displayName(data));
        entity.setCustomNameVisible(true);
        return data;
    }

    private List<String> rollMods(final int min, final int max) {
        final List<MonsterConfig.MonsterMod> pool = new ArrayList<>(config.mods());
        final List<String> chosen = new ArrayList<>();
        final int count = max <= min ? min : min + random.nextInt(max - min + 1);
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            chosen.add(pool.remove(random.nextInt(pool.size())).id());
        }
        return chosen;
    }

    /** 原版血量與護甲換成我們的：血量 = 屬性表的生命，護甲歸零（減免由引擎算），無敵幀歸零。 */
    private void applyVanilla(final LivingEntity entity, final MonsterData data, final StatSheet sheet) {
        final double life = Math.max(1, sheet.get(StatKeys.LIFE_MAX));
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(life);
            entity.setHealth(life);
        }
        final AttributeInstance armour = entity.getAttribute(Attribute.ARMOR);
        if (armour != null) {
            armour.setBaseValue(0);
        }
        final AttributeInstance toughness = entity.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.setBaseValue(0);
        }
        entity.setMaximumNoDamageTicks(0);

        // 原版防具會提供護甲，全部拿掉
        final EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.setItem(EquipmentSlot.HEAD, null);
            equipment.setItem(EquipmentSlot.CHEST, null);
            equipment.setItem(EquipmentSlot.LEGS, null);
            equipment.setItem(EquipmentSlot.FEET, null);
        }

        double speedBonus = 0;
        for (final String modId : data.mods()) {
            final MonsterConfig.MonsterMod mod = config.mod(modId);
            if (mod != null) {
                speedBonus += mod.vanillaSpeedBonus();
            }
        }
        final AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
            if (speedBonus != 0) {
                speed.addModifier(new AttributeModifier(speedKey, speedBonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    /** 名稱：「Lv.12 殭屍」，魔法怪加詞綴名，稀有怪用隨機名字。 */
    public Component displayName(final MonsterData data) {
        final StringBuilder text = new StringBuilder("<" + data.rarity().color() + ">Lv." + data.level() + " ");
        if (data.rarity() == MonsterRarity.MAGIC) {
            for (final String modId : data.mods()) {
                final MonsterConfig.MonsterMod mod = config.mod(modId);
                if (mod != null) {
                    text.append(ItemRenderer.escape(mod.name()));
                }
            }
        }
        text.append(ItemRenderer.escape(data.name()));
        if (data.rarity() == MonsterRarity.RARE) {
            text.append(" <dark_gray>(").append(data.rarity().displayName()).append(")");
        }
        return plugin.text().parse(text.toString());
    }

    /** 原版生物的中文名（monsters.yml 的 types），沒有就用英文代號。 */
    public String typeName(final LivingEntity entity) {
        final MonsterConfig.TypeSettings settings = config.type(entity.getType().name());
        return settings.name() != null ? settings.name() : entity.getType().name();
    }

    private String rareName(final String baseName) {
        final List<String> prefixes = plugin.items().repository().rareNamePrefixes();
        final String prefix = prefixes.isEmpty() ? "無名" : prefixes.get(random.nextInt(prefixes.size()));
        return prefix + baseName;
    }
}
