package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 2026-10-24：消耗或產生戰星的輔助寶石（照 PoE2）。
 * <ul>
 *   <li>狂速（Ferocity）：放的時候消耗一顆怒星，這一下技能速度更多 40%</li>
 *   <li>久咒（Abiding Hex）：詛咒時消耗一顆銳星，持續時間更多 80%</li>
 *   <li>碎地（Jagged Ground）：猛擊時消耗一顆磐星，留下 4 秒的碎地（敵人減速 20%）</li>
 *   <li>蔓寒（Creeping Chill）：冰晶出現時消耗一顆銳星，冰晶周圍長出越來越大的冰緩地面</li>
 *   <li>雷星（Empowered Sparks）：產生銳星時強化接下來幾次攻擊，強化的攻擊放出游雷</li>
 *   <li>完磐（Perfected Endurance）：完美時機的那一下打中時獲得磐星</li>
 *   <li>祭星（Guatelitzi's Ablation）：祭柱每 4 秒給一顆銳星；放的時候獻上 25% 生命；祭柱倒了之後回復生命與魔力</li>
 * </ul>
 * 恆星、倍星、抑星在 {@link ChargeService#spend} 裡處理（每個消耗戰星的地方都走那裡）。
 */
public final class ChargeEffects implements Listener {

    /** 雷星放出來的游雷（隱藏技能；4 顆、6 顆各一個）。 */
    static final String SPARKS = "proc_empowered_sparks";
    static final String SPARKS_SIX = "proc_empowered_sparks_6";
    /** 碎地：基礎半徑（格，乘技能的範圍倍率）。 */
    static final double JAGGED_RADIUS = 2.5;
    /** 蔓寒：開始的半徑、每秒多 50%、最多多 300%。 */
    static final double CREEPING_RADIUS = 1.2;
    static final double CREEPING_GROWTH = 0.5;
    static final double CREEPING_MAX_GROWTH = 3.0;
    /** 冰晶的生命當作同等級一般怪物生命的幾倍（這裡的冰晶沒有生命，蔓寒照這個算冰緩）。 */
    static final double CRYSTAL_LIFE = 0.5;

    /** 這次放技能消耗戰星得到的東西。speedMore：技能速度的「更多」倍率（1 = 沒有）。 */
    public record OnUse(SkillBuild build, double speedMore) {
    }

    private record Empowered(int attacks, int projectiles) {
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Empowered> empowered = new HashMap<>();
    /** 2026-10-26 照 PoE2（崩震圖騰引爆碎地）：還在的碎地。 */
    private final List<JaggedPatch> jaggedPatches = new java.util.ArrayList<>();

    /** 一塊碎地：誰的、在哪、多大、到哪個 tick；alive[0] = false 時收掉（被引爆）。 */
    private record JaggedPatch(UUID owner, Location at, double radius, long end, boolean[] alive) {
    }

    /** 2026-10-27 照 PoE2 天賦樹（「對站在碎地上的敵人…」）：這隻站在還在的碎地上嗎（誰造的都算）。 */
    boolean onJaggedGround(final LivingEntity target) {
        final long now = now();
        final Location at = target.getLocation();
        for (final JaggedPatch patch : jaggedPatches) {
            if (patch.alive()[0] && patch.end() > now && patch.at().getWorld() == at.getWorld()
                    && patch.at().distanceSquared(at) <= patch.radius() * patch.radius()) {
                return true;
            }
        }
        return false;
    }

    ChargeEffects(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private ChargeService charges() {
        return plugin.combat() == null ? null : plugin.combat().charges();
    }

    /** 放技能的時候（已經付完消耗）：照輔助消耗戰星。 */
    OnUse onUse(final Player player, final SkillBuild build, final StatSheet sheet) {
        final ChargeService charges = charges();
        if (charges == null || build.definition().hidden() && !build.isDefault()) { // 普攻（隱藏的預設技能）也算攻擊
            return new OnUse(build, 1.0);
        }
        final StatSheet effective = build.profile().effective(sheet);
        final Set<String> tags = build.profile().tags();
        SkillBuild result = build;
        double speedMore = 1.0;
        final double ferocity = effective.flat(ChargeService.SUP_FEROCITY, tags);
        if (ferocity > 0) {
            final int benefit = charges.spend(player, "frenzy", effective, tags);
            speedMore += ferocity / 100.0 * benefit;
        }
        final double abiding = effective.flat(ChargeService.SUP_ABIDING_HEX, tags);
        if (abiding > 0 && tags.contains("curse")) {
            final int benefit = charges.spend(player, "power", effective, tags);
            if (benefit > 0) {
                result = result.withDurationScale(result.durationScale() * (1 + abiding / 100.0 * benefit));
            }
        }
        final double jagged = effective.flat(ChargeService.SUP_JAGGED, tags);
        if (jagged > 0 && tags.contains(Tags.SLAM)) {
            final int benefit = charges.spend(player, "endurance", effective, tags);
            if (benefit > 0) {
                final Vector facing = SkillTargets.facing(player).setY(0);
                final Location at = player.getLocation().add(facing.lengthSquared() < 1e-6 ? new Vector() : facing.normalize().multiply(2));
                final double radius = JAGGED_RADIUS * build.areaScale();
                // 2026-10-26 照 PoE2：天賦的碎地效果（減速幅度）、碎地持續時間
                final long ticks = tw.exilecore.combat.HitEffects.scaledTicks(Math.round(jagged * 20) * benefit,
                        effective.increased(tw.exilecore.stats.TreeMechanicKeys.JAGGED_DURATION, tags));
                final JaggedPatch patch = new JaggedPatch(player.getUniqueId(), at.clone(), radius, now() + ticks, new boolean[] {true});
                jaggedPatches.removeIf(old -> !old.alive()[0] || old.end() <= now());
                jaggedPatches.add(patch); // 2026-10-26 照 PoE2：記下來給崩震圖騰引爆
                ground(player, at, () -> patch.alive()[0], elapsed -> radius, jaggedSlow(effective.increased(tw.exilecore.stats.TreeMechanicKeys.JAGGED_EFFECT, tags)),
                        0, ticks, Particle.CRIT, effective.flat(tw.exilecore.stats.TreeNewMechanicKeys.JAGGED_TAKEN, tags) / 100.0);
            }
        }
        final double sacrifice = effective.flat(ChargeService.SUP_OFFERING_SACRIFICE, tags);
        if (sacrifice > 0 && build.definition().warcry() != null && build.definition().warcry().spike()) {
            final PlayerState state = plugin.players().get(player);
            state.setLife(Math.max(1, state.life() * (1 - Math.min(100, sacrifice) / 100.0)));
            plugin.players().display(player, state);
        }
        if (tags.contains(Tags.ATTACK)) {
            fireSparks(player, build);
        }
        return new OnUse(result, Math.max(0.1, speedMore));
    }

    /**
     * 2026-10-26 照 PoE2 Shockwave Totem：圖騰的猛擊（中心 center、半徑 reach）碰到 owner 造的碎地就引爆：
     * 那些碎地收掉，回傳它們的中心（引爆的傷害由圖騰那邊算）。
     */
    List<Location> eruptJagged(final Player owner, final Location center, final double reach) {
        final long now = now();
        jaggedPatches.removeIf(patch -> !patch.alive()[0] || patch.end() <= now);
        final List<Location> erupted = new java.util.ArrayList<>();
        final java.util.Iterator<JaggedPatch> it = jaggedPatches.iterator();
        while (it.hasNext()) {
            final JaggedPatch patch = it.next();
            if (!patch.owner().equals(owner.getUniqueId()) || patch.at().getWorld() == null || !patch.at().getWorld().equals(center.getWorld())) {
                continue;
            }
            final double touch = reach + patch.radius();
            if (patch.at().distanceSquared(center) <= touch * touch) {
                patch.alive()[0] = false;
                erupted.add(patch.at().clone());
                it.remove();
            }
        }
        return erupted;
    }

    /** 2026-10-26：碎地的減速（基礎 20%，乘 (1 + 碎地效果)，最多 90%）。 */
    static double jaggedSlow(final double increased) {
        return Math.min(0.9, JAGGED_SLOW * Math.max(0, 1 + increased));
    }

    static final double JAGGED_SLOW = 0.2;

    /** 雷星：產生了銳星，強化接下來 attacks 次攻擊（已經有的取較多的）。 */
    public void empower(final Player player, final int attacks, final int projectiles) {
        if (attacks <= 0) {
            return;
        }
        final Empowered old = empowered.get(player.getUniqueId());
        empowered.put(player.getUniqueId(), new Empowered(Math.max(attacks, old == null ? 0 : old.attacks()),
                Math.max(projectiles, old == null ? 0 : old.projectiles())));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.6f);
    }

    /** 強化的攻擊放出游雷（從玩家身上往四面八方）。 */
    private void fireSparks(final Player player, final SkillBuild build) {
        final Empowered left = empowered.get(player.getUniqueId());
        if (left == null) {
            return;
        }
        if (left.attacks() <= 1) {
            empowered.remove(player.getUniqueId());
        } else {
            empowered.put(player.getUniqueId(), new Empowered(left.attacks() - 1, left.projectiles()));
        }
        final SkillDefinition sub = service.config().skill(left.projectiles() >= 6 ? SPARKS_SIX : SPARKS);
        if (sub == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillBuild spark = SkillBuild.of(service.config(), sub, build.usableGemLevel(), state.level(), false, List.of(),
                state.weaponTags(), plugin.players().sheet(player));
        final Location from = player.getLocation().add(0, 1.0, 0);
        service.procCast(Origin.at(player, from, SkillTargets.facing(player)), spark, null);
    }

    /** 蔓寒：冰晶出現時（每次放一次，不是每顆）消耗一顆銳星，每顆冰晶周圍長出冰緩地面，冰晶在的時候都在。 */
    void creepingChill(final Player owner, final SkillBuild build, final List<Location> crystals, final List<BooleanSupplier> alive,
                       final long maxTicks) {
        final ChargeService charges = charges();
        if (charges == null || crystals.isEmpty()) {
            return;
        }
        final StatSheet effective = build.profile().effective(plugin.players().sheet(owner));
        final Set<String> tags = build.profile().tags();
        final double creeping = effective.flat(ChargeService.SUP_CREEPING, tags);
        if (creeping <= 0) {
            return;
        }
        final int benefit = charges.spend(owner, "power", effective, tags);
        if (benefit <= 0) {
            return;
        }
        final double chillAs = plugin.monsters() == null ? 0
                : plugin.monsters().config().lifeAt(Math.max(1, plugin.players().get(owner).level())) * CRYSTAL_LIFE * creeping / 100.0 * benefit;
        final double base = CREEPING_RADIUS * build.areaScale();
        for (int i = 0; i < crystals.size(); i++) {
            ground(owner, crystals.get(i), alive.get(i),
                    elapsed -> base * (1 + Math.min(CREEPING_MAX_GROWTH, CREEPING_GROWTH * elapsed / 20.0)), 0, chillAs, maxTicks, Particle.SNOWFLAKE);
        }
    }

    /** 一塊地面：每 10 tick 給範圍內的怪物減速（slow）或冰緩（chillAs），alive 不成立或時間到就收掉。 */
    private void ground(final Player owner, final Location at, final BooleanSupplier alive, final java.util.function.LongToDoubleFunction radius,
                        final double slow, final double chillAs, final long ticks, final Particle particle) {
        ground(owner, at, alive, radius, slow, chillAs, ticks, particle, 0);
    }

    /** 同上；taken：地上的敵人受到的傷害增加（2026-10-26 照 PoE2 天賦「在你造的碎地裡的敵人受到的傷害增加」）。 */
    private void ground(final Player owner, final Location at, final BooleanSupplier alive, final java.util.function.LongToDoubleFunction radius,
                        final double slow, final double chillAs, final long ticks, final Particle particle, final double taken) {
        if (at.getWorld() == null || plugin.ailments() == null) {
            return;
        }
        // 2026-10-27 效能：所有地面共用一個排程（fx.ground），每塊照自己的時間每 10 tick 一次（時間點跟以前一樣）；這次之前每塊冰晶一個排程
        grounds.add(new Ground(owner, at, alive, radius, slow, chillAs, ticks, particle, taken, now(), now() + 1));
        if (groundTask == null) {
            groundTask = plugin.repeat("fx.ground", this::tickGrounds, 1, 1);
        }
    }

    /** 一塊地面（{@link #ground}）。 */
    private record Ground(Player owner, Location at, BooleanSupplier alive, java.util.function.LongToDoubleFunction radius, double slow,
                          double chillAs, long ticks, Particle particle, double taken, long start, long[] next) {
        Ground(final Player owner, final Location at, final BooleanSupplier alive, final java.util.function.LongToDoubleFunction radius,
               final double slow, final double chillAs, final long ticks, final Particle particle, final double taken, final long start,
               final long first) {
            this(owner, at, alive, radius, slow, chillAs, ticks, particle, taken, start, new long[] {first});
        }
    }

    private final List<Ground> grounds = new java.util.ArrayList<>();
    private org.bukkit.scheduler.BukkitTask groundTask;

    private void tickGrounds() {
        final long now = now();
        for (final Ground ground : List.copyOf(grounds)) {
            if (now < ground.next()[0]) {
                continue;
            }
            ground.next()[0] += 10;
            if (!groundStep(ground, now)) {
                grounds.remove(ground);
            }
        }
        if (grounds.isEmpty() && groundTask != null) {
            groundTask.cancel();
            groundTask = null;
        }
    }

    /** 地面的一次（每 10 tick）：回傳還在不在。 */
    private boolean groundStep(final Ground ground, final long now) {
        final Player owner = ground.owner();
        final Location at = ground.at();
        final double slow = ground.slow();
        final double chillAs = ground.chillAs();
        final double taken = ground.taken();
        final Particle particle = ground.particle();
        {
            final long elapsed = now - ground.start();
            if (elapsed >= ground.ticks() || !ground.alive().getAsBoolean() || !owner.isOnline() || !owner.getWorld().equals(at.getWorld())) {
                return false;
            }
            final double r = ground.radius().applyAsDouble(elapsed);
            for (final LivingEntity target : service.targets().around(at, r, owner)) {
                if (!(target instanceof Player)) {
                    plugin.ailments().groundOnMonster(target, slow, chillAs, 15);
                    if (taken > 0 && plugin.combat() != null) {
                        plugin.combat().debuffs().apply(target.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("jagged." + owner.getUniqueId(),
                                owner.getUniqueId(), now() + 15, taken, false));
                    }
                }
            }
            final int points = (int) Math.max(8, Math.min(32, r * 6));
            for (int i = 0; i < points; i++) {
                final double a = Math.PI * 2 * i / points + elapsed * 0.03;
                Fx.spawn(at.getWorld(), particle, at.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, 0);
            }
        }
        return true;
    }

    /** 祭柱倒了（祭星）：每秒回復 percent% 生命與魔力，持續 seconds 秒（祭柱活了多久）。 */
    void offeringRegen(final Player player, final double percent, final int seconds) {
        if (percent <= 0 || seconds <= 0) {
            return;
        }
        final int[] left = {seconds};
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (left[0]-- <= 0 || !player.isOnline() || player.isDead()) {
                task[0].cancel();
                return;
            }
            final PlayerState state = plugin.players().get(player);
            state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * percent / 100.0));
            state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * percent / 100.0));
            plugin.players().display(player, state);
        }, 20, 20);
    }

    /**
     * 打中時：完磐（完美時機的那一下給磐星）、回槍躍（打中被招架的敵人用掉招架、放震波、給怒星，charge.on_parried.代號）。
     * 同一下打中好幾個只算一次。
     */
    /** 2026-10-28：回槍躍多的那一道震波隔幾 tick（觸發技能自己有短冷卻，同一 tick 放不出第二道）。 */
    static final long EXTRA_SHOCKWAVE_DELAY = 4;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit()
                || event.skill().tags().contains(Tags.TOTEM_HIT)) {
            return;
        }
        final ChargeService charges = charges();
        if (charges == null) {
            return;
        }
        final StatSheet effective = event.skill().effective(plugin.players().sheet(player));
        final long now = now();
        if (event.skill().id().endsWith(SupportProcs.PERFECT_SUFFIX)) {
            final double gain = effective.flat(ChargeService.SUP_PERFECT_ENDURANCE, event.skill().tags());
            if (gain > 0 && perfectTick.getOrDefault(player.getUniqueId(), -1L) != now) {
                perfectTick.put(player.getUniqueId(), now);
                charges.grant(player, "endurance", (int) Math.round(gain));
            }
        }
        // 回槍躍（PoE2 Disengage）：打中被招架的敵人，用掉招架、在牠身上放震波、給怒星（一次施放只給一次戰星）
        final LivingEntity target = event.defender();
        double base = 0;
        String charge = null;
        for (final tw.exilecore.charge.ChargeType type : charges.config().types()) {
            final double value = effective.flat(ChargeService.ON_PARRIED + type.id(), event.skill().tags());
            if (value > 0) {
                base = value;
                charge = type.id();
                break;
            }
        }
        if (charge == null || plugin.combat() == null
                || !plugin.combat().debuffs().remove(target.getUniqueId(), PARRIED, player.getUniqueId(), now)) {
            return;
        }
        final String skillId = SupportProcs.baseId(event.skill().id());
        service.procs().castAt(player, DISENGAGE_SHOCKWAVE, skillId, target.getLocation());
        // 2026-10-28 照 PoE2 Disengage 的品質：(0—20)% 機率多一道震波（緊接著在同一個地方）
        final double extraWave = effective.flat(tw.exilecore.stats.GemQualityKeys.EXTRA_SHOCKWAVE, event.skill().tags());
        if (extraWave > 0 && plugin.random().nextDouble() * 100 < extraWave) {
            final org.bukkit.Location again = target.getLocation();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    service.procs().castAtNow(player, DISENGAGE_SHOCKWAVE, skillId, again);
                }
            }, EXTRA_SHOCKWAVE_DELAY);
        }
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 0.8f, 1.6f);
        if (parriedTick.getOrDefault(player.getUniqueId(), -100L) < now - PARRIED_WINDOW) {
            parriedTick.put(player.getUniqueId(), now);
            charges.grant(player, charge, parriedCount(base, gemLevel(player, skillId)));
        }
    }

    /**
     * 2026-10-24（剔殘，PoE2 Cull the Weak）：被技能打死的敵人（SupportProcs 記的最後一下）：那個技能寫了 charge.event.kill.代號 就給戰星
     * （稀有怪多一顆、傳奇怪多兩顆；ChargeService.onEvent 處理）。只看技能自己帶的，掛的輔助不算。
     */
    void onKill(final Player player, final String skillId, final LivingEntity victim) {
        final ChargeService charges = charges();
        final SkillDefinition definition = service.config().skill(skillId);
        if (charges == null || definition == null || definition.modifiers().stream()
                .noneMatch(modifier -> modifier.stat().startsWith(ChargeService.EVENT + "kill."))) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        if (position < 0) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild build = service.build(state, sheet, definition, state.skills().skill(position));
        charges.onEvent(player, "kill", build.profile().effective(sheet), build.profile().tags(), victim);
    }

    /** 回槍躍（照 PoE2 的 Disengage）：基本顆數，寶石 8 級多一顆、16 級再多一顆。 */
    static int parriedCount(final double base, final int gemLevel) {
        return (int) Math.round(base) + (gemLevel >= 8 ? 1 : 0) + (gemLevel >= 16 ? 1 : 0);
    }

    /** 技能面板上這個技能的寶石等級（沒有放在面板上算 1）。 */
    private int gemLevel(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        // 2026-10-27 照站長：屬性不夠時照自動降到的等級（這次之前照寶石本身的等級）
        return position < 0 ? 1 : GemAttributes.usableLevel(service.config(), state.skills().skill(position), plugin.players().sheet(player));
    }

    // ---- 2026-10-24：招架（PoE2 Parry）與熔殼（PoE2 Magma Barrier）----

    /** 「被招架」減益的代號（Debuffs）；受到的攻擊傷害增加 50%、2 秒（照 PoE2）。 */
    static final String PARRIED = "parried";
    static final double PARRIED_TAKEN = 0.5;
    static final long PARRIED_TICKS = 40;
    static final String PARRY_SWEEP = "proc_parry_sweep";
    static final String DISENGAGE_SHOCKWAVE = "proc_disengage_shockwave";
    static final String MAGMA_SPRAY = "proc_magma_spray";
    /** 熔殼灌滿熔岩要幾 tick（PoE2：8 秒）。 */
    static final long MAGMA_IMBUE_TICKS = 160;

    /** 熔殼：玩家 → 上一次噴出熔岩的 tick（從這時候重新開始灌）。 */
    private final Map<UUID, Long> magmaSpent = new HashMap<>();

    /**
     * 玩家格擋了一擊（CombatListener 呼叫）。
     * 招架中：攻擊者「被招架」2 秒（受到的攻擊傷害增加 50%），揮盾橫掃身邊的敵人，舉盾結束。
     * 熔殼開著、盾牌灌滿熔岩：噴出熔岩、給一顆磐星，重新開始灌。
     */
    public void onBlock(final Player player, final LivingEntity attacker, final StatSheet sheet) {
        final long now = now();
        if (service.treeMechanics() != null) {
            // 2026-10-26 照 PoE2 天賦：舉盾格擋時眩暈攻擊者、招架之後的下一次遠程／近戰攻擊
            service.treeMechanics().onBlock(player, attacker, sheet, service.buffActivePrefix(player, "guard"),
                    tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_PARRY));
        }
        // 2026-10-26：天賦樹「最近舉盾擋下過」「最近成功招架」
        // 2026-10-26：架盾（channel_block，按住的舉盾）也算舉盾擋下（盾擊看這個）；這次之前只看 guard 開頭的
        plugin.players().conditions().onBlocked(player, service.buffActivePrefix(player, "guard") || service.buffActivePrefix(player, "channel_block"),
                tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_PARRY), now);
        if (tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_PARRY)) {
            service.buffs().remove(player, "guard"); // 招架只擋一下
            if (attacker != null && !(attacker instanceof Player) && plugin.combat() != null) {
                // 2026-10-26 照 PoE2：天賦的「被招架」效果、持續時間（以前固定 50%、2 秒）
                plugin.combat().debuffs().apply(attacker.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff(PARRIED, player.getUniqueId(),
                        now + parriedTicks(sheet), parriedTaken(sheet), false, Tags.ATTACK));
                attacker.getWorld().spawnParticle(Particle.CRIT, attacker.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.1);
            }
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1.3f);
            service.procs().castAt(player, PARRY_SWEEP, "parry", player.getLocation());
            plugin.players().markDirty(player);
        }
        if (tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_MAGMA_BARRIER)) {
            final long since = Math.max(service.auraSince(player, "magma_barrier"), magmaSpent.getOrDefault(player.getUniqueId(), Long.MIN_VALUE));
            if (since >= 0 && now - since >= MAGMA_IMBUE_TICKS) {
                magmaSpent.put(player.getUniqueId(), now);
                service.procs().castAt(player, MAGMA_SPRAY, "magma_barrier", player.getLocation());
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_BUCKET_EMPTY_LAVA, SoundCategory.PLAYERS, 1f, 1f);
                player.getWorld().spawnParticle(Particle.LAVA, player.getLocation().add(0, 1, 0), 16, 1.2, 0.3, 1.2, 0);
                final ChargeService charges = charges();
                if (charges != null) {
                    charges.grant(player, "endurance", 1);
                }
            }
        }
    }

    /** 2026-10-26：「被招架」受到的攻擊傷害增加多少（50% × (1 + 被招架效果)）。 */
    static double parriedTaken(final StatSheet sheet) {
        return PARRIED_TAKEN * Math.max(0, 1 + sheet.increased(tw.exilecore.stats.TreeMechanicKeys.PARRIED_EFFECT, Set.of()));
    }

    /** 2026-10-26：「被招架」持續幾 tick（2 秒 × (1 + 被招架持續時間)）。 */
    static long parriedTicks(final StatSheet sheet) {
        return Math.max(1, Math.round(PARRIED_TICKS * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.TreeMechanicKeys.PARRIED_DURATION, Set.of()))));
    }

    /** 熔殼的盾牌灌滿熔岩了沒（動作列、技能說明用）。 */
    public boolean magmaReady(final Player player) {
        final long since = Math.max(service.auraSince(player, "magma_barrier"), magmaSpent.getOrDefault(player.getUniqueId(), Long.MIN_VALUE));
        return since >= 0 && now() - since >= MAGMA_IMBUE_TICKS;
    }

    /** 回槍躍：一次施放只給一次戰星（同一次的打擊都在這幾 tick 內）。 */
    static final long PARRIED_WINDOW = 5;

    private final Map<UUID, Long> perfectTick = new HashMap<>();
    private final Map<UUID, Long> parriedTick = new HashMap<>();

    // ---- 2026-10-24：幻身（PoE2 Lingering Illusion）----

    /** 翻滾時留下的幻身（隱藏的召喚技能）。 */
    static final String ILLUSION = "proc_lingering_illusion";
    /** 翻滾時留下幻身：光環給的旗標、每次花多少魔力（PoE2：10）。 */
    static final String FLAG_ILLUSION = "flag.lingering_illusion";
    static final double ILLUSION_MANA = 10;
    /** 2026-10-27：幻身下次能留的 tick（玩家 → tick）。 */
    private final Map<UUID, Long> illusionReady = new HashMap<>();
    /** 幻身被打掉時多一個殘影的機率（光環給，FLAT 百分比）。 */
    static final String REMNANT_EXTRA = "charge.remnant_extra";
    /** 殘影：撐幾 tick、多近算撿到。 */
    static final long REMNANT_TICKS = 100;
    static final double REMNANT_PICKUP = 1.6;

    /** 翻滾時（SkillTriggers 呼叫）：開著幻身的人花 10 魔力在原地留下一個會引怪的幻身。 */
    void onDodge(final Player player, final StatSheet sheet) {
        if (!tw.exilecore.stats.StatKeys.flag(sheet, FLAG_ILLUSION) || service.minions() == null) {
            return;
        }
        // 2026-10-27：幻身的冷卻（跟 SupportProcs.decoy 一樣的算法）先看，冷卻中不花魔力；這次之前先扣 10 魔力，冷卻中什麼都沒留下
        final long now = now();
        if (illusionReady.getOrDefault(player.getUniqueId(), 0L) > now) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (state.mana() < ILLUSION_MANA) {
            return;
        }
        final SkillDefinition sub = service.config().skill(ILLUSION);
        if (illusionReady.size() > 256) {
            illusionReady.values().removeIf(tick -> tick <= now);
        }
        illusionReady.put(player.getUniqueId(), now + Math.max(SupportProcs.COOLDOWN, sub == null ? 0 : Math.round(sub.cooldown() * 20)));
        state.setMana(state.mana() - ILLUSION_MANA);
        plugin.players().display(player, state);
        service.procs().decoy(player, ILLUSION, "lingering_illusion", player.getLocation());
    }

    /** 幻身被敵人打掉（MinionService 呼叫）：留下殘影（機率多一個），主人撿到給一顆銳星。 */
    void illusionDied(final Player owner, final SkillBuild build, final Location at) {
        final double extra = plugin.players().sheet(owner).flat(REMNANT_EXTRA, Set.of());
        final int count = 1 + (plugin.random().nextDouble() * 100 < extra ? 1 : 0);
        for (int i = 0; i < count; i++) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final Location spot = at.clone().add(count > 1 ? Math.cos(angle) * 0.8 : 0, 0.4, count > 1 ? Math.sin(angle) * 0.8 : 0);
            dropRemnant(owner, spot, "power");
        }
    }

    /** 一個殘影：發光的小水晶，主人走過去撿到就給那種戰星；時間到消失。 */
    private void dropRemnant(final Player owner, final Location at, final String charge) {
        dropRemnant(owner, at, org.bukkit.Material.AMETHYST_SHARD, REMNANT_TICKS, true, RemnantMods.NONE, times -> {
            final ChargeService charges = charges();
            if (charges != null) {
                charges.grant(owner, charge, times);
            }
        });
    }

    // ---- 2026-10-26 照 PoE2：元素灌注殘骸（Elemental Infusion Remnant）----

    /** 灌注殘骸撐幾 tick（PoE2 沒寫明；跟殘影一樣放久一點：8 秒）。 */
    static final long INFUSION_REMNANT_TICKS = 160;
    private static final org.bukkit.Material[] INFUSION_REMNANT_ICONS = {
            org.bukkit.Material.BLAZE_POWDER, org.bukkit.Material.PRISMARINE_CRYSTALS, org.bukkit.Material.GLOWSTONE_DUST};

    /**
     * 在 at 留下一個灌注殘骸（element：0 火、1 冰、2 電），主人走過去撿到獲得那種灌注（SkillResources#collectInfusion）。
     * 基石「元素之相」：產生的殘骸換一種（火→電、電→冰、冰→火）。
     */
    void dropInfusionRemnant(final Player owner, final Location at, final int rawElement) {
        dropInfusionRemnant(owner, at, rawElement, RemnantMods.NONE);
    }

    /**
     * 2026-10-27 照 PoE2 殘骸輔助（Harmonic／Magnetic Remnants、Remnant Potency）：產生殘骸的技能帶的——x% 機率多產生一個、
     * 能從更遠的地方撿、效果增加、撿到幾秒後才生效。這次之前是「放技能後 12 秒內撿到的殘骸」都算的增益。
     */
    void dropInfusionRemnant(final Player owner, final Location at, final int rawElement, final RemnantMods mods) {
        if (rawElement < 0 || rawElement > 2 || owner == null || !owner.isOnline()) {
            return;
        }
        final int element = tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(owner), tw.exilecore.stats.KeystoneKeys.FLAG_ELEMENTAL_EQUILIBRIUM)
                ? tw.exilecore.stats.KeystoneKeys.equilibrium(rawElement) : rawElement;
        final int count = 1 + (mods.extra() > 0 && plugin.random().nextDouble() * 100 < mods.extra() ? 1 : 0);
        for (int i = 0; i < count; i++) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final Location spot = at.clone().add(i > 0 ? Math.cos(angle) * 0.8 : 0, 0.4, i > 0 ? Math.sin(angle) * 0.8 : 0);
            dropRemnant(owner, spot, INFUSION_REMNANT_ICONS[element], INFUSION_REMNANT_TICKS, true, mods,
                    times -> service.resources().collectInfusion(owner, element, times));
        }
    }

    /**
     * 2026-10-27：殘骸輔助給這個殘骸的：range／effect＝增加 x%（撿取距離、效果），extra＝多產生一個的機率 %，delaySeconds＝撿到幾秒後才生效。
     */
    public record RemnantMods(double range, double extra, double effect, double delaySeconds) {
        public static final RemnantMods NONE = new RemnantMods(0, 0, 0, 0);

        /** 產生殘骸的技能的屬性表（含輔助）。 */
        public static RemnantMods of(final StatSheet skillSheet, final Set<String> tags) {
            if (skillSheet == null) {
                return NONE;
            }
            return new RemnantMods(skillSheet.flat(tw.exilecore.stats.SupportExtraKeys.REMNANT_RANGE, tags),
                    skillSheet.flat(tw.exilecore.stats.SupportExtraKeys.REMNANT_EXTRA, tags),
                    skillSheet.flat(tw.exilecore.stats.SupportExtraKeys.REMNANT_EFFECT, tags),
                    skillSheet.flat(tw.exilecore.stats.SupportExtraKeys.REMNANT_DELAY, tags));
        }
    }

    /** 2026-10-26（骸餘饗的殘骸，OriginalSkills）：別的系統的殘骸也走同一套（撿取距離、殘影效果、再出現）。 */
    void dropCustomRemnant(final Player owner, final Location at, final org.bukkit.Material icon, final java.util.function.IntConsumer onPickup) {
        dropRemnant(owner, at, icon, INFUSION_REMNANT_TICKS, true, RemnantMods.NONE, onPickup);
    }

    /** 2026-10-26 照 PoE2 風暴回憶（Storm's Recollection）：你產生的殘骸被撿走 x 毫秒後再出現一次（FLAT 毫秒）。 */
    public static final String REMNANT_REAPPEAR = "remnant.reappear_ms";

    /** 2026-10-27：殘影周圍的圈有幾個點、多大；最後幾 tick 開始閃爍。 */
    static final int REMNANT_RING_POINTS = 12;
    static final double REMNANT_RING_RADIUS = 0.45;
    static final long REMNANT_BLINK_TICKS = 40;

    /** 殘影的元素顏色：火橘、冰青、電黃、暴能球紫，其他白。 */
    static org.bukkit.Color remnantColour(final org.bukkit.Material icon) {
        return switch (icon) {
            case BLAZE_POWDER -> org.bukkit.Color.fromRGB(0xff6a1a);
            case PRISMARINE_CRYSTALS -> org.bukkit.Color.fromRGB(0x6fd8ff);
            case GLOWSTONE_DUST -> org.bukkit.Color.fromRGB(0xffe24a);
            case AMETHYST_SHARD -> org.bukkit.Color.fromRGB(0xb36bff);
            default -> org.bukkit.Color.fromRGB(0xf0f0f0);
        };
    }

    /** 殘影／殘骸的共用部分：發光的小東西，主人走過去撿到就跑 onPickup（給幾次）；時間到消失。canReappear：撿走後可以再出現一次。 */
    private void dropRemnant(final Player owner, final Location at, final org.bukkit.Material icon, final long ticks, final boolean canReappear,
                             final RemnantMods mods, final java.util.function.IntConsumer onPickup) {
        if (at.getWorld() == null) {
            return;
        }
        final org.bukkit.inventory.ItemStack shown = new org.bukkit.inventory.ItemStack(icon);
        final org.bukkit.entity.ItemDisplay display = at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setPersistent(false);
            d.setVisibleByDefault(false); // 2026-10-27 照站長：只有放的人看得到
            d.setGlowing(true);
            d.setGlowColorOverride(remnantColour(icon));
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.5f, 0.5f, 0.5f), new org.joml.Quaternionf()));
        });
        owner.showEntity(plugin, display);
        trackRemnant(owner, display); // 2026-10-27 效能：每人最多 MAX_REMNANTS 個
        // 2026-10-27 效能：所有殘影共用一個每 2 tick 的排程（fx.remnant）；這次之前每個殘影一個排程，每次都讀一次屬性表
        remnants.add(new Remnant(owner, display, at, icon, shown, new org.bukkit.Particle.DustOptions(remnantColour(icon), 0.7f),
                now() + ticks, ticks, canReappear, mods, onPickup));
        if (remnantTask == null) {
            remnantTask = plugin.repeat("fx.remnant", this::tickRemnants, 2, 2);
        }
    }

    /** 2026-10-27 效能：每個人只畫離他最近的幾個殘影周圍的圈（每 4 tick 一次，跟以前一樣）；其他的照樣發光、閃爍、撿得到。 */
    static final int REMNANT_RING_NEAREST = 6;
    /** 閃爍藏起來那一下顯示的空物品（用到才建：類別載入時伺服器可能還沒好，單元測試也沒有伺服器）。 */
    private org.bukkit.inventory.ItemStack remnantHidden;

    /** 地上的一個殘影／殘骸。 */
    private static final class Remnant {
        final Player owner;
        final org.bukkit.entity.ItemDisplay display;
        final Location at;
        final org.bukkit.Material icon;
        final org.bukkit.inventory.ItemStack shown;
        final org.bukkit.Particle.DustOptions dust;
        final long until;
        final long ticks;
        final boolean canReappear;
        /** 2026-10-27：殘骸輔助給的（撿取距離、效果、延遲）。 */
        final RemnantMods mods;
        final java.util.function.IntConsumer onPickup;
        /** 過了幾次（每 2 tick 一次）。 */
        int step;
        /** 目前是不是藏起來（閃爍）。 */
        boolean off;

        Remnant(final Player owner, final org.bukkit.entity.ItemDisplay display, final Location at, final org.bukkit.Material icon,
                final org.bukkit.inventory.ItemStack shown, final org.bukkit.Particle.DustOptions dust, final long until, final long ticks,
                final boolean canReappear, final RemnantMods mods, final java.util.function.IntConsumer onPickup) {
            this.owner = owner;
            this.mods = mods;
            this.display = display;
            this.at = at;
            this.icon = icon;
            this.shown = shown;
            this.dust = dust;
            this.until = until;
            this.ticks = ticks;
            this.canReappear = canReappear;
            this.onPickup = onPickup;
        }
    }

    private final List<Remnant> remnants = new java.util.ArrayList<>();
    private org.bukkit.scheduler.BukkitTask remnantTask;
    /** 撿取距離的快取：玩家 → {屬性表版本, 增加的撿取距離}（屬性表沒變就不重算；不會為了這個強迫重算屬性表）。 */
    private final Map<UUID, double[]> pickupRange = new HashMap<>();

    /** 每 2 tick：所有殘影的閃爍、周圍的圈、撿取。 */
    private void tickRemnants() {
        final long now = now();
        final List<Remnant> live = new java.util.ArrayList<>(remnants.size());
        for (final Remnant remnant : List.copyOf(remnants)) {
            if (!remnant.display.isValid() || now >= remnant.until || !remnant.owner.isOnline()) {
                remnants.remove(remnant);
                if (remnant.display.isValid()) {
                    remnant.display.remove();
                }
                continue;
            }
            remnant.step++;
            final long left = remnant.until - now;
            // 2026-10-27 照站長：周圍一圈元素顏色（只畫給主人看）；最後 2 秒開始閃爍（越接近消失閃得越快）
            final boolean blinking = left <= REMNANT_BLINK_TICKS;
            final boolean off = blinking && (remnant.step / (left <= REMNANT_BLINK_TICKS / 2 ? 1 : 2)) % 2 == 1;
            if (off != remnant.off) { // 狀態變了才送更新
                if (remnantHidden == null) {
                    remnantHidden = new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR);
                }
                remnant.display.setItemStack(off ? remnantHidden : remnant.shown);
                remnant.off = off;
            }
            live.add(remnant);
        }
        if ((now / 2) % 2 == 0) {
            drawRemnantRings(live);
        }
        for (final Remnant remnant : live) {
            if (remnant.display.isValid()) {
                checkPickup(remnant);
            }
        }
        if (remnants.isEmpty() && remnantTask != null) {
            remnantTask.cancel();
            remnantTask = null;
            pickupRange.clear();
        }
    }

    /** 周圍的圈：每個人只畫離他最近的 {@link #REMNANT_RING_NEAREST} 個（藏起來那一下不畫）。 */
    private void drawRemnantRings(final List<Remnant> live) {
        final Map<UUID, List<Remnant>> byOwner = new HashMap<>();
        for (final Remnant remnant : live) {
            if (!remnant.off && remnant.owner.getWorld().equals(remnant.at.getWorld())) {
                byOwner.computeIfAbsent(remnant.owner.getUniqueId(), id -> new java.util.ArrayList<>()).add(remnant);
            }
        }
        for (final List<Remnant> mine : byOwner.values()) {
            final Location from = mine.get(0).owner.getLocation();
            if (mine.size() > REMNANT_RING_NEAREST) {
                mine.sort(java.util.Comparator.comparingDouble(remnant -> remnant.at.distanceSquared(from)));
            }
            for (int n = 0; n < Math.min(REMNANT_RING_NEAREST, mine.size()); n++) {
                final Remnant remnant = mine.get(n);
                for (int i = 0; i < REMNANT_RING_POINTS; i++) {
                    final double angle = Math.PI * 2 * i / REMNANT_RING_POINTS + remnant.step * 0.15;
                    remnant.owner.spawnParticle(org.bukkit.Particle.DUST, remnant.at.getX() + Math.cos(angle) * REMNANT_RING_RADIUS,
                            remnant.at.getY() - 0.2, remnant.at.getZ() + Math.sin(angle) * REMNANT_RING_RADIUS, 1, 0, 0, 0, 0, remnant.dust);
                }
            }
        }
    }

    /** 撿取距離增加多少（屬性表版本沒變就用記下來的）。 */
    private double pickupIncreased(final Player owner) {
        final StatSheet sheet = plugin.players().get(owner).sheet();
        final double[] cached = pickupRange.get(owner.getUniqueId());
        if (cached != null && cached[0] == sheet.version()) {
            return cached[1];
        }
        final double increased = sheet.increased(tw.exilecore.stats.TreeMechanicKeys.REMNANT_RANGE, Set.of()); // 2026-10-26 照 PoE2：天賦「殘影可以從更遠的地方撿」
        pickupRange.put(owner.getUniqueId(), new double[] {sheet.version(), increased});
        return increased;
    }

    private void checkPickup(final Remnant remnant) {
        final Player owner = remnant.owner;
        final Location at = remnant.at;
        if (!owner.getWorld().equals(at.getWorld())) {
            return;
        }
        final double pickup = remnantPickup(pickupIncreased(owner) + remnant.mods.range() / 100.0); // 2026-10-27：＋殘骸輔助（磁骸、和骸）
        if (owner.getLocation().distanceSquared(at) > pickup * pickup) {
            return;
        }
        remnants.remove(remnant);
        remnant.display.remove();
        final StatSheet sheet = plugin.players().sheet(owner);
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.8f);
        // 2026-10-26 照 PoE2：殘影的效果有機率給兩次；撿到時回復生命、魔力
        int times = plugin.random().nextDouble() * 100 < sheet.flat(tw.exilecore.stats.TreeMechanicKeys.REMNANT_DOUBLE, Set.of()) ? 2 : 1;
        // 2026-10-26 照 PoE2 天賦「你造的殘影效果增加 x%」：x% 機率多給一顆（期望值同樣多 x%）；2026-10-27：＋骸效（Remnant Potency）
        final double effect = sheet.increased(tw.exilecore.stats.TreeNewMechanicKeys.REMNANT_EFFECT, Set.of()) + remnant.mods.effect() / 100.0;
        if (effect > 0) {
            times += TreeMechanics.surpassing(effect * 100, plugin.random().nextDouble());
        }
        if (remnant.mods.delaySeconds() > 0) {
            // 2026-10-27 照 PoE2 Remnant Potency：撿到 x 秒後才生效
            final int given = times;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (owner.isOnline()) {
                    remnant.onPickup.accept(given);
                }
            }, Math.max(1, Math.round(remnant.mods.delaySeconds() * 20)));
        } else {
            remnant.onPickup.accept(times);
        }
        remnantRecovery(owner, sheet);
        service.supportMechanics().lineage().remnantCollected(owner); // 2026-10-27 照 PoE2 拾息：撿到殘骸時冷卻回復速度增加
        final double reappear = sheet.flat(REMNANT_REAPPEAR, Set.of());
        if (remnant.canReappear && reappear > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (owner.isOnline()) {
                    dropRemnant(owner, at, remnant.icon, remnant.ticks, false, remnant.mods, remnant.onPickup);
                }
            }, Math.max(1, Math.round(reappear / 50.0)));
        }
    }

    /** 2026-10-26：撿殘影的距離（格）：1.6 × (1 + 撿取距離)。 */
    static double remnantPickup(final StatSheet sheet) {
        return remnantPickup(sheet.increased(tw.exilecore.stats.TreeMechanicKeys.REMNANT_RANGE, Set.of()));
    }

    /** 撿取距離（格）：1.6 ×（1 ＋ 增加的撿取距離）。 */
    static double remnantPickup(final double increased) {
        return REMNANT_PICKUP * Math.max(0.1, 1 + increased);
    }

    /** 2026-10-26：撿到殘影時回復最大生命／魔力的 x%（天賦）。 */
    private void remnantRecovery(final Player owner, final StatSheet sheet) {
        final double life = sheet.flat(tw.exilecore.stats.TreeMechanicKeys.REMNANT_LIFE, Set.of());
        final double mana = sheet.flat(tw.exilecore.stats.TreeMechanicKeys.REMNANT_MANA, Set.of());
        if (life <= 0 && mana <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(owner);
        final double effect = Math.max(0, 1 + sheet.increased(tw.exilecore.stats.TreeNewMechanicKeys.REMNANT_EFFECT, Set.of())); // 2026-10-26：殘影效果
        if (life > 0) {
            state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * life * effect / 100.0));
        }
        if (mana > 0) {
            state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * mana * effect / 100.0));
        }
        plugin.players().display(owner, state);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        empowered.remove(event.getPlayer().getUniqueId());
        perfectTick.remove(event.getPlayer().getUniqueId());
        parriedTick.remove(event.getPlayer().getUniqueId());
        magmaSpent.remove(event.getPlayer().getUniqueId());
        jaggedPatches.removeIf(patch -> patch.owner().equals(event.getPlayer().getUniqueId())); // 2026-10-26
        liveRemnants.remove(event.getPlayer().getUniqueId()); // 2026-10-27（實體本身由共用的排程看到主人下線就收掉）
        pickupRange.remove(event.getPlayer().getUniqueId());
    }

    // ---- 2026-10-27 效能：每個人地上的殘影／殘骸最多這麼多個（每個都是一個發光實體 + 每 2 tick 一個排程）----

    /** 每個人同時最多幾個殘影／殘骸；再多就收掉最舊的。這次之前沒有上限（素痕湧對一大群點燃、冰凍時一次掉幾十個）。 */
    static final int MAX_REMNANTS = 24;
    private final Map<UUID, java.util.ArrayDeque<org.bukkit.entity.Entity>> liveRemnants = new HashMap<>();

    /** 記下新的殘影；超過上限的最舊那個收掉（它的排程看到實體沒了就自己停）。 */
    private void trackRemnant(final Player owner, final org.bukkit.entity.Entity display) {
        final java.util.ArrayDeque<org.bukkit.entity.Entity> mine = liveRemnants.computeIfAbsent(owner.getUniqueId(), id -> new java.util.ArrayDeque<>());
        mine.removeIf(entity -> !entity.isValid());
        while (mine.size() >= MAX_REMNANTS) {
            final org.bukkit.entity.Entity oldest = mine.pollFirst();
            if (oldest != null && oldest.isValid()) {
                oldest.remove();
            }
        }
        mine.addLast(display);
    }
}
