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
                final long ticks = Math.round(jagged * 20) * benefit;
                ground(player, at, () -> true, elapsed -> radius, 0.2, 0, ticks, Particle.CRIT);
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
        final SkillBuild spark = SkillBuild.of(service.config(), sub, Math.max(1, build.gemLevel()), state.level(), false, List.of(),
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
        if (at.getWorld() == null || plugin.ailments() == null) {
            return;
        }
        final long start = now();
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final long elapsed = now() - start;
            if (elapsed >= ticks || !alive.getAsBoolean() || !owner.isOnline() || !owner.getWorld().equals(at.getWorld())) {
                task[0].cancel();
                return;
            }
            final double r = radius.applyAsDouble(elapsed);
            for (final LivingEntity target : service.targets().around(at, r, owner)) {
                if (!(target instanceof Player)) {
                    plugin.ailments().groundOnMonster(target, slow, chillAs, 15);
                }
            }
            final int points = (int) Math.max(8, Math.min(32, r * 6));
            for (int i = 0; i < points; i++) {
                final double a = Math.PI * 2 * i / points + elapsed * 0.03;
                Fx.spawn(at.getWorld(), particle, at.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, 0);
            }
        }, 0, 10);
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
        return position < 0 ? 1 : state.skills().skill(position).level();
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
        if (tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_PARRY)) {
            service.buffs().remove(player, "guard"); // 招架只擋一下
            if (attacker != null && !(attacker instanceof Player) && plugin.combat() != null) {
                plugin.combat().debuffs().apply(attacker.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff(PARRIED, player.getUniqueId(),
                        now + PARRIED_TICKS, PARRIED_TAKEN, false, Tags.ATTACK));
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
        final PlayerState state = plugin.players().get(player);
        if (state.mana() < ILLUSION_MANA) {
            return;
        }
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
        if (at.getWorld() == null) {
            return;
        }
        final org.bukkit.entity.ItemDisplay display = at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AMETHYST_SHARD));
            d.setPersistent(false);
            d.setGlowing(true);
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.5f, 0.5f, 0.5f), new org.joml.Quaternionf()));
        });
        final long until = now() + REMNANT_TICKS;
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!display.isValid() || now() >= until || !owner.isOnline()) {
                task[0].cancel();
                if (display.isValid()) {
                    display.remove();
                }
                return;
            }
            if (owner.getWorld().equals(at.getWorld()) && owner.getLocation().distanceSquared(at) <= REMNANT_PICKUP * REMNANT_PICKUP) {
                task[0].cancel();
                display.remove();
                owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.8f);
                final ChargeService charges = charges();
                if (charges != null) {
                    charges.grant(owner, charge, 1);
                }
            }
        }, 2, 2);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        empowered.remove(event.getPlayer().getUniqueId());
        perfectTick.remove(event.getPlayer().getUniqueId());
        parriedTick.remove(event.getPlayer().getUniqueId());
        magmaSpent.remove(event.getPlayer().getUniqueId());
    }
}
