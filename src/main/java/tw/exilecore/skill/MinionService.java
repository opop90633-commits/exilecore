package tw.exilecore.skill;

import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.Corpses;
import tw.exilecore.combat.MinionHooks;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterSheets;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 玩家的召喚物（殭屍、骷髏、焚魂、魔像）：原版生物當身體，AI 由我們管——
 * 原版的移動與找目標的 AI 全部拿掉（才不會去打玩家、村民），每 tick 由這裡決定要跟著主人、追哪隻怪、什麼時候打。
 * <ul>
 *   <li>目標：主人剛打過的怪優先，不然是自己附近最近的怪；離主人太遠的怪不追，跑太遠就瞬移回主人身邊。</li>
 *   <li>打人：用原版的傷害事件（傷害來源是召喚物），{@link tw.exilecore.combat.CombatListener} 透過 {@link MinionHooks}
 *       換成主人技能描述、召喚物屬性表算出來的數字；掉落、經驗、傷害數字都算主人的。</li>
 *   <li>被打：怪物會回頭打召喚物（原版的仇恨），每隔一下附近沒目標的怪也會先打召喚物；玩家與召喚物之間互不傷害。</li>
 *   <li>數值每 2 秒照主人目前的屬性表重算（換裝、配點馬上跟上）。</li>
 * </ul>
 * 主人下線、換角色就全部收掉；伺服器重開不會留下（不存檔）。
 */
public final class MinionService implements Listener, MinionHooks {

    /** 離主人超過這麼遠（格）就瞬移回來。 */
    static final double TELEPORT_DISTANCE = 28;
    /** 沒目標時離主人超過這麼遠就走回來。 */
    static final double FOLLOW_START = 5;
    static final double FOLLOW_STOP = 2.5;
    /** 主人附近多遠內的怪，召喚物會主動去打（2026-10：改成以主人為中心，16 → 12，免得跑出畫面外去打）。 */
    static final double AGGRO = 12;
    /** 目標離主人超過這麼遠就不追了（2026-10：22 → 16）。 */
    static final double LEASH = 16;
    /** 附近沒目標的怪多遠內會先打召喚物。 */
    static final double PASSIVE_TAUNT = 5;
    /** 主人剛打過的怪算「主人的目標」幾 tick。 */
    static final long FOCUS_TICKS = 60;
    /** 焚魂飛行速度（格／tick）。 */
    static final double FLY_SPEED = 0.32;
    /** 一個玩家全部的召喚物最多幾隻（各技能的上限另外算）。 */
    static final int MAX_PER_PLAYER = 40;

    /** 一隻召喚物。 */
    static final class Minion {
        final UUID owner;
        final SkillBuild build;
        final SummonSpec spec;
        final Mob mob;
        /** 頭上的名字（技能名與主人；血不滿時後面加百分比）。 */
        final String name;
        final long bornTick;
        /** 到哪個 tick 消失（0 = 永久）。 */
        final long untilTick;
        StatSheet sheet;
        /** 疊好輔助寶石的屬性表（打人、被打都用這張）。 */
        StatSheet effective;
        SkillProfile profile;
        double healthScale = 1;
        int attackTicks = 20;
        double regenPercent;
        UUID target;
        long nextAttack;
        long nextPath;
        long nextRetarget;
        long nextTaunt;
        long nextRebuild;
        boolean idleStopped;
        /** 祭品（2026-10）給的增益與到期的 tick。 */
        List<Modifier> offering = List.of();
        long offeringUntil;
        /** 強化（2026-10 PoE2 喚屍：吃銳星叫出來的那隻）：一直有效的修飾器。 */
        List<Modifier> empowered = List.of();
        /** 殘影（2026-10）：站著不動、不打人，只負責嘲諷；不算數量上限、不顯示在快捷欄、死了不觸發。 */
        boolean decoy;

        Minion(final UUID owner, final SkillBuild build, final SummonSpec spec, final Mob mob, final String name, final long now,
               final long untilTick) {
            this.owner = owner;
            this.build = build;
            this.spec = spec;
            this.mob = mob;
            this.name = name;
            this.bornTick = now;
            this.untilTick = untilTick;
            this.nextTaunt = now + 20;
        }

        String skillId() {
            return build.definition().id();
        }
    }

    /** 主人剛打過的怪。 */
    private record Focus(UUID target, long tick) {
    }

    private final ExileCore plugin;
    private final SkillConfig config;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final NamespacedKey key;
    private final NamespacedKey skillKey;
    private final NamespacedKey speedKey;
    private final NamespacedKey armourKey;
    private final NamespacedKey toughnessKey;
    private final List<Minion> minions = new ArrayList<>();
    private final Map<UUID, Minion> byEntity = new HashMap<>();
    private final Map<UUID, Focus> focus = new HashMap<>();
    /** 光環：主人開著的、隊友給的光環效果也給召喚物。 */
    private AuraService auras;

    MinionService(final ExileCore plugin, final SkillConfig config, final SkillTargets targets, final SkillVisuals visuals) {
        this.plugin = plugin;
        this.config = config;
        this.targets = targets;
        this.visuals = visuals;
        this.key = new NamespacedKey(plugin, "minion");
        this.skillKey = new NamespacedKey(plugin, "minion_skill");
        this.speedKey = new NamespacedKey(plugin, "minion_speed");
        this.armourKey = new NamespacedKey(plugin, "minion_no_armour");
        this.toughnessKey = new NamespacedKey(plugin, "minion_no_toughness");
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    void setAuras(final AuraService auras) {
        this.auras = auras;
    }

    // ---- 查詢 ----

    /** 這個玩家某個技能目前有幾隻（說明用）。 */
    int count(final UUID owner, final String skillId) {
        int count = 0;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId)) {
                count++;
            }
        }
        return count;
    }

    /** 這個玩家全部有幾隻。 */
    public int count(final UUID owner) {
        int count = 0;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && !minion.decoy) {
                count++;
            }
        }
        return count;
    }

    int size() {
        return minions.size();
    }

    /** 魔像在的時候給主人的增益（屬性表的來源）。 */
    List<Modifier> modifiers(final Player player) {
        List<Modifier> list = null;
        final Set<String> counted = new HashSet<>();
        for (final Minion minion : minions) {
            if (minion.owner.equals(player.getUniqueId()) && !minion.spec.buff().isEmpty() && counted.add(minion.skillId())) {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.addAll(minion.spec.buff());
            }
        }
        return list == null ? List.of() : list;
    }

    // ---- 召喚 ----

    /** 花魔力之前：召不召得出來（要屍體的技能附近要有屍體）。 */
    String precheck(final Player player, final SkillBuild build) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null) {
            return null;
        }
        if (spec.corpse() && corpseNear(player, spec) == null) {
            if (powerRaise(build) && powerCharges(player) > 0) {
                return null; // 喚屍（2026-10 PoE2）：沒有屍體就吃一顆銳星
            }
            return powerRaise(build) ? "附近沒有屍體，身上也沒有銳星" : "附近沒有屍體";
        }
        return null;
    }

    private Corpses.Corpse corpseNear(final Player player, final SummonSpec spec) {
        final Location at = player.getLocation();
        return plugin.combat().corpses().nearest(at.getWorld().getUID(), at.getX(), at.getZ(), spec.search(), now());
    }

    /** 祭品（2026-10）：主人身邊 radius 格內自己的召喚物得到增益 ticks；回傳幾隻。 */
    int offer(final Player player, final double radius, final int ticks, final List<Modifier> mods) {
        final long now = now();
        final Location at = player.getLocation();
        int count = 0;
        for (final Minion minion : minions) {
            if (!minion.owner.equals(player.getUniqueId()) || !minion.mob.isValid() || !minion.mob.getWorld().equals(at.getWorld())
                    || minion.mob.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            minion.offering = List.copyOf(mods);
            minion.offeringUntil = now + ticks;
            rebuild(minion, plugin.players().sheet(player), false);
            minion.mob.getWorld().spawnParticle(org.bukkit.Particle.SOUL, minion.mob.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            count++;
        }
        return count;
    }

    /**
     * 指令（2026-10）：owner 的召喚物裡聽命的（技能代號在 who 裡，或 who 有 any；殘影不算、20 格內），照離主人由近到遠，最多 max 隻；
     * 回傳每隻的位置（腳）與牠現在的目標（沒有就 null）。
     */
    java.util.List<java.util.Map.Entry<Location, LivingEntity>> commandable(final Player owner, final java.util.List<String> who, final int max) {
        final java.util.List<Minion> list = new ArrayList<>();
        final Location at = owner.getLocation();
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && minion.mob.getWorld().equals(at.getWorld())
                    && minion.mob.getLocation().distanceSquared(at) <= 20 * 20 && (who.contains("any") || who.contains(minion.skillId()))) {
                list.add(minion);
            }
        }
        list.sort(java.util.Comparator.comparingDouble(m -> m.mob.getLocation().distanceSquared(at)));
        final java.util.List<java.util.Map.Entry<Location, LivingEntity>> out = new ArrayList<>();
        for (final Minion minion : list) {
            if (out.size() >= max) {
                break;
            }
            minion.mob.swingMainHand();
            final LivingEntity target = minion.target == null ? null : living(minion.target);
            out.add(java.util.Map.entry(minion.mob.getLocation(), target));
        }
        return out;
    }

    /** 祭柱（2026-10）：at 附近 radius 格內 owner 的召喚物（殘影不算）得到增益 ticks；回傳幾隻。 */
    int offerAt(final Player owner, final Location at, final double radius, final int ticks, final List<Modifier> mods) {
        final long now = now();
        int count = 0;
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner.getUniqueId()) || minion.decoy || !minion.mob.isValid() || !minion.mob.getWorld().equals(at.getWorld())
                    || minion.mob.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            final boolean fresh = minion.offeringUntil <= now;
            minion.offering = List.copyOf(mods);
            minion.offeringUntil = now + ticks;
            if (fresh) {
                rebuild(minion, plugin.players().sheet(owner), false);
                minion.mob.getWorld().spawnParticle(org.bukkit.Particle.SOUL, minion.mob.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.02);
            }
            count++;
        }
        return count;
    }

    /** 祭柱沒有屍體時（2026-10）：獻祭一隻 near 附近 search 格內自己最舊的召喚物（殘影不算），回傳牠倒下的位置；沒有回傳 null。 */
    Location sacrifice(final Player owner, final Location near, final double search) {
        Minion pick = null;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && minion.mob.getWorld().equals(near.getWorld())
                    && minion.mob.getLocation().distanceSquared(near) <= search * search && (pick == null || minion.bornTick < pick.bornTick)) {
                pick = minion;
            }
        }
        if (pick == null) {
            return null;
        }
        final Location at = pick.mob.getLocation();
        remove(pick, Gone.EXPIRED);
        return at;
    }

    /** 祭柱：附近有沒有自己的召喚物可以獻祭（花魔力之前看）。 */
    boolean canSacrifice(final Player owner, final Location near, final double search) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && minion.mob.getWorld().equals(near.getWorld())
                    && minion.mob.getLocation().distanceSquared(near) <= search * search) {
                return true;
            }
        }
        return false;
    }

    /** 夥伴（2026-10）：這個技能現在有沒有活著的。 */
    boolean hasCompanion(final UUID owner, final String skillId) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId) && minion.mob.isValid() && !minion.mob.isDead()) {
                return true;
            }
        }
        return false;
    }

    /** 精魂召喚（2026-10，照 PoE2 每隻算精魂）：這個技能超過 keep 隻的收掉（精魂變少了）。 */
    void trim(final UUID owner, final String skillId, final int keep) {
        int seen = 0;
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId)) {
                if (++seen > keep) {
                    remove(minion, Gone.EXPIRED);
                }
            }
        }
    }

    /** 夥伴（2026-10）：光環沒開著的夥伴收掉。 */
    void dismissCompanions(final UUID owner, final Set<String> keep) {
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner) && minion.build.definition().mechanic() == Mechanic.AURA && !keep.contains(minion.skillId())) {
                remove(minion, Gone.EXPIRED);
            }
        }
    }

    /** 號召（2026-10）：自己的召喚物全部瞬間叫回腳邊、放掉原本的目標；回傳叫回幾隻。 */
    int rally(final Player player) {
        final List<Minion> mine = new ArrayList<>();
        for (final Minion minion : minions) {
            if (minion.owner.equals(player.getUniqueId()) && minion.mob.isValid() && !minion.mob.isDead()) {
                mine.add(minion);
            }
        }
        final Location center = player.getLocation();
        for (int i = 0; i < mine.size(); i++) {
            final Minion minion = mine.get(i);
            final double angle = Math.PI * 2 * i / Math.max(1, mine.size());
            Location to = center.clone().add(Math.cos(angle) * 1.6, 0, Math.sin(angle) * 1.6);
            if (!to.getBlock().isPassable() || !to.clone().add(0, 1, 0).getBlock().isPassable()) {
                to = center.clone();
            }
            to.setYaw(center.getYaw());
            final Location from = minion.mob.getLocation();
            tw.exilecore.skill.Fx.spawn(from.getWorld(), org.bukkit.Particle.REVERSE_PORTAL, from.add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            minion.mob.setTarget(null);
            minion.target = null;
            minion.mob.teleport(to);
            tw.exilecore.skill.Fx.spawn(to.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME, to.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
        }
        return mine.size();
    }

    /** 喚屍這類（2026-10 PoE2）：沒有屍體時可以吃一顆銳星。 */
    private static boolean powerRaise(final SkillBuild build) {
        return SkillExecutor.hasFlag(build.definition(), tw.exilecore.stats.StatKeys.FLAG_POWER_RAISE);
    }

    private int powerCharges(final Player player) {
        return plugin.combat() == null || plugin.combat().charges() == null ? 0 : plugin.combat().charges().count(player, "power");
    }

    /** 強化殭屍的加成：更耐打（生命更多 40%）、傷害更多 100%、移動更快 25%。 */
    static final List<Modifier> EMPOWERED = List.of(
            Modifier.more(tw.exilecore.stats.StatKeys.LIFE_MAX, 0.4, "強化"),
            Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, 1.0, "強化"),
            Modifier.increased(tw.exilecore.stats.StatKeys.MOVEMENT_SPEED, 0.25, "強化"));

    /** 召出來（超過上限就把同一個技能最舊的收掉）。 */
    void summon(final Player player, final SkillBuild build) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null) {
            return;
        }
        if (spec.corpse() && powerRaise(build) && corpseNear(player, spec) == null && powerCharges(player) > 0
                && plugin.combat().charges().consume(player, "power")) {
            summon(player, build, 3, false, EMPOWERED); // 吃一顆銳星：一隻強化的加兩隻一般的，從面前爬出來
            return;
        }
        summon(player, build, spec.count(), spec.corpse(), List.of());
    }

    private void summon(final Player player, final SkillBuild build, final int count, final boolean useCorpse, final List<Modifier> first) {
        final SummonSpec spec = build.definition().summon();
        final long now = now();
        final UUID owner = player.getUniqueId();
        final int limit = Math.max(1, build.minions());
        final Vector facing = SkillTargets.facing(player);
        final String ownerName = plugin.players().get(player).character() == null ? player.getName()
                : plugin.players().get(player).character().name();
        final String name = "<#8fd3ff>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name())
                + " <gray>（" + tw.exilecore.item.ItemRenderer.escape(ownerName) + "）";
        for (int i = 0; i < count; i++) {
            Location at;
            String spectreKind = null;
            if (useCorpse) {
                final Corpses.Corpse corpse = corpseNear(player, spec);
                if (corpse == null) {
                    break; // 第二隻沒屍體就算了
                }
                plugin.combat().corpses().remove(corpse);
                at = ground(new Location(player.getWorld(), corpse.x(), corpse.y(), corpse.z()));
                if (spec.spectre()) {
                    // 附身（2026-10）：屍體原本是什麼怪就用什麼當身體（不能用的照技能寫的種類）
                    spectreKind = corpse.type() != null && SummonSpec.SPECTRES.contains(corpse.type()) ? corpse.type() : null;
                }
            } else {
                // 面前 1.8 格，兩隻以上左右排開
                final double side = count == 1 ? 0 : (i - (count - 1) / 2.0) * 1.4;
                final Vector right = new Vector(-facing.getZ(), 0, facing.getX());
                at = ground(player.getLocation().add(facing.clone().multiply(1.8)).add(right.multiply(side)));
            }
            if (at == null) {
                at = player.getLocation();
            }
            if (spec.flying()) {
                at.add(0, 1.2, 0);
            }
            at.setYaw(player.getLocation().getYaw());
            // 同一個技能同時最多幾隻：多的收最舊的；一個人全部也有上限
            final List<Minion> mine = new ArrayList<>();
            final List<Minion> all = new ArrayList<>();
            for (final Minion minion : minions) {
                if (minion.owner.equals(owner)) {
                    all.add(minion);
                    if (minion.skillId().equals(build.definition().id())) {
                        mine.add(minion);
                    }
                }
            }
            while (mine.size() >= limit) {
                final Minion oldest = mine.removeFirst();
                all.remove(oldest);
                remove(oldest, Gone.REPLACED);
            }
            while (all.size() >= MAX_PER_PLAYER) {
                remove(all.removeFirst(), Gone.REPLACED);
            }
            final Mob mob = spawn(player, build, spec, at, name, spectreKind);
            if (mob == null) {
                continue;
            }
            final Minion minion = new Minion(owner, build, spec, mob, name, now, spec.permanent() ? 0 : now + build.durationTicks(spec.duration()));
            if (i == 0 && !first.isEmpty()) {
                minion.empowered = first;
                mob.setGlowing(true); // 強化的那隻發光，好認
            }
            rebuild(minion, plugin.players().sheet(player), true);
            minions.add(minion);
            byEntity.put(mob.getUniqueId(), minion);
            syncName(minion, now); // 有時間限制的一出生就看得到剩幾秒
            visuals.summon(build.definition().visual(), at, spec);
        }
        if (!spec.buff().isEmpty()) {
            plugin.players().markDirty(player);
        }
        refreshHud(owner);
    }

    /**
     * 殘影（2026-10）：在 at 留一個主人模樣的分身（戴主人的頭、穿主人的盔甲外觀），站著不動、一直嘲諷附近的怪，
     * 時間到或被打爆就消失。同一個人同時只有一個（新的換掉舊的）；不算召喚物數量上限。
     */
    public void decoy(final Player player, final SkillBuild build, final Location at) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null || at.getWorld() == null) {
            return;
        }
        final UUID owner = player.getUniqueId();
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner) && minion.decoy) {
                remove(minion, Gone.REPLACED);
            }
        }
        final long now = now();
        final String name = "<#b8c7ff>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name());
        Location spot = ground(at.clone());
        if (spot == null) {
            spot = at.clone();
        }
        spot.setYaw(player.getLocation().getYaw());
        final Mob mob = spawn(player, build, spec, spot, name, null);
        if (mob == null) {
            return;
        }
        final Minion minion = new Minion(owner, build, spec, mob, name, now, now + build.durationTicks(spec.duration()));
        minion.decoy = true;
        rebuild(minion, plugin.players().sheet(player), true);
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
            speed.setBaseValue(0);
        }
        final EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
                skull.setOwningPlayer(player);
                head.setItemMeta(skull);
            }
            equipment.setItem(EquipmentSlot.HEAD, head, true);
            final EntityEquipment worn = player.getEquipment();
            for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND}) {
                final ItemStack item = worn == null ? null : worn.getItem(slot);
                if (item != null && !item.isEmpty()) {
                    final ItemStack look = new ItemStack(item.getType()); // 只要外觀，不帶物品資料
                    equipment.setItem(slot, look, true);
                }
            }
        }
        mob.setGlowing(true);
        minions.add(minion);
        byEntity.put(mob.getUniqueId(), minion);
        syncName(minion, now);
        taunt(minion, spec.tauntRadius(), true);
        visuals.taunt(build.definition().visual(), spot, spec.tauntRadius());
    }

    /** 一個技能的召喚物數量（快捷欄右邊；2026-10）。 */
    public record HudCount(String name, int count, int limit) {
    }

    /**
     * 每個有召喚物的技能：名字、現在幾隻、上限（上限看最近召出來那隻的設定，換了輔助寶石下次召喚就更新）。
     */
    public java.util.List<HudCount> hudCounts(final UUID owner) {
        final java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        final java.util.Map<String, Minion> latest = new java.util.HashMap<>();
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner) || minion.decoy) {
                continue;
            }
            counts.computeIfAbsent(minion.skillId(), key -> new int[1])[0]++;
            latest.merge(minion.skillId(), minion, (x, y) -> y.bornTick >= x.bornTick ? y : x);
        }
        final java.util.List<HudCount> list = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<String, int[]> entry : counts.entrySet()) {
            final Minion minion = latest.get(entry.getKey());
            list.add(new HudCount(minion.build.definition().name(), entry.getValue()[0], Math.max(1, minion.build.minions())));
        }
        return list;
    }

    /** 召喚物多了、少了：馬上更新主人的動作列（快捷欄右邊的數量）。 */
    private void refreshHud(final UUID owner) {
        final Player player = plugin.getServer().getPlayer(owner);
        if (player != null && player.isOnline()) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(player);
            if (state != null && state.hasCharacter()) {
                plugin.players().display(player, state);
            }
        }
    }

    private Mob spawn(final Player player, final SkillBuild build, final SummonSpec spec, final Location at, final String name, final String kind) {
        final EntityType type;
        try {
            type = EntityType.valueOf(kind != null ? kind : spec.kind());
        } catch (final IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("召喚物種類不存在：{}", spec.kind());
            return null;
        }
        final Consumer<Entity> setup = created -> {
            if (!(created instanceof Mob mob)) {
                return;
            }
            if (mob instanceof Ageable ageable) {
                ageable.setAdult();
            }
            mob.setPersistent(false);
            mob.setRemoveWhenFarAway(false);
            mob.setCanPickupItems(false);
            mob.setSilent(true); // 召喚物靜音（2026-10）：原版的叫聲、受傷、死亡、腳步、攻擊的聲音都不出
            mob.setCollidable(false);
            mob.customName(plugin.text().parse(name));
            mob.setCustomNameVisible(true);
            mob.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
            mob.getPersistentDataContainer().set(skillKey, PersistentDataType.STRING, build.definition().id());
            if (mob instanceof Zombie zombie) {
                zombie.setShouldBurnInDay(false);
                zombie.setCanBreakDoors(false);
                final AttributeInstance reinforcements = zombie.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
                if (reinforcements != null) {
                    reinforcements.setBaseValue(0);
                }
            }
            if (mob instanceof AbstractSkeleton skeleton) {
                skeleton.setShouldBurnInDay(false);
            }
            if (mob instanceof IronGolem golem) {
                golem.setPlayerCreated(true);
                final AttributeInstance scale = golem.getAttribute(Attribute.SCALE);
                if (scale != null) {
                    scale.setBaseValue(0.85);
                }
            }
            if (mob instanceof Vex vex) {
                vex.setLimitedLifetime(false);
                vex.setCharging(false);
            }
            final AttributeInstance follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) {
                follow.setBaseValue(48);
            }
            cancelAttribute(mob.getAttribute(Attribute.ARMOR), armourKey);
            cancelAttribute(mob.getAttribute(Attribute.ARMOR_TOUGHNESS), toughnessKey);
            final EntityEquipment equipment = mob.getEquipment();
            if (equipment != null) {
                equipment.clear();
                final Material helmet = spec.helmet() == null ? null : Material.matchMaterial(spec.helmet());
                final Material weapon = spec.weapon() == null ? null : Material.matchMaterial(spec.weapon());
                if (helmet != null) {
                    equipment.setItem(EquipmentSlot.HEAD, new ItemStack(helmet), true);
                }
                if (weapon != null) {
                    equipment.setItem(EquipmentSlot.HAND, new ItemStack(weapon), true);
                }
                for (final EquipmentSlot slot : EquipmentSlot.values()) {
                    try {
                        equipment.setDropChance(slot, 0f);
                    } catch (final IllegalArgumentException ignored) {
                        // 不是裝備格（例如 BODY、SADDLE 在這種生物身上）
                    }
                }
            }
        };
        try {
            final Entity spawned = spawnTyped(at.getWorld(), at, type.getEntityClass(), setup);
            if (!(spawned instanceof Mob mob) || !spawned.isValid()) {
                if (spawned != null) {
                    spawned.remove();
                }
                return null; // 被其他插件取消了
            }
            tameGoals(mob);
            return mob;
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("召不出{}（{}）", spec.kindName(), e.toString());
            return null;
        }
    }

    private static <T extends Entity> T spawnTyped(final World world, final Location at, final Class<T> type, final Consumer<Entity> setup) {
        return world.spawn(at, type, false, setup);
    }

    /** 拿掉原版的移動與找目標 AI（只留浮水、看東看西這些），由我們來指揮。跨世界之後原版會重新裝上，要再拿一次。 */
    private void tameGoals(final Mob mob) {
        final MobGoals goals = plugin.getServer().getMobGoals();
        goals.removeAllGoals(mob, GoalType.MOVE);
        goals.removeAllGoals(mob, GoalType.TARGET);
        mob.setTarget(null);
    }

    /** 把一個原版屬性壓到 0（頭盔的護甲不能再減一次傷；減免由引擎算）。 */
    private static void cancelAttribute(final AttributeInstance attribute, final NamespacedKey key) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(0);
        attribute.removeModifier(key);
        attribute.addModifier(new AttributeModifier(key, -1000, AttributeModifier.Operation.ADD_NUMBER));
    }

    /** 這個點往下找地面（最多 4 格）；找不到回傳 null。 */
    private static Location ground(final Location point) {
        final World world = point.getWorld();
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 4; y--) {
            if (y - 1 >= world.getMinHeight() && y < world.getMaxHeight()
                    && world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5, point.getYaw(), 0);
            }
        }
        return null;
    }

    // ---- 數值 ----

    /** 照主人目前的屬性表重算這隻的屬性表，再把原版的血量上限、移動速度同步過去。 */
    private void rebuild(final Minion minion, final StatSheet ownerSheet, final boolean fresh) {
        final MonsterConfig monsters = plugin.monsters().config();
        final org.bukkit.entity.Player ownerPlayer = plugin.getServer().getPlayer(minion.owner);
        final int level = MinionSheets.monsterLevel(config, minion.build.level(),
                ownerPlayer == null ? 0 : plugin.players().get(ownerPlayer).level());
        final double[] damage = monsters.damageAt(level);
        final double scale = MinionSheets.extraScale(config, minion.build.level()); // 2026-10：超過 20 級的技能等級
        final MinionSheets.Base base = new MinionSheets.Base(monsters.lifeAt(level) * scale, monsters.accuracyAt(level), monsters.evasionAt(level),
                monsters.armourAt(level), damage[0] * scale, damage[1] * scale, monsters.critChance());
        final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.ownerModifiers(ownerSheet));
        if (auras != null) {
            ownerModifiers.addAll(auras.effectsFor(minion.owner)); // 光環給召喚物的是完整效果（PoE 的規矩）
        }
        ownerModifiers.addAll(minion.empowered);
        final boolean offered = minion.offeringUntil > now();
        if (offered) {
            ownerModifiers.addAll(minion.offering); // 祭品（2026-10）
        }
        minion.sheet = MinionSheets.build(minion.spec, base, ownerModifiers);
        minion.profile = MinionSheets.profile(minion.build);
        minion.effective = minion.profile.local().isEmpty() ? minion.sheet // 2026-10：輔助寶石給召喚物的也拿掉 minion 標籤（防禦類才讀得到）
                : minion.sheet.overlay(MinionSheets.stripMinion(minion.profile.local()));
        minion.attackTicks = MinionSheets.attackTicks(MinionSheets.attacksPerSecond(minion.effective, minion.profile));
        minion.regenPercent = MinionSheets.regenPercent(minion.effective);
        final double life = MinionSheets.life(minion.effective);
        minion.healthScale = MonsterSheets.healthScale(life);
        syncVanilla(minion, life, fresh);
        minion.nextRebuild = offered ? Math.min(now() + 40, minion.offeringUntil + 1) : now() + 40;
    }

    private void syncVanilla(final Minion minion, final double life, final boolean fresh) {
        final Mob mob = minion.mob;
        final AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            final double fraction = fresh || health.getValue() <= 0 ? 1.0 : Math.max(0, Math.min(1, mob.getHealth() / health.getValue()));
            for (final AttributeModifier modifier : List.copyOf(health.getModifiers())) {
                health.removeModifier(modifier);
            }
            final double vanilla = Math.min(MonsterSheets.VANILLA_HEALTH_CAP, life);
            health.setBaseValue(vanilla);
            mob.setHealth(Math.max(0.5, Math.min(vanilla, vanilla * fraction)));
        }
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
            final double scale = minion.spec.speed() * MinionSheets.movementScale(minion.effective) - 1;
            if (Math.abs(scale) > 1e-6) {
                speed.addModifier(new AttributeModifier(speedKey, scale, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    // ---- 每 tick ----

    void tick() {
        final long now = now();
        if (minions.isEmpty()) {
            if (now % 200 == 0) {
                focus.clear();
            }
            return;
        }
        for (final Minion minion : new ArrayList<>(minions)) {
            if (!byEntity.containsKey(minion.mob.getUniqueId())) {
                continue; // 這一輪已經被收掉了
            }
            try {
                tickOne(minion, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("召喚物更新出錯（{}）", minion.skillId(), e);
                remove(minion, Gone.QUIET);
            }
        }
    }

    private void tickOne(final Minion minion, final long now) {
        final java.util.UUID previousFx = Fx.source(minion.owner); // 2026-10：隱藏別人的技能特效
        try {
            tickOneInner(minion, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickOneInner(final Minion minion, final long now) {
        final Mob mob = minion.mob;
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (!mob.isValid() || mob.isDead()) {
            remove(minion, Gone.QUIET);
            return;
        }
        if (owner == null || !owner.isOnline()) {
            remove(minion, Gone.EXPIRED);
            return;
        }
        if (minion.untilTick > 0 && now >= minion.untilTick) {
            remove(minion, Gone.EXPIRED);
            return;
        }
        if (minion.decoy) {
            // 殘影：不動、不打、不跟著主人；換世界就收掉，每 taunt 秒嘲諷一次
            if (!mob.getWorld().equals(owner.getWorld())) {
                remove(minion, Gone.EXPIRED);
                return;
            }
            if (now % 20 == minion.bornTick % 20) {
                syncName(minion, now);
            }
            if (minion.spec.taunt() > 0 && now >= minion.nextTaunt) {
                minion.nextTaunt = now + Math.round(minion.spec.taunt() * 20);
                taunt(minion, minion.spec.tauntRadius(), true);
            }
            return;
        }
        if (now >= minion.nextRebuild) {
            rebuild(minion, plugin.players().sheet(owner), false);
        }
        if (now % 200 == 17) {
            tameGoals(mob); // 保險：跨世界、區塊重載之後原版 AI 會回來
        }
        // 離太遠、不同世界：瞬移回主人身邊
        if (!mob.getWorld().equals(owner.getWorld()) || mob.getLocation().distanceSquared(owner.getLocation()) > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            teleportToOwner(minion, owner);
            return;
        }
        if (minion.regenPercent > 0 && now % 20 == 0) {
            healFraction(mob, minion.regenPercent / 100.0);
        }
        if (minion.spec.heal() > 0 && now % 20 == minion.bornTick % 20) {
            healAllies(minion, owner); // 治療的召喚物（2026-10）
        }
        if (now % 20 == minion.bornTick % 20) {
            syncName(minion, now);
        }
        if (minion.spec.flying() && now % 2 == 0) {
            visuals.minionTrail(minion.build.definition().visual(), mob.getLocation().add(0, 0.4, 0));
        }

        // 目標
        LivingEntity target = minion.target == null ? null : living(minion.target);
        if (target != null && !validTarget(target, owner, mob)) {
            target = null;
            minion.target = null;
        }
        if (target == null && now >= minion.nextRetarget) {
            minion.nextRetarget = now + 5;
            if (!ownerInTown(owner)) {
                target = findTarget(minion, owner);
                minion.target = target == null ? null : target.getUniqueId();
            }
        }
        if (target == null) {
            idle(minion, owner, now);
            return;
        }
        minion.idleStopped = false;
        mob.lookAt(target);
        if (mob.getTarget() != target) {
            mob.setTarget(target);
        }
        if (!(mob instanceof Vex)) {
            mob.setAggressive(true);
        }
        // 距離量到目標的碰撞箱邊緣，所以只加自己的半個身寬
        final double reach = minion.spec.isRanged() ? minion.spec.ranged() : minion.spec.reach() + mob.getWidth() / 2;
        final double distance = Math.sqrt(SkillTargets.boxDistanceSquared(mob.getLocation(), target.getBoundingBox()));
        final boolean inReach = distance <= reach && (minion.spec.isRanged() || Math.abs(target.getLocation().getY() - mob.getLocation().getY()) <= 3);
        if (minion.spec.flying()) {
            fly(mob, SkillTargets.center(target), inReach ? 0.4 : 1.0);
            ((Vex) mob).setCharging(distance <= reach + 3);
        } else if (!inReach && now >= minion.nextPath) {
            minion.nextPath = now + 10;
            mob.getPathfinder().moveTo(target, 1.2);
        } else if (inReach && now >= minion.nextPath) {
            minion.nextPath = now + 10;
            mob.getPathfinder().stopPathfinding();
        }
        if (inReach && now >= minion.nextAttack && mob.hasLineOfSight(target)) {
            minion.nextAttack = now + minion.attackTicks;
            attack(minion, target);
        }
        // 魔像：每隔幾秒吼一聲，把附近的怪引過來打它
        if (minion.spec.taunt() > 0 && now >= minion.nextTaunt) {
            minion.nextTaunt = now + Math.round(minion.spec.taunt() * 20);
            taunt(minion, minion.spec.tauntRadius(), true);
            visuals.taunt(minion.build.definition().visual(), mob.getLocation(), minion.spec.tauntRadius());
        } else if (now % 20 == minion.bornTick % 20) {
            taunt(minion, PASSIVE_TAUNT, false); // 附近還沒有目標的怪先打召喚物
        }
    }

    private void attack(final Minion minion, final LivingEntity target) {
        final Mob mob = minion.mob;
        mob.swingMainHand();
        if (minion.spec.isRanged()) {
            bolt(mob, target, minion.spec.bolt()); // 遠程（2026-10）：從身上畫一道彈道到目標
        }
        visuals.minionAttack(minion.build.definition().visual(), mob, target);
        final List<LivingEntity> hits = new ArrayList<>();
        hits.add(target);
        if (minion.spec.radius() > 0) {
            for (final LivingEntity other : targets.around(target.getLocation(), minion.spec.radius(), plugin.getServer().getPlayer(minion.owner))) {
                if (other != target && !hits.contains(other)) {
                    hits.add(other);
                }
            }
        }
        for (final LivingEntity hit : hits) {
            if (hit.isValid() && !hit.isDead()) {
                hit.damage(1.0, mob); // 數字由 CombatListener 換掉（MinionHooks），這裡的 1 只是觸發
            }
        }
    }

    /** 遠程召喚物的彈道：一串粒子從手邊到目標身上。 */
    private static void bolt(final Mob mob, final LivingEntity target, final String kind) {
        final Location from = mob.getEyeLocation();
        final Location to = SkillTargets.center(target);
        final org.bukkit.util.Vector step = to.toVector().subtract(from.toVector());
        final double length = step.length();
        if (length < 0.1) {
            return;
        }
        step.multiply(0.5 / length);
        final org.bukkit.Particle particle = switch (kind) {
            case "fire" -> org.bukkit.Particle.FLAME;
            case "cold" -> org.bukkit.Particle.SNOWFLAKE;
            case "lightning" -> org.bukkit.Particle.ELECTRIC_SPARK;
            case "chaos" -> org.bukkit.Particle.WITCH;
            default -> org.bukkit.Particle.CRIT;
        };
        final Location at = from.clone();
        for (double d = 0; d < length; d += 0.5) {
            tw.exilecore.skill.Fx.spawn(at.getWorld(), particle, at, 1, 0, 0, 0, 0);
            at.add(step);
        }
        if ("arrow".equals(kind)) {
            mob.getWorld().playSound(mob.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 0.4f, 1.2f);
        }
    }

    /** 治療（2026-10）：主人與附近 8 格內自己的召喚物回復最大生命的 heal%。 */
    private void healAllies(final Minion healer, final Player owner) {
        final double fraction = healer.spec.heal() / 100.0;
        final Location at = healer.mob.getLocation();
        if (owner.getWorld().equals(at.getWorld()) && owner.getLocation().distanceSquared(at) <= 64) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
            if (state.life() < state.maxLife()) {
                state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * fraction));
                plugin.players().display(owner, state);
                tw.exilecore.skill.Fx.spawn(owner.getWorld(), org.bukkit.Particle.HEART, owner.getLocation().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2, 0);
            }
        }
        for (final Minion other : minions) {
            if (other.owner.equals(healer.owner) && other.mob.isValid() && other.mob.getWorld().equals(at.getWorld())
                    && other.mob.getLocation().distanceSquared(at) <= 64) {
                healFraction(other.mob, fraction);
            }
        }
        tw.exilecore.skill.Fx.spawn(at.getWorld(), org.bukkit.Particle.HAPPY_VILLAGER, at.clone().add(0, 1.2, 0), 6, 0.6, 0.4, 0.6, 0);
    }

    private void idle(final Minion minion, final Player owner, final long now) {
        final Mob mob = minion.mob;
        if (mob.getTarget() != null) {
            mob.setTarget(null);
        }
        if (!(mob instanceof Vex)) {
            mob.setAggressive(false);
        }
        final Location home = owner.getLocation();
        final double distance = mob.getLocation().distance(home);
        if (minion.spec.flying()) {
            // 每隻在主人頭上各佔一個位置（照出生的 tick 排角度），不會全擠在同一點
            final double angle = Math.toRadians((minion.bornTick * 47) % 360);
            final Location hover = home.clone().add(Math.cos(angle) * 1.3, 2.0, Math.sin(angle) * 1.3);
            fly(mob, hover, hover.distance(mob.getLocation()) > 1.0 ? 0.8 : 0.15);
            ((Vex) mob).setCharging(false);
            return;
        }
        if (distance > FOLLOW_START) {
            if (now >= minion.nextPath) {
                minion.nextPath = now + 10;
                mob.getPathfinder().moveTo(home, distance > 12 ? 1.5 : 1.15); // 落後太多就用跑的
                minion.idleStopped = false;
            }
        } else if (distance <= FOLLOW_STOP && !minion.idleStopped) {
            mob.getPathfinder().stopPathfinding();
            minion.idleStopped = true;
        }
        if (now % 40 == minion.bornTick % 40) {
            mob.lookAt(owner);
        }
    }

    /** 會飛的：直接往目標飄（速度照倍率；靠近了就慢下來）。 */
    private void fly(final Mob mob, final Location to, final double factor) {
        final Vector delta = to.toVector().subtract(mob.getLocation().toVector());
        final double length = delta.length();
        if (length < 0.2) {
            mob.setVelocity(new Vector(0, 0, 0));
            return;
        }
        final double speed = Math.min(length, FLY_SPEED * factor * speedScale(mob));
        mob.setVelocity(delta.multiply(speed / length));
    }

    private double speedScale(final Mob mob) {
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null || speed.getBaseValue() <= 0) {
            return 1.0;
        }
        return Math.max(0.3, speed.getValue() / speed.getBaseValue());
    }

    private void teleportToOwner(final Minion minion, final Player owner) {
        final Vector facing = SkillTargets.facing(owner);
        Location spot = ground(owner.getLocation().subtract(facing.multiply(1.5)));
        if (spot == null) {
            spot = owner.getLocation();
        }
        if (minion.spec.flying()) {
            spot.add(0, 1.2, 0);
        }
        final boolean crossWorld = !minion.mob.getWorld().equals(owner.getWorld());
        minion.mob.teleport(spot);
        minion.target = null;
        minion.nextPath = 0;
        if (crossWorld) {
            tameGoals(minion.mob); // 跨世界是新的實體：原版 AI 要再拿掉
        }
        visuals.minionBlink(spot);
    }

    private boolean ownerInTown(final Player owner) {
        return plugin.skills() != null && plugin.skills().inTown(owner);
    }

    private boolean validTarget(final LivingEntity target, final Player owner, final Mob mob) {
        return targets.valid(target, owner) && target.getWorld().equals(mob.getWorld())
                && target.getLocation().distanceSquared(owner.getLocation()) <= LEASH * LEASH
                && target.getLocation().distanceSquared(mob.getLocation()) <= (AGGRO + 6) * (AGGRO + 6);
    }

    /** 主人剛打過的怪優先，不然是自己附近最近、離主人不會太遠的怪。 */
    private LivingEntity findTarget(final Minion minion, final Player owner) {
        final Focus focused = focus.get(owner.getUniqueId());
        if (focused != null && now() - focused.tick() <= FOCUS_TICKS) {
            final LivingEntity target = living(focused.target());
            if (target != null && validTarget(target, owner, minion.mob)) {
                return target;
            }
        }
        // 2026-10：找主人身邊 AGGRO 格內、離這隻召喚物最近的怪（以前是找召喚物自己身邊 16 格，常常跑去打畫面外的）
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        final org.bukkit.Location at = minion.mob.getLocation();
        for (final LivingEntity candidate : targets.around(owner.getLocation(), AGGRO, owner)) {
            if (!candidate.getWorld().equals(at.getWorld())) {
                continue;
            }
            final double distance = candidate.getLocation().distanceSquared(at);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 把附近的怪引過來打這隻（all = false 只引還沒有目標的）。 */
    private void taunt(final Minion minion, final double radius, final boolean all) {
        for (final LivingEntity near : targets.around(minion.mob.getLocation(), radius)) {
            if (near instanceof Mob monster && (all || monster.getTarget() == null)) {
                monster.setTarget(minion.mob);
            }
        }
    }

    private LivingEntity living(final UUID uuid) {
        final Entity entity = plugin.getServer().getEntity(uuid);
        return entity instanceof LivingEntity living && living.isValid() && !living.isDead() ? living : null;
    }

    /** 頭上的名字：有時間限制的後面寫剩幾秒（2026-10，跟圖騰一樣），血不滿時再加上百分比。每秒更新一次。 */
    private void syncName(final Minion minion, final long now) {
        final AttributeInstance health = minion.mob.getAttribute(Attribute.MAX_HEALTH);
        if (health == null || health.getValue() <= 0) {
            return;
        }
        final int percent = (int) Math.round(100 * Math.max(0, Math.min(1, minion.mob.getHealth() / health.getValue())));
        final StringBuilder text = new StringBuilder(minion.name);
        if (minion.untilTick > 0) {
            final long seconds = Math.max(0, (minion.untilTick - now + 19) / 20);
            text.append(" <white>").append(seconds).append("</white><gray> 秒");
        }
        if (percent < 100) {
            text.append(" <red>").append(percent).append('%');
        }
        minion.mob.customName(plugin.text().parse(text.toString()));
    }

    private static void healFraction(final Mob mob, final double fraction) {
        final AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health == null || mob.getHealth() >= health.getValue()) {
            return;
        }
        mob.setHealth(Math.min(health.getValue(), mob.getHealth() + health.getValue() * fraction));
    }

    // ---- 收掉 ----

    private enum Gone { EXPIRED, REPLACED, DIED, QUIET }

    private void remove(final Minion minion, final Gone why) {
        minions.remove(minion);
        byEntity.remove(minion.mob.getUniqueId());
        final Location at = minion.mob.getLocation();
        if (minion.mob.isValid() && why != Gone.DIED) {
            minion.mob.remove();
        }
        if (why != Gone.QUIET) {
            visuals.minionGone(minion.build.definition().visual(), at, minion.spec, why == Gone.DIED);
        }
        if (!minion.spec.buff().isEmpty()) {
            final Player owner = plugin.getServer().getPlayer(minion.owner);
            if (owner != null) {
                plugin.players().markDirty(owner);
            }
        }
        if (why != Gone.QUIET) {
            refreshHud(minion.owner);
        }
    }

    /** 某個玩家的全部收掉（登出、換角色）。 */
    void forget(final UUID owner) {
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner)) {
                remove(minion, Gone.QUIET);
            }
        }
        focus.remove(owner);
    }

    /** 全部收掉（插件停用）；也清掉上次沒收乾淨的。 */
    void clear() {
        for (final Minion minion : new ArrayList<>(minions)) {
            remove(minion, Gone.QUIET);
        }
        sweep();
    }

    /** 清掉世界裡帶著我們標記、但不在名單上的生物（上次當機留下的）。 */
    void sweep() {
        for (final World world : plugin.getServer().getWorlds()) {
            for (final LivingEntity entity : world.getLivingEntities()) {
                if (!(entity instanceof Player) && entity.getPersistentDataContainer().has(key) && !byEntity.containsKey(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
    }

    // ---- 給戰鬥模組（MinionHooks）----

    @Override
    public boolean isMinion(final Entity entity) {
        return entity != null && !(entity instanceof Player)
                && (byEntity.containsKey(entity.getUniqueId()) || entity.getPersistentDataContainer().has(key));
    }

    @Override
    public Attack attack(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        if (minion == null) {
            return null;
        }
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        return owner == null ? null : new Attack(owner, minion.sheet, minion.profile);
    }

    @Override
    public Player owner(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? null : plugin.getServer().getPlayer(minion.owner);
    }

    @Override
    public StatSheet sheet(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? null : minion.effective;
    }

    @Override
    public double healthScale(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? 1.0 : minion.healthScale;
    }

    /** owner 的召喚物（殘影不算）每隻回復 amount（2026-10：藥劑的「召喚物也回復」詞綴）。 */
    public void healOwned(final Player owner, final double amount) {
        if (amount <= 0) {
            return;
        }
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid()) {
                heal(minion.mob, amount);
            }
        }
    }

    @Override
    public void heal(final LivingEntity entity, final double amount) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        if (minion == null || amount <= 0) {
            return;
        }
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            entity.setHealth(Math.min(health.getValue(), entity.getHealth() + amount * minion.healthScale));
        }
    }

    // ---- 事件 ----

    /** 主人打中的怪：召喚物接下來優先打它。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (event.attacker() instanceof Player player && event.result().hit() && !(event.defender() instanceof Player)
                && !minions.isEmpty()) {
            focus.put(player.getUniqueId(), new Focus(event.defender().getUniqueId(), now()));
        }
    }

    /** 召喚物死了：不掉東西、不給經驗，從名單拿掉。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final EntityDeathEvent event) {
        final Minion minion = byEntity.get(event.getEntity().getUniqueId());
        if (minion == null) {
            if (isMinion(event.getEntity())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        remove(minion, Gone.DIED);
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner != null && !minion.decoy && plugin.skills() != null && plugin.skills().triggers() != null) {
            plugin.skills().triggers().minionDeath(owner, event.getEntity().getLocation()); // 2026-10：召喚物死亡觸發
            minionDied(owner, minion, event.getEntity().getLocation());
        }
    }

    /** 召喚物死掉時叫出牠的那個技能掛的輔助（2026-10）：觸發、回魔力（魂收）、治療附近的召喚物（骸恤）。 */
    private void minionDied(final Player owner, final Minion minion, final org.bukkit.Location at) {
        final SkillService skills = plugin.skills();
        final String skillId = minion.skillId();
        skills.procEvent(owner, skillId, "minion_death", at, null);
        final double mana = skills.supportFlat(owner, skillId, tw.exilecore.stats.StatKeys.MANA_ON_MINION_DEATH);
        if (mana > 0) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
            if (state.mana() < state.maxMana()) {
                state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * mana / 100.0));
                plugin.players().display(owner, state);
            }
        }
        final double heal = skills.supportFlat(owner, skillId, tw.exilecore.stats.StatKeys.MINION_DEATH_HEAL);
        if (heal > 0) {
            for (final Minion other : List.copyOf(byEntity.values())) {
                if (other == minion || !other.owner.equals(minion.owner) || other.decoy || !other.mob.isValid() || other.mob.isDead()
                        || !other.mob.getWorld().equals(at.getWorld()) || other.mob.getLocation().distanceSquared(at) > 9) {
                    continue;
                }
                final AttributeInstance health = other.mob.getAttribute(Attribute.MAX_HEALTH);
                final double max = health == null ? other.mob.getHealth() : health.getValue();
                other.mob.setHealth(Math.min(max, other.mob.getHealth() + max * heal / 100.0));
                if (plugin.ailments() != null) {
                    plugin.ailments().clear(other.mob);
                }
                other.mob.getWorld().spawnParticle(org.bukkit.Particle.HEART, other.mob.getLocation().add(0, other.mob.getHeight() + 0.2, 0), 2, 0.2, 0.1, 0.2, 0);
            }
        }
    }

    /** 召喚物不會被太陽燒。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(final EntityCombustEvent event) {
        if (event.getClass() == EntityCombustEvent.class && isMinion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 召喚物不變身（殭屍泡水變沉屍）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTransform(final EntityTransformEvent event) {
        if (isMinion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 召喚物不會把玩家或別的召喚物當目標（原版 AI 萬一回來了也擋住）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(final EntityTargetLivingEntityEvent event) {
        if (isMinion(event.getEntity()) && (event.getTarget() instanceof Player || isMinion(event.getTarget()))) {
            event.setCancelled(true);
        }
    }

    /** 召喚物打人不擊退（PoE 沒有擊退）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(final EntityKnockbackEvent event) {
        if (event instanceof EntityPushedByEntityAttackEvent pushed && isMinion(pushed.getPushedBy())) {
            event.setCancelled(true);
        }
    }

    /** 環境（火、岩漿、摔落、窒息…）不傷召喚物；掉進虛空、/kill 照舊。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnvironment(final EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || !isMinion(event.getEntity())) {
            return;
        }
        switch (event.getCause()) {
            case VOID, KILL, CUSTOM, WORLD_BORDER -> {
            }
            default -> event.setCancelled(true);
        }
    }
}
