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
import org.bukkit.inventory.ItemStack;
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
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * 怪物的等級化：決定等級與稀有度、寫進實體 PDC、改名、把原版血量與護甲換成我們的數值。
 * 原版生物、MythicMobs 怪物、區域 Boss 都走這裡；等級在區域裡由區域決定，區域外照離出生點的距離。
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
    private final NamespacedKey lifeMultiplierKey;
    private final NamespacedKey damageMultiplierKey;
    private final NamespacedKey bossKey;
    private final NamespacedKey pinnedKey;
    private final NamespacedKey armourKey;
    private final NamespacedKey toughnessKey;
    private final Map<UUID, StatSheet> sheets = new HashMap<>();
    /** 額外加在怪物屬性表上的修飾器（地圖詞綴、光環、Boss 階段）；建表時依序呼叫。 */
    private final List<BiConsumer<LivingEntity, StatSheet>> augmenters = new ArrayList<>();
    /** 位置 → 怪物等級的來源（地圖實例）；回傳空的就照區域或距離算。 */
    private final List<Function<Location, OptionalInt>> levelSources = new ArrayList<>();
    private final NamespacedKey summonedKey;
    /** 召喚出來的小怪要跟著召喚者的標記（整數，例如地圖實例的編號）。 */
    private final List<NamespacedKey> inheritedTags = new ArrayList<>();
    /** 怪物裝飾好之後通知（光環、召喚的追蹤）。 */
    private final List<BiConsumer<LivingEntity, MonsterData>> decorateListeners = new ArrayList<>();
    /** 每隻怪物受到每個玩家多少傷害（掉落分份、經驗歸屬）。 */
    private final DamageLedger ledger = new DamageLedger();
    /** 刷怪那一刻附近有幾個玩家（人多時怪物變硬；隊伍模組設定怎麼算，沒設定就是 1）。 */
    private final NamespacedKey playersKey;
    private java.util.function.ToIntFunction<LivingEntity> playerCounter = entity -> 1;

    public MonsterService(final ExileCore plugin, final MonsterConfig config, final RandomGenerator random) {
        this.plugin = plugin;
        this.config = config;
        this.random = random;
        this.levelKey = new NamespacedKey(plugin, "monster_level");
        this.rarityKey = new NamespacedKey(plugin, "monster_rarity");
        this.modsKey = new NamespacedKey(plugin, "monster_mods");
        this.nameKey = new NamespacedKey(plugin, "monster_name");
        this.speedKey = new NamespacedKey(plugin, "monster_speed");
        this.lifeMultiplierKey = new NamespacedKey(plugin, "monster_life_multiplier");
        this.damageMultiplierKey = new NamespacedKey(plugin, "monster_damage_multiplier");
        this.bossKey = new NamespacedKey(plugin, "monster_boss");
        this.pinnedKey = new NamespacedKey(plugin, "monster_pinned");
        this.armourKey = new NamespacedKey(plugin, "monster_no_armour");
        this.toughnessKey = new NamespacedKey(plugin, "monster_no_toughness");
        this.summonedKey = new NamespacedKey(plugin, "monster_summoned");
        this.playersKey = new NamespacedKey(plugin, "monster_players");
        this.ledger.setClock(() -> plugin.getServer().getCurrentTick()); // 2026-10：Boss 秒傷紀錄要知道每個人從什麼時候開始打
    }

    /** 每隻怪物受到的玩家傷害。 */
    public DamageLedger ledger() {
        return ledger;
    }

    /** 設定「刷怪時附近有幾個玩家」怎麼算（隊伍模組）。 */
    public void setPlayerCounter(final java.util.function.ToIntFunction<LivingEntity> counter) {
        this.playerCounter = counter == null ? entity -> 1 : counter;
    }

    /** 這隻怪物刷出來時附近有幾個玩家（至少 1）。 */
    public int playersAround(final LivingEntity entity) {
        final Integer value = entity.getPersistentDataContainer().get(playersKey, PersistentDataType.INTEGER);
        return value == null ? 1 : Math.max(1, value);
    }

    /**
     * Boss 生出來之後才進來的人（2026-10）：人數變多就照新的人數重算生命，血量比例不變（剩 60% 還是 60%）。
     * 只會往上加（有人離開不會變弱）。回傳有沒有改。
     */
    public boolean raisePlayers(final LivingEntity entity, final int players) {
        if (players <= playersAround(entity) || !entity.isValid() || entity.isDead()) {
            return false;
        }
        final Optional<MonsterData> data = data(entity);
        if (data.isEmpty()) {
            return false;
        }
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        final double ratio = health == null || health.getValue() <= 0 ? 1 : Math.max(0, Math.min(1, entity.getHealth() / health.getValue()));
        entity.getPersistentDataContainer().set(playersKey, PersistentDataType.INTEGER, players);
        sheets.remove(entity.getUniqueId());
        final StatSheet sheet = sheet(entity, data.get());
        applyVanilla(entity, data.get(), sheet);
        final AttributeInstance after = entity.getAttribute(Attribute.MAX_HEALTH);
        if (after != null) {
            entity.setHealth(Math.max(0.5, Math.min(after.getValue(), after.getValue() * ratio)));
        }
        return true;
    }

    /** 刷出來之前先固定「附近的玩家數」（2026-10，世界 Boss：人數由活動模組自己算生命，不用人多變硬的加成）。 */
    public void fixPlayers(final LivingEntity entity, final int players) {
        entity.getPersistentDataContainer().set(playersKey, PersistentDataType.INTEGER, Math.max(1, players));
    }

    /**
     * 改生命倍率（2026-10，世界 Boss 照在場的高等級玩家人數浮動）：重算屬性表，血量比例不變。回傳有沒有改。
     */
    public boolean setLifeMultiplier(final LivingEntity entity, final double multiplier) {
        if (!entity.isValid() || entity.isDead()) {
            return false;
        }
        final Double before = entity.getPersistentDataContainer().get(lifeMultiplierKey, PersistentDataType.DOUBLE);
        if (before != null && Math.abs(before - multiplier) < 1e-9) {
            return false;
        }
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        final double ratio = health == null || health.getValue() <= 0 ? 1 : Math.max(0, Math.min(1, entity.getHealth() / health.getValue()));
        entity.getPersistentDataContainer().set(lifeMultiplierKey, PersistentDataType.DOUBLE, multiplier);
        sheets.remove(entity.getUniqueId());
        final Optional<MonsterData> data = data(entity);
        if (data.isEmpty()) {
            return false;
        }
        applyVanilla(entity, data.get(), sheet(entity, data.get()));
        final AttributeInstance after = entity.getAttribute(Attribute.MAX_HEALTH);
        if (after != null) {
            entity.setHealth(Math.max(0.5, Math.min(after.getValue(), after.getValue() * ratio)));
        }
        return true;
    }

    /** 註冊一個屬性表的加成來源（地圖模組、光環）。 */
    public void addAugmenter(final BiConsumer<LivingEntity, StatSheet> augmenter) {
        augmenters.add(augmenter);
    }

    /** 註冊一個召喚時要跟著複製的標記（整數值）。 */
    public void addInheritedTag(final NamespacedKey key) {
        inheritedTags.add(key);
    }

    /** 把召喚者的這些標記複製到小怪身上（只複製註冊過的，不會帶走 Boss、等級這些）。 */
    public void inheritTags(final org.bukkit.entity.Entity from, final org.bukkit.entity.Entity to) {
        for (final NamespacedKey key : inheritedTags) {
            final Integer value = from.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            if (value != null) {
                to.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
            }
        }
    }

    /** 註冊一個「怪物裝飾好了」的通知。 */
    public void addDecorateListener(final BiConsumer<LivingEntity, MonsterData> listener) {
        decorateListeners.add(listener);
    }

    /** 註冊一個等級來源（地圖模組：地圖裡的怪物等級是地圖的等級）。 */
    public void addLevelSource(final Function<Location, OptionalInt> source) {
        levelSources.add(source);
    }

    /** 這隻的屬性表要重算（光環、Boss 階段改變時）。 */
    public void invalidate(final UUID uuid) {
        sheets.remove(uuid);
    }

    /** 標記成召喚出來的（不掉東西、不給經驗）。 */
    public void markSummoned(final LivingEntity entity) {
        entity.getPersistentDataContainer().set(summonedKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isSummoned(final LivingEntity entity) {
        return entity.getPersistentDataContainer().has(summonedKey);
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
        final Double lifeMultiplier = container.get(lifeMultiplierKey, PersistentDataType.DOUBLE);
        final Double damageMultiplier = container.get(damageMultiplierKey, PersistentDataType.DOUBLE);
        final Byte boss = container.get(bossKey, PersistentDataType.BYTE);
        return Optional.of(new MonsterData(level, rarity, mods, name == null ? typeName(entity) : name,
                lifeMultiplier == null ? 1.0 : lifeMultiplier, damageMultiplier == null ? 1.0 : damageMultiplier,
                boss != null && boss != 0));
    }

    /** 這隻怪物的屬性表（快取；死亡時清掉）。 */
    public StatSheet sheet(final LivingEntity entity, final MonsterData data) {
        return sheets.computeIfAbsent(entity.getUniqueId(), uuid -> {
            final StatSheet sheet = MonsterSheets.build(config, data, entity.getType().name());
            for (final BiConsumer<LivingEntity, StatSheet> augmenter : augmenters) {
                augmenter.accept(entity, sheet);
            }
            return sheet;
        });
    }

    public void forget(final UUID uuid) {
        sheets.remove(uuid);
    }

    /**
     * 我們的生命值對原版血量的比例：照這隻怪物「現在」的原版血量上限算（平常是 min(生命, 1024)，比例跟以前一樣）。
     * 別的插件改了上限（例如 MythicMobs 的 VanillaMobs.yml 把凋零骷髏設成 50 血）時，比例跟著變，
     * 傷害換算才不會以為牠還有 1024 原版血——不然 Boss 一出生就只剩 5% 的生命（2026-10，蝕骨之王）。
     */
    public double healthScale(final LivingEntity entity, final MonsterData data) {
        final double life = sheet(entity, data).get(StatKeys.LIFE_MAX);
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max == null || life <= 0 || max.getValue() <= 0) {
            return MonsterSheets.healthScale(life);
        }
        return max.getValue() / life;
    }

    /** 把我們算出的傷害換成要寫回原版事件的數字（依血量比例縮放，最少 0.01 讓事件不被視為無效）。 */
    public double toVanillaDamage(final LivingEntity entity, final MonsterData data, final double damage) {
        return Math.max(0.01, damage * healthScale(entity, data));
    }

    /** 依位置決定等級：在區域裡用區域的等級（入口低、Boss 場地高），區域外照離出生點的距離。 */
    public int levelFor(final Location location) {
        for (final Function<Location, OptionalInt> source : levelSources) {
            final OptionalInt level = source.apply(location);
            if (level.isPresent()) {
                return level.getAsInt();
            }
        }
        if (plugin.zones() != null) {
            final OptionalInt zoneLevel = plugin.zones().levelAt(location);
            if (zoneLevel.isPresent()) {
                return zoneLevel.getAsInt();
            }
        }
        final double distance = location.distance(location.getWorld().getSpawnLocation());
        return config.levelForDistance(distance);
    }

    /** 管理員生成的怪物（/ec spawn）：營地的清怪不會清掉它。 */
    public void pin(final LivingEntity entity) {
        entity.getPersistentDataContainer().set(pinnedKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isPinned(final LivingEntity entity) {
        return entity.getPersistentDataContainer().has(pinnedKey);
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
        return decorate(entity, level, rarity, mods, null);
    }

    /**
     * 同上，{@code baseName} 是怪物的名字（MythicMobs 怪物用它自己的名字）；null 時用 monsters.yml 的原版中文名。
     */
    public MonsterData decorate(final LivingEntity entity, final int level, final MonsterRarity rarity, final List<String> mods,
                                final String baseName) {
        final MonsterConfig.RaritySettings settings = config.rarity(rarity);
        final List<String> chosenMods = mods != null ? mods : rollMods(settings.minMods(), settings.maxMods(), level);
        final String base = baseName == null || baseName.isBlank() ? typeName(entity) : baseName;
        final String name = rarity == MonsterRarity.RARE ? rareName(base) : base;
        final MonsterData data = new MonsterData(level, rarity, chosenMods, name);
        apply(entity, data);
        return data;
    }

    /**
     * 照給的資料裝飾（等級、稀有度、詞綴、名字、倍率、首領都照寫的，不抽詞綴）。營地的訓練假人用：
     * 附近的玩家數固定（人多時不會變硬）。
     */
    public void decorateExact(final LivingEntity entity, final MonsterData data, final int playersAround) {
        entity.getPersistentDataContainer().set(playersKey, PersistentDataType.INTEGER, Math.max(1, playersAround));
        apply(entity, data);
    }

    /** 照給的資料裝飾（名字、詞綴、倍率都照寫的），附近的玩家數照刷出來那一刻算（人多會變硬）。地圖事件的流寇用。 */
    public void decorateData(final LivingEntity entity, final MonsterData data) {
        apply(entity, data);
    }

    /**
     * 區域 Boss：傳奇稀有度、固定名字與詞綴、額外的生命／傷害倍率、放大體型，而且不會因為離玩家太遠而消失。
     */
    public MonsterData decorateBoss(final LivingEntity entity, final int level, final String name, final List<String> mods,
                                    final double lifeMultiplier, final double damageMultiplier, final double scale) {
        final List<String> known = new ArrayList<>();
        for (final String id : mods) {
            if (config.mod(id) != null) {
                known.add(id);
            }
        }
        final MonsterData data = new MonsterData(level, MonsterRarity.UNIQUE, known, name, lifeMultiplier, damageMultiplier, true);
        apply(entity, data);
        final AttributeInstance size = entity.getAttribute(Attribute.SCALE);
        if (size != null) {
            size.setBaseValue(scale);
        }
        entity.setRemoveWhenFarAway(false);
        return data;
    }

    /** 寫進 PDC、重算屬性表、套到原版數值、改名。 */
    private void apply(final LivingEntity entity, final MonsterData data) {
        final PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(levelKey, PersistentDataType.INTEGER, data.level());
        container.set(rarityKey, PersistentDataType.STRING, data.rarity().name());
        container.set(modsKey, PersistentDataType.STRING, String.join(",", data.mods()));
        container.set(nameKey, PersistentDataType.STRING, data.name());
        if (!container.has(playersKey)) {
            // 刷出來那一刻附近的玩家數（之後不會再變，存檔後也還在）
            int players = 1;
            try {
                players = Math.max(1, playerCounter.applyAsInt(entity));
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("計算怪物附近的玩家數失敗", e);
            }
            container.set(playersKey, PersistentDataType.INTEGER, players);
        }
        if (data.lifeMultiplier() != 1.0 || data.damageMultiplier() != 1.0 || data.boss()) {
            container.set(lifeMultiplierKey, PersistentDataType.DOUBLE, data.lifeMultiplier());
            container.set(damageMultiplierKey, PersistentDataType.DOUBLE, data.damageMultiplier());
            container.set(bossKey, PersistentDataType.BYTE, (byte) (data.boss() ? 1 : 0));
        } else {
            container.remove(lifeMultiplierKey);
            container.remove(damageMultiplierKey);
            container.remove(bossKey);
        }
        sheets.remove(entity.getUniqueId());

        final StatSheet sheet = sheet(entity, data);
        applyVanilla(entity, data, sheet);
        enforceVanillaHealthLater(entity);
        entity.customName(displayName(data));
        entity.setCustomNameVisible(true);
        for (final BiConsumer<LivingEntity, MonsterData> listener : decorateListeners) {
            listener.accept(entity, data);
        }
    }

    /**
     * 生成之後別的插件可能還會改原版血量上限（MythicMobs 的 VanillaMobs.yml、MythicMobs 怪物的 Health 在生成事件之後才套）：
     * 過 1 tick、1 秒、3 秒各檢查一次（地圖 Boss 每次檢查都對），被改掉的話照我們的值改回來（被壓成 50/50 的算滿血）。
     */
    private void enforceVanillaHealthLater(final LivingEntity entity) {
        for (final long delay : new long[] {1L, 20L, 60L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> enforceVanillaHealth(entity), delay);
        }
    }

    /** 地圖 Boss 每次檢查都對一次（2026-10：MythicMobs 的 VanillaMobs.yml 會把凋零骷髏改成 50 血，Boss 也被改到）。 */
    public void ensureVanillaHealth(final LivingEntity entity) {
        enforceVanillaHealth(entity);
    }

    private void enforceVanillaHealth(final LivingEntity entity) {
        if (!entity.isValid() || entity.isDead()) {
            return;
        }
        final Optional<MonsterData> data = data(entity);
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (data.isEmpty() || health == null) {
            return;
        }
        final double expected = Math.min(MonsterSheets.VANILLA_HEALTH_CAP, Math.max(1, sheet(entity, data.get()).get(StatKeys.LIFE_MAX)));
        if (Math.abs(health.getValue() - expected) < 0.01 && health.getModifiers().isEmpty()) {
            return;
        }
        // 2026-10：照「少了幾點」還原，不照比例——被別的插件改成 50 血的那段時間，打下去的原版點數是照我們的比例算的
        // （1 點 = 幾生命），照比例還原會把打掉的放大好幾十倍（以前 Boss 一出來被打一下就剩一半）
        //（上限被調高時血量本身沒變：照原本的血量；被調低時血量被壓到新的上限：照少了幾點）
        final double current = entity.getHealth();
        final double restored = health.getValue() >= expected ? current : expected - Math.max(0, health.getValue() - current);
        for (final AttributeModifier modifier : List.copyOf(health.getModifiers())) {
            health.removeModifier(modifier);
        }
        health.setBaseValue(expected);
        entity.setHealth(Math.max(0.5, Math.min(expected, restored)));
        entity.setMaximumNoDamageTicks(0);
    }

    /** 屬性表變了（地圖 Boss 換階段）之後，把原版的移動速度、血量上限同步一次（血量比例不變）。 */
    public void refreshVanilla(final LivingEntity entity) {
        final Optional<MonsterData> data = data(entity);
        if (data.isEmpty()) {
            return;
        }
        sheets.remove(entity.getUniqueId());
        final double fraction = entity.getHealth() / Math.max(1, entity.getAttribute(Attribute.MAX_HEALTH) == null ? 1
                : entity.getAttribute(Attribute.MAX_HEALTH).getValue());
        applyVanilla(entity, data.get(), sheet(entity, data.get()));
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            entity.setHealth(Math.max(0.5, Math.min(health.getValue(), health.getValue() * fraction)));
        }
    }

    /** 依詞綴數量範圍從詞綴池隨機抽（不重複；只抽 min_level 1 的基本詞綴）。 */
    public List<String> rollMods(final int min, final int max) {
        return rollMods(min, max, 1);
    }

    /** 依詞綴數量範圍從詞綴池隨機抽（不重複），只抽這個等級能出現的（光環、召喚要夠高等）。魔法怪群用它抽一組共用的詞綴。 */
    public List<String> rollMods(final int min, final int max, final int level) {
        final List<MonsterConfig.MonsterMod> pool = new ArrayList<>();
        for (final MonsterConfig.MonsterMod mod : config.mods()) {
            if (mod.minLevel() <= level) {
                pool.add(mod);
            }
        }
        final List<String> chosen = new ArrayList<>();
        final int count = max <= min ? min : min + random.nextInt(max - min + 1);
        int withSkills = 0;
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            final MonsterConfig.MonsterMod mod = pool.remove(random.nextInt(pool.size()));
            if (mod.hasSkills()) {
                if (withSkills >= MAX_SKILL_MODS) {
                    i--; // 招式的詞綴最多兩條（再多畫面太亂），這條跳過再抽
                    continue;
                }
                withSkills++;
            }
            chosen.add(mod.id());
        }
        return chosen;
    }

    /** 一隻怪物最多幾條帶招式的詞綴。 */
    public static final int MAX_SKILL_MODS = 2;

    /** 依稀有度的詞綴數量抽一組詞綴（只有基本詞綴）。 */
    public List<String> rollMods(final MonsterRarity rarity) {
        return rollMods(rarity, 1);
    }

    /** 依稀有度的詞綴數量抽一組這個等級能出現的詞綴。 */
    public List<String> rollMods(final MonsterRarity rarity, final int level) {
        final MonsterConfig.RaritySettings settings = config.rarity(rarity);
        return rollMods(settings.minMods(), settings.maxMods(), level);
    }

    /**
     * 原版血量與護甲換成我們的：血量 = 屬性表的生命（原版上限 1024，超過就按比例縮放），
     * 護甲與韌性用一個 -1000 的修飾器壓到 0（減免由引擎算；身上的裝備留著當外觀，
     * 例如 MythicMobs 的 Boss 裝），防具上的附魔（保護）拿掉，無敵幀歸零。
     */
    private void applyVanilla(final LivingEntity entity, final MonsterData data, final StatSheet sheet) {
        final double life = Math.max(1, sheet.get(StatKeys.LIFE_MAX));
        final double vanillaLife = Math.min(MonsterSheets.VANILLA_HEALTH_CAP, life);
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            // 其他來源的血量加成（殭屍首領、其他插件）全部拿掉，原版血量上限才會剛好等於我們算的
            for (final AttributeModifier modifier : List.copyOf(health.getModifiers())) {
                health.removeModifier(modifier);
            }
            health.setBaseValue(vanillaLife);
            entity.setHealth(Math.min(vanillaLife, health.getValue()));
        }
        cancelAttribute(entity.getAttribute(Attribute.ARMOR), armourKey);
        cancelAttribute(entity.getAttribute(Attribute.ARMOR_TOUGHNESS), toughnessKey);
        entity.setMaximumNoDamageTicks(0);

        // 裝備的附魔保護會在原版的傷害流程裡再減一次傷，拿掉；掉落機率歸零（死亡時只走我們的掉落表）
        final EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                final ItemStack item = equipment.getItem(slot);
                if (!item.isEmpty() && !item.getEnchantments().isEmpty()) {
                    final ItemStack plain = item.clone();
                    plain.removeEnchantments();
                    equipment.setItem(slot, plain);
                }
                if (entity instanceof org.bukkit.entity.Mob) {
                    equipment.setDropChance(slot, 0f); // 不是 Mob（例如訓練假人的人體模型）的設掉落機率會丟例外
                }
            }
            if (entity instanceof org.bukkit.entity.Mob) {
                equipment.setDropChance(EquipmentSlot.HAND, 0f);
                equipment.setDropChance(EquipmentSlot.OFF_HAND, 0f);
            }
        }

        // 地圖詞綴、光環給的移動速度（屬性表的「增加 x% 移動速度」）
        double speedBonus = sheet.increased(StatKeys.MOVEMENT_SPEED, java.util.Set.of());
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

    /** 把一個原版屬性壓到 0（基礎值 0，再加 -1000 蓋掉裝備給的加成；原版會把總和夾在 0 以上）。 */
    private static void cancelAttribute(final AttributeInstance attribute, final NamespacedKey key) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(0);
        attribute.removeModifier(key);
        attribute.addModifier(new AttributeModifier(key, -1000, AttributeModifier.Operation.ADD_NUMBER));
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
