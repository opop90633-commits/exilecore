package tw.exilecore.monster;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 怪物詞綴的持續效果（每秒一次）：
 * <ul>
 *   <li>光環：半徑內的怪物（含自己）得到光環的修飾器，腳下有一圈彩色粒子；離開範圍一秒後失效。</li>
 *   <li>召喚：有目標玩家時每隔幾秒叫出同種的普通怪（等級一樣，不掉東西、不給經驗），同時最多幾隻。</li>
 *   <li>招式：詞綴（猛擊的、衝鋒的…）或生物種類（monsters.yml 的 types.*.skills，一般怪照 skill_chance 抽、稀有怪一定帶）
 *       給的招式，掛到技能模組的 Boss 招式引擎上（一樣有地上的警告，但不跳動作列、聲音小、不追擊）；
 *       有寫 hand 的手上拿對應的東西，讓人看得出這隻會什麼。</li>
 * </ul>
 * 光環的修飾器透過 {@link MonsterService#addAugmenter} 加進屬性表；範圍內的名單變了就讓那隻怪物的屬性表重算。
 */
public final class MonsterEffects implements Listener {

    private final ExileCore plugin;
    private final MonsterService monsters;
    /** 帶光環或召喚詞綴的怪物。 */
    private final Map<UUID, LivingEntity> sources = new HashMap<>();
    /** 怪物 → 目前受到哪些光環（詞綴代號）。 */
    private final Map<UUID, Set<String>> auras = new HashMap<>();
    /** 召喚者 → 下次可以召喚的 tick。 */
    private final Map<UUID, Long> nextSummon = new HashMap<>();
    /** 召喚者 → 叫出來、還活著的小怪。 */
    private final Map<UUID, List<LivingEntity>> minions = new HashMap<>();

    public MonsterEffects(final ExileCore plugin, final MonsterService monsters) {
        this.plugin = plugin;
        this.monsters = monsters;
        monsters.addAugmenter(this::augment);
    }

    /** 區塊重新載入的怪物（地圖怪會存起來）：光環、召喚、追擊要重新接上。 */
    @EventHandler
    public void onAdd(final EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof LivingEntity living && monsters.isMonster(living)) {
            monsters.data(living).ifPresent(data -> track(living, data));
        }
    }

    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    /** 怪物被裝飾後（或重新載入後）呼叫：有光環或召喚詞綴就開始追蹤；白天不打人的種類加上追擊；有招式的掛上招式。 */
    public void track(final LivingEntity entity, final MonsterData data) {
        if (entity instanceof Mob mob && ChaseGoal.TYPES.contains(entity.getType())) {
            final MobGoals goals = plugin.getServer().getMobGoals();
            if (!goals.hasGoal(mob, ChaseGoal.key(plugin))) {
                goals.addGoal(mob, 0, new ChaseGoal(plugin, mob));
            }
        }
        // 2026-10-22 效能：骷髏類的射箭走位換成節流過的版本（原版每 tick 重新尋路）；優先權最高，原版的弓箭 AI 就不會跑
        if (entity instanceof Mob mob && RangedGoal.applies(entity)) {
            final MobGoals goals = plugin.getServer().getMobGoals();
            if (!goals.hasGoal(mob, RangedGoal.key(plugin))) {
                goals.addGoal(mob, 0, new RangedGoal(plugin, mob));
            }
        }
        attachSkills(entity, data);
        for (final String modId : data.mods()) {
            final MonsterConfig.MonsterMod mod = monsters.config().mod(modId);
            if (mod != null && (mod.aura() != null || mod.summon() != null)) {
                sources.put(entity.getUniqueId(), entity);
                return;
            }
        }
    }

    /**
     * 這隻怪物的招式：詞綴給的 + 這種生物的（照機率）；有就掛到招式引擎上，手上拿對應的東西。
     * 地圖、區域 Boss 不在這裡掛：牠們的招式由地圖、區域那邊掛（Boss 的招式組），這裡掛的話區塊重新載入時會把 Boss 的換掉。
     */
    private void attachSkills(final LivingEntity entity, final MonsterData data) {
        if (plugin.skills() == null || monsters.isSummoned(entity) || plugin.monsters() == null) {
            return; // 召喚出來的小怪不會再有招式（不然會一直生下去）
        }
        if (data.boss()) {
            return;
        }
        final List<tw.exilecore.skill.BossSkillUse> uses = new ArrayList<>();
        String hand = null;
        for (final String modId : data.mods()) {
            final MonsterConfig.MonsterMod mod = monsters.config().mod(modId);
            if (mod != null && mod.hasSkills()) {
                uses.addAll(mod.skills());
                if (hand == null) {
                    hand = mod.hand();
                }
            }
        }
        final MonsterConfig.TypeSettings type = monsters.config().type(entity.getType().name());
        if (!type.skills().isEmpty() && rollsTypeSkill(entity, data, type)) {
            uses.addAll(type.skills());
            if (hand == null) {
                hand = type.hand();
            }
        }
        if (uses.isEmpty()) {
            plugin.skills().bossSkills().detach(entity.getUniqueId());
            return;
        }
        final String name = data.name();
        plugin.skills().bossSkills().attach(entity, uses, new tw.exilecore.skill.BossSkills.Host() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int phase() {
                return 0;
            }

            @Override
            public LivingEntity summon(final String entityType, final Location at) {
                return spawnMinion(entity, data, entityType, at);
            }

            @Override
            public boolean boss() {
                return false;
            }
        });
        equipHand(entity, hand);
    }

    /**
     * 這隻一般怪有沒有抽到這種生物的招式：照 UUID 決定（區塊重新載入後答案一樣）；
     * 魔法怪兩倍機率，稀有怪與傳奇怪一定有。
     */
    static boolean rollsTypeSkill(final LivingEntity entity, final MonsterData data, final MonsterConfig.TypeSettings type) {
        final double chance = switch (data.rarity()) {
            case NORMAL -> type.skillChance();
            case MAGIC -> Math.min(1.0, type.skillChance() * 2);
            default -> 1.0;
        };
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0) {
            return false;
        }
        final long bits = entity.getUniqueId().getLeastSignificantBits() ^ 0x5DEECE66DL;
        final double roll = ((bits >>> 11) & 0xFFFFF) / (double) 0x100000;
        return roll < chance;
    }

    /** 手上拿對應的東西（看得出這隻會什麼）；手上本來就有東西（骷髏的弓）就不換，不然牠就不會射了。 */
    private void equipHand(final LivingEntity entity, final String hand) {
        if (hand == null || !(entity instanceof Mob mob) || mob.getEquipment() == null) {
            return;
        }
        final org.bukkit.Material material = org.bukkit.Material.matchMaterial(hand);
        if (material == null || material.isAir()) {
            if (warnedHands.add(hand)) {
                plugin.getSLF4JLogger().warn("monsters.yml：hand 寫的 {} 不是原版物品", hand);
            }
            return;
        }
        if (!mob.getEquipment().getItemInMainHand().isEmpty()) {
            return;
        }
        mob.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(material));
        mob.getEquipment().setDropChance(org.bukkit.inventory.EquipmentSlot.HAND, 0f);
    }

    private final Set<String> warnedHands = new HashSet<>();

    /** 技能模組啟用得比怪物晚：已經在世界裡的怪物（區塊早就載入的）補掛招式。 */
    public void reattachAll() {
        for (final org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (final LivingEntity entity : world.getLivingEntities()) {
                if (monsters.isMonster(entity)) {
                    monsters.data(entity).ifPresent(data -> attachSkills(entity, data));
                }
            }
        }
    }

    public void forget(final UUID uuid) {
        sources.remove(uuid);
        auras.remove(uuid);
        nextSummon.remove(uuid);
        minions.remove(uuid);
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(uuid);
        }
    }

    /** 光環給的修飾器加進屬性表。 */
    private void augment(final LivingEntity entity, final StatSheet sheet) {
        final Set<String> active = auras.get(entity.getUniqueId());
        if (active == null) {
            return;
        }
        for (final String modId : active) {
            final MonsterConfig.MonsterMod mod = monsters.config().mod(modId);
            if (mod == null || mod.aura() == null) {
                continue;
            }
            for (final AffixMod stat : mod.aura().stats()) {
                sheet.add(stat.toModifier(Map.of(), "光環 " + mod.name()));
            }
        }
    }

    /** 每秒一次。 */
    public void tick() {
        final Map<UUID, Set<String>> next = new HashMap<>();
        final long now = plugin.getServer().getCurrentTick();
        final Iterator<Map.Entry<UUID, LivingEntity>> iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            final LivingEntity source = iterator.next().getValue();
            if (!source.isValid() || source.isDead()) {
                iterator.remove();
                minions.remove(source.getUniqueId());
                continue;
            }
            final Optional<MonsterData> data = monsters.data(source);
            if (data.isEmpty()) {
                iterator.remove();
                continue;
            }
            for (final String modId : data.get().mods()) {
                final MonsterConfig.MonsterMod mod = monsters.config().mod(modId);
                if (mod == null) {
                    continue;
                }
                if (mod.aura() != null) {
                    // 2026-10：怪物光環詞綴的範圍圈都不畫了（效果照舊；圈疊滿地反而看不清楚）
                    pulse(source, mod, next, false);
                }
                if (mod.summon() != null) {
                    summon(source, data.get(), mod.summon(), now);
                }
            }
        }
        // 範圍內名單變了的怪物：屬性表重算
        final Set<UUID> changed = new HashSet<>(auras.keySet());
        changed.addAll(next.keySet());
        final List<UUID> refresh = new ArrayList<>();
        for (final UUID uuid : changed) {
            if (!next.getOrDefault(uuid, Set.of()).equals(auras.getOrDefault(uuid, Set.of()))) {
                refresh.add(uuid);
            }
        }
        auras.clear();
        auras.putAll(next);
        for (final UUID uuid : refresh) {
            // 屬性表重算；光環如果給了移動速度或生命，原版的數值也跟著同步
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(uuid);
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                monsters.refreshVanilla(living);
            } else {
                monsters.invalidate(uuid);
            }
        }
    }

    private void pulse(final LivingEntity source, final MonsterConfig.MonsterMod mod, final Map<UUID, Set<String>> next, final boolean drawn) {
        final double radius = mod.aura().radius();
        next.computeIfAbsent(source.getUniqueId(), key -> new HashSet<>()).add(mod.id());
        for (final Entity entity : source.getNearbyEntities(radius, radius / 2, radius)) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player) && monsters.isMonster(living)
                    && entity.getLocation().distanceSquared(source.getLocation()) <= radius * radius) {
                next.computeIfAbsent(entity.getUniqueId(), key -> new HashSet<>()).add(mod.id());
            }
        }
        // 腳下一圈：套了資源包的人看平面的細圈（跟著怪走），粒子只送給附近沒套的人
        if (!drawn) {
            return;
        }
        final Location center = source.getLocation();
        if (center.getWorld() == null || center.getNearbyPlayers(32).isEmpty()) {
            return;
        }
        final List<Player> viewers;
        if (plugin.skills() != null) {
            final tw.exilecore.skill.GroundDecals decals = plugin.skills().decals();
            decals.follow("mob:" + source.getUniqueId() + ":" + mod.id(), source, radius, mod.aura().color() & 0xffffff);
            viewers = decals.viewers(center);
            if (viewers.isEmpty()) {
                return;
            }
        } else {
            viewers = center.getWorld().getPlayers();
        }
        final Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(mod.aura().color() & 0xffffff), 1.2f);
        final int points = (int) Math.max(12, radius * 3);
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            tw.exilecore.skill.GroundDecals.particle(viewers, Particle.DUST, new Location(center.getWorld(), center.getX() + Math.cos(angle) * radius,
                    center.getY() + 0.2, center.getZ() + Math.sin(angle) * radius), 1, 0, 0, 0, 0, dust);
        }
    }

    private void summon(final LivingEntity source, final MonsterData data, final MonsterConfig.Summon summon, final long now) {
        if (!(source instanceof Mob mob) || !(mob.getTarget() instanceof Player target) || !target.getWorld().equals(source.getWorld())
                || target.getLocation().distanceSquared(source.getLocation()) > 24 * 24) {
            return;
        }
        if (plugin.ailments() != null && plugin.ailments().isIncapacitated(source)) {
            return; // 凍住了不能施法
        }
        final long ready = nextSummon.getOrDefault(source.getUniqueId(), 0L);
        if (now < ready) {
            return;
        }
        nextSummon.put(source.getUniqueId(), now + summon.intervalSeconds() * 20L);
        final List<LivingEntity> alive = minions.computeIfAbsent(source.getUniqueId(), key -> new ArrayList<>());
        alive.removeIf(minion -> !minion.isValid() || minion.isDead());
        final int count = Math.min(summon.count(), summon.max() - alive.size());
        if (count <= 0) {
            return;
        }
        final Location base = source.getLocation();
        base.getWorld().playSound(base, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.2f);
        for (int i = 0; i < count; i++) {
            final double angle = Math.PI * 2 * i / count;
            final Location spot = base.clone().add(Math.cos(angle) * 1.5, 0.1, Math.sin(angle) * 1.5);
            final LivingEntity minion = spawnMinion(source, data, source.getType().name(), spot);
            if (minion == null) {
                continue; // 被其他插件取消了
            }
            if (minion instanceof Mob minionMob) {
                minionMob.setTarget(target);
            }
            base.getWorld().spawnParticle(Particle.SOUL, spot, 8, 0.3, 0.5, 0.3, 0.02);
            alive.add(minion);
        }
    }

    /**
     * 幫一隻怪物生一隻小怪：同等級的普通怪，標記成召喚的（不掉東西、不給經驗），帶著召喚者的標記（地圖實例等）。
     * 生物代號不認得或生不出來回傳 null。
     */
    LivingEntity spawnMinion(final LivingEntity source, final MonsterData data, final String entityType, final Location at) {
        final org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(entityType.toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (!type.isSpawnable() || type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            return null;
        }
        final Entity spawned = at.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
            if (created instanceof org.bukkit.entity.Ageable ageable) {
                ageable.setAdult();
            }
            monsters.inheritTags(source, created);
            if (created instanceof LivingEntity living) {
                monsters.markSummoned(living);
            }
        });
        if (!(spawned instanceof LivingEntity minion) || !spawned.isValid()) {
            spawned.remove();
            return null;
        }
        minion.clearActivePotionEffects();
        monsters.decorate(minion, data.level(), MonsterRarity.NORMAL, List.of());
        minion.setPersistent(false);
        return minion;
    }
}
