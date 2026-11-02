package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentState;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2（PoB2 sup_str／sup_int）：幾個輔助觸發的隱藏技能自己的傷害基礎與做法（{@link SupportProcs#fireAll} 放出去之前交給這裡）。
 * 這次之前它們都是「法術傷害表 × 法術成長」的一圈新星。
 * <ul>
 *   <li>噴炎柱（Flame Pillar）：傷害＝用掉的點燃剩下的傷害的 25%（support_flame_pillar_fire_damage_equal_to_%_of_remaining_ignite_damage 25）。</li>
 *   <li>魔焰爆（Mana Flare）：用掉目前魔力的 25%，造成等量的火焰傷害（support_mana_flare_%_of_current_mana_consumed 25）。</li>
 *   <li>播火爆（Fan the Flames II）：在那隻敵人身上炸開 3.5 格，把牠的點燃傳給被炸到的敵人。</li>
 *   <li>荊棘叢生（Brambles）：每次猛擊長出 3 叢荊棘（危險區域，0.4 秒後才會被碰、留 8 秒、最多 20 叢），敵人碰到就刺一下
 *       （原技能那一下的 35% 攻擊傷害、致殘）然後消失；剛長出 0.5 秒內被碰到傷害更少 80%。</li>
 *   <li>腐血嘯（Corrupted Cry）：不打擊中，範圍內的敵人各上 1 層腐化之血（物理持續傷害、5 秒、最多 10 層、每層一樣重）。</li>
 *   <li>刺爆針（Quill Burst）：在被打的敵人周圍造成你的荊棘傷害（打不到被打的那隻）。</li>
 * </ul>
 */
final class ProcBasis {

    static final String FLAME_PILLAR = "proc_flame_pillar";
    static final String MANA_FLARE = "proc_mana_flare";
    static final String FAN_FLAMES = "proc_fan_flames";
    static final String BRAMBLE = "proc_bramble";
    static final String CORRUPTING_CRY = "proc_corrupting_cry";
    static final String QUILL_BURST = "proc_quill_burst";
    static final String TWISTED_PACT = "proc_twisted_pact";

    /** 噴炎柱：剩下的點燃傷害的幾成。 */
    static final double FLAME_PILLAR_SHARE = 0.25;
    /** 魔焰爆：目前魔力的幾成。 */
    static final double MANA_FLARE_SHARE = 0.25;
    /** 腐血嘯每次上幾層腐化之血（add_buff_to_target_skill_number_of_corrupted_blood_debuffs_to_inflict 1；扭曲契約 5）。 */
    static final int CORRUPTING_CRY_STACKS = 1;
    /** 荊棘叢生：每次猛擊幾叢（create_X_briar_thorns_on_slam 3）、同時最多幾叢、留幾 tick（8 秒）、幾 tick 後才會被碰（0.4 秒）。 */
    static final int BRAMBLES_PER_SLAM = 3;
    static final int BRAMBLE_MAX = 20;
    static final long BRAMBLE_TICKS = 160;
    static final long BRAMBLE_ARM_TICKS = 8;
    /** 剛長出 0.5 秒內被敵人碰到更少 80%（hazard_damage_+%_final_for_first_500_ms_when_not_killed_by_self −80）。 */
    static final long BRAMBLE_FRESH_TICKS = 10;
    static final double BRAMBLE_FRESH_LESS = 0.8;
    /** 原作沒寫荊棘長在哪：伺服器自己定落在猛擊範圍內（沒有範圍時 2 格）。 */
    static final double BRAMBLE_DEFAULT_SCATTER = 2.0;

    private final ExileCore plugin;
    private final SkillService service;

    ProcBasis(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    static boolean handles(final String subId) {
        return switch (subId) {
            case FLAME_PILLAR, MANA_FLARE, FAN_FLAMES, BRAMBLE, CORRUPTING_CRY -> true;
            default -> false;
        };
    }

    // =====================================================================
    // 純函式（測試用）
    // =====================================================================

    /** 噴炎柱的基礎火焰傷害：點燃每秒 dps、還剩 remainingTicks tick 的 25%。 */
    static double flamePillarBase(final double dps, final long remainingTicks) {
        return FLAME_PILLAR_SHARE * Math.max(0, dps) * Math.max(0, remainingTicks) / 20.0;
    }

    /** 魔焰爆用掉（＝造成）的魔力：目前魔力的 25%。 */
    static double manaFlareBase(final double currentMana) {
        return MANA_FLARE_SHARE * Math.max(0, currentMana);
    }


    /** 荊棘被碰到時的傷害倍率：剛長出 0.5 秒內更少 80%。 */
    static double brambleDamageScale(final long ageTicks) {
        return ageTicks < BRAMBLE_FRESH_TICKS ? 1 - BRAMBLE_FRESH_LESS : 1.0;
    }

    /** 腐化之血每層每秒的傷害：skills.yml 的 damage 寫的是每分鐘（PoB2 base_physical_damage_to_deal_per_minute）。 */
    static double perSecond(final double perMinute) {
        return Math.max(0, perMinute) / 60.0;
    }

    /** 把技能的基礎傷害換成 amount（照原本的傷害種類比例分；沒有種類的算 typeIfNone）；之後照樣吃增加、更多。 */
    static SkillProfile withBase(final SkillProfile base, final double amount, final DamageType typeIfNone) {
        final DamagePacket shares = base.spellDamage();
        final DamagePacket packet = new DamagePacket();
        if (shares == null || shares.total() <= 0) {
            packet.set(typeIfNone, amount);
        } else {
            for (final DamageType type : DamageType.values()) {
                if (shares.get(type) > 0) {
                    packet.set(type, amount * shares.get(type) / shares.total());
                }
            }
        }
        return new SkillProfile(base.id(), base.tags(), base.effectiveness(), packet, new DamagePacket(packet), base.conversion(), base.local());
    }

    /**
     * 持續傷害（腐化之血）用的技能描述：不吃附加傷害（效用 0）、不是法術也不是範圍傷害，算戰吼的傷害
     * （modifiers_to_warcry_damage_also_apply_to_this）。
     */
    static SkillProfile dotProfile(final SkillProfile base) {
        final SkillProfile derived = base.derive(base.id(), Set.of(Tags.WARCRY, Tags.PHYSICAL), Set.of(Tags.SPELL, Tags.AREA));
        return new SkillProfile(derived.id(), derived.tags(), 0, derived.spellDamage(), derived.spellMax(), derived.conversion(), derived.local());
    }

    /** 荊棘的攻擊：原技能那一下的描述換成荊棘的，拿掉近戰、猛擊、打擊（荊棘不是那些），加上荊棘自己的修飾器（致殘）。 */
    static SkillProfile brambleProfile(final SkillProfile fromParent, final Set<String> ownTags, final List<Modifier> own) {
        final Set<String> add = new java.util.HashSet<>(ownTags);
        add.remove(Tags.SPELL);
        return fromParent.derive(BRAMBLE, add, Set.of(Tags.SPELL, Tags.MELEE, Tags.SLAM, Tags.STRIKE)).withLocal(own);
    }


    // =====================================================================
    // 放出去之前（SupportProcs.fireAll）
    // =====================================================================

    /**
     * 換傷害基礎；回傳 null＝這次不放（沒有點燃、沒有魔力）。
     */
    SkillBuild adjust(final Player player, final String subId, final String parentId, final SkillBuild build, final LivingEntity target) {
        return switch (subId) {
            case FLAME_PILLAR -> pillarAllowed(player, parentId) ? countPillar(player, parentId, flamePillar(build, target)) : null;
            case MANA_FLARE -> manaFlare(player, build);
            default -> build;
        };
    }

    /** 噴炎柱：玩家 → 原技能 → 這一次使用已經噴了幾道（flame_pillar_support_maximum_pillars_per_skill_use 10）。 */
    static final int PILLARS_PER_USE = 10;
    private final Map<UUID, Map<String, Integer>> pillarsThisUse = new HashMap<>();

    /** 放了一個技能（SupportProcs.onUse）：那個技能的噴炎柱重新算。 */
    void used(final Player player, final String skillId) {
        final Map<String, Integer> mine = pillarsThisUse.get(player.getUniqueId());
        if (mine != null) {
            mine.remove(skillId);
        }
    }

    /** 這一次使用還能不能再噴一道（純函式，測試用）。 */
    static boolean pillarRoom(final int alreadyThisUse) {
        return alreadyThisUse < PILLARS_PER_USE;
    }

    private boolean pillarAllowed(final Player player, final String parentId) {
        return pillarRoom(pillarsThisUse.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(parentId, 0));
    }

    private SkillBuild countPillar(final Player player, final String parentId, final SkillBuild built) {
        if (built != null) {
            pillarsThisUse.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).merge(parentId, 1, Integer::sum);
        }
        return built;
    }

    private SkillBuild flamePillar(final SkillBuild build, final LivingEntity target) {
        if (target == null || plugin.ailments() == null) {
            return null;
        }
        final AilmentState.Ignite ignite = plugin.ailments().ignite(target);
        if (ignite == null) {
            return null;
        }
        final double amount = flamePillarBase(ignite.dps(), ignite.endTick() - plugin.getServer().getCurrentTick());
        return amount <= 0 ? null : build.withProfile(withBase(build.profile(), amount, DamageType.FIRE));
    }

    private SkillBuild manaFlare(final Player player, final SkillBuild build) {
        final PlayerState state = plugin.players().get(player);
        final double amount = manaFlareBase(state.mana());
        if (amount <= 0) {
            return null;
        }
        state.setMana(state.mana() - amount); // 用掉目前魔力的 25%
        plugin.players().display(player, state);
        return build.withProfile(withBase(build.profile(), amount, DamageType.FIRE));
    }

    /**
     * 自己放的（不走一般的 procCast）；回傳 true＝放好了。
     *
     * @param parent 觸發它的那一下（荊棘叢生的範圍；沒算回傳 null）
     */
    boolean custom(final Player player, final String subId, final String parentId, final SkillBuild build, final List<Modifier> own,
                   final SkillBuild parent, final Location at, final LivingEntity target) {
        return switch (subId) {
            case FAN_FLAMES -> target != null && fanFlames(player, build, target);
            case BRAMBLE -> brambles(player, parentId, build, own, parent, at);
            case CORRUPTING_CRY -> corruptingCry(player, build, parent, at);
            default -> false;
        };
    }

    // ---- 播火爆 ----

    /**
     * 播火爆（PoB2 TriggeredFanTheFlamesPlayerTwo：active_skill_base_area_of_effect_radius 35）：在那隻點燃中的敵人身上炸開 3.5 格的圓，
     * 打牠與範圍內的敵人，把牠的點燃傳給被炸到的其他敵人。
     */
    private boolean fanFlames(final Player player, final SkillBuild build, final LivingEntity first) {
        final Location centre = first.getLocation();
        final double radius = build.radius();
        final AilmentState.Ignite ignite = plugin.ailments() == null ? null : plugin.ailments().ignite(first);
        final List<LivingEntity> hit = new ArrayList<>(service.targets().around(centre, radius, player));
        if (hit.stream().noneMatch(each -> each.getUniqueId().equals(first.getUniqueId()))) {
            hit.add(first);
        }
        for (final LivingEntity each : hit) {
            if (plugin.combat() != null && plugin.combat().skillDamage() != null) {
                plugin.combat().skillDamage().hit(player, each, build.profile(), null);
            }
            // Fan the Flames II：把第一隻的點燃傳給被炸到的其他敵人
            if (ignite != null && !each.getUniqueId().equals(first.getUniqueId()) && each.isValid() && !each.isDead()) {
                plugin.ailments().copyIgnite(each, ignite);
            }
        }
        Fx.spawn(centre.getWorld(), Particle.FLAME, centre.clone().add(0, 0.6, 0), 30, radius * 0.4, 0.3, radius * 0.4, 0.03);
        centre.getWorld().playSound(centre, org.bukkit.Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.1f);
        return true;
    }

    // ---- 腐血嘯 ----

    private boolean corruptingCry(final Player player, final SkillBuild build, final SkillBuild warcry, final Location at) {
        final double perStack = corruptedPerStack(player, build);
        if (perStack <= 0) {
            return true;
        }
        // 範圍＝被輔助的戰吼的範圍（Corrupting Cry II 不縮範圍）；不是戰吼的照自己 skills.yml 的半徑
        double radius = build.radius();
        final StatSheet sheet = plugin.players().sheet(player);
        if (warcry != null && warcry.definition().warcry() != null && sheet != null) {
            radius = Warcries.baseRadius(warcry.definition().warcry(), warcry.profile().effective(sheet), warcry.profile().tags()) * warcry.areaScale();
        }
        for (final LivingEntity each : service.targets().around(at, radius, player)) {
            service.supportMechanics().corruptedBlood(player, each, perStack, CORRUPTING_CRY_STACKS);
        }
        return true;
    }

    /** 腐化之血每層每秒的傷害（照技能表的每分鐘傷害，吃戰吼、物理、持續傷害的加成）。 */
    double corruptedPerStack(final Player player, final SkillBuild build) {
        final StatSheet sheet = plugin.players().sheet(player);
        if (sheet == null) {
            return 0;
        }
        return perSecond(tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, dotProfile(build.profile())).total());
    }

    // ---- 荊棘叢生 ----

    /** born＝長出來（或重新布置好）的那一刻；armedAt＝之後才會被碰；delayed＝遲爆（碰到不觸發，時間到才刺）；rearm＝再布的機率 %。 */
    private record Bramble(UUID owner, Location at, long born, long armedAt, long until, double radius, SkillProfile profile,
                           boolean delayed, double rearm) {
    }

    private final Map<UUID, List<Bramble>> brambles = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask brambleTask;

    /** 荊棘留幾 tick：遲爆的持續時間更少 70%（照撒蒺、蒺藜步的鐵蒺）。純函式（測試用）。 */
    static long brambleLifetime(final long ticks, final boolean delayed) {
        return Math.max(1, Math.round(ticks * (delayed ? tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION_DURATION : 1.0)));
    }

    /** 再布：觸發後重新布置的那一叢——剩下的時間（至少 2 秒），過 1 秒才會被碰。 */
    static long[] rearmTimes(final long now, final long born, final long until) {
        final long armedAt = now + tw.exilecore.stats.SupportExtraKeys.REARM_TICKS;
        return new long[]{armedAt - BRAMBLE_ARM_TICKS, armedAt, now + Math.max(until - born, 40)};
    }

    private boolean brambles(final Player player, final String parentId, final SkillBuild build, final List<Modifier> own, final SkillBuild parent,
                             final Location at) {
        if (at == null || at.getWorld() == null) {
            return false;
        }
        // 照 PoE2：荊棘是危險區域，掛在原技能上的遲爆（Delayed Reaction）、再布（Rearm）照撒蒺、蒺藜步的規則作用
        final double delayed = service.supportFlat(player, parentId, tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION);
        final double rearm = service.supportFlat(player, parentId, tw.exilecore.stats.SupportExtraKeys.REARM);
        SkillProfile profile = brambleProfile(build.profile(), build.definition().tags(), own);
        if (delayed > 0) {
            profile = profile.scaled(1 + delayed / 100.0, "遲爆");
        }
        final double scatter = parent != null && parent.radius() > 0 ? parent.radius() : BRAMBLE_DEFAULT_SCATTER;
        final long now = plugin.getServer().getCurrentTick();
        final long lifetime = brambleLifetime(build.durationTicks(BRAMBLE_TICKS / 20.0), delayed > 0);
        final List<Bramble> mine = brambles.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>());
        final StatSheet sheet = plugin.players().sheet(player);
        for (int i = 0; i < BRAMBLES_PER_SLAM; i++) {
            final double[] offset = OriginalSkills.scatterOffset(plugin.random().nextDouble(), plugin.random().nextDouble(), scatter);
            Location point = SkillZones.groundOf(at.clone().add(offset[0], 0, offset[1]));
            if (point == null) {
                point = at.clone();
            }
            SkillProfile each = profile;
            double radius = build.radius();
            // 植物：長出來時擲「立即繁茂」，或一出來就在積雷雲裡（50% 更多傷害）
            final Overgrowth.Effect effect = Overgrowth.effect(BRAMBLE);
            if (effect != null && (plugin.random().nextDouble() < Overgrowth.chance(sheet, each) || service.zones().inThunderstorm(player.getUniqueId(), point))) {
                each = Overgrowth.apply(each, effect, sheet);
                radius *= Overgrowth.radiusScale(effect);
                Fx.spawn(point.getWorld(), Particle.HAPPY_VILLAGER, point.clone().add(0, 0.5, 0), 8, 0.5, 0.3, 0.5, 0);
            }
            final Bramble bramble = new Bramble(player.getUniqueId(), point, now, now + BRAMBLE_ARM_TICKS, now + lifetime, radius, each, delayed > 0, rearm);
            tw.exilecore.skill.Fx.spawn(point.getWorld(), Particle.BLOCK, point.clone().add(0, 0.3, 0), 8, 0.3, 0.1, 0.3, 0, Material.SWEET_BERRY_BUSH.createBlockData());
            if (delayed > 0 && !service.targets().around(point, radius, player).isEmpty()) {
                stab(player, mine, bramble, now); // 遲爆：長出來那一刻就有敵人，立刻刺
                continue;
            }
            OriginalSkills.pushCapped(mine, bramble, BRAMBLE_MAX);
        }
        startBrambleTask();
        return true;
    }

    private void startBrambleTask() {
        if (brambleTask != null) {
            return;
        }
        brambleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickBrambles, 2L, 2L);
    }

    private void tickBrambles() {
        final long now = plugin.getServer().getCurrentTick();
        for (final Iterator<Map.Entry<UUID, List<Bramble>>> players = brambles.entrySet().iterator(); players.hasNext(); ) {
            final Map.Entry<UUID, List<Bramble>> entry = players.next();
            final Player owner = plugin.getServer().getPlayer(entry.getKey());
            if (owner == null || !owner.isOnline()) {
                players.remove();
                continue;
            }
            final List<Bramble> touched = new ArrayList<>();
            for (final Iterator<Bramble> it = entry.getValue().iterator(); it.hasNext(); ) {
                final Bramble bramble = it.next();
                final boolean here = bramble.at().getWorld() != null && bramble.at().getWorld().equals(owner.getWorld());
                if (now >= bramble.until() || !here) {
                    it.remove();
                    if (bramble.delayed() && here) {
                        touched.add(bramble); // 遲爆：時間到才刺
                    }
                    continue;
                }
                if (now % 10 == 0 && now >= bramble.born()) {
                    tw.exilecore.skill.Fx.spawn(bramble.at().getWorld(), Particle.BLOCK, bramble.at().clone().add(0, 0.2, 0), 2, 0.25, 0.05, 0.25, 0,
                            Material.SWEET_BERRY_BUSH.createBlockData());
                }
                if (bramble.delayed() || now < bramble.armedAt() || service.targets().around(bramble.at(), bramble.radius(), owner).isEmpty()) {
                    continue;
                }
                it.remove(); // 危險區域：碰到就刺，然後消失
                touched.add(bramble);
            }
            for (final Bramble bramble : touched) {
                stab(owner, entry.getValue(), bramble, now);
            }
        }
        brambles.values().removeIf(List::isEmpty);
        if (brambles.isEmpty() && brambleTask != null) {
            brambleTask.cancel();
            brambleTask = null;
        }
    }

    /** 一叢荊棘刺下去（範圍內的敵人），然後消失；再布：x% 機率過 1 秒在原地重新布置（不超過上限）。 */
    private void stab(final Player owner, final List<Bramble> mine, final Bramble bramble, final long now) {
        final SkillProfile profile = bramble.profile().scaled(brambleDamageScale(now - bramble.born()), "剛長出的荊棘");
        for (final LivingEntity each : service.targets().around(bramble.at(), bramble.radius(), owner)) {
            if (plugin.combat() != null && plugin.combat().skillDamage() != null) {
                plugin.combat().skillDamage().hit(owner, each, profile, null);
            }
        }
        tw.exilecore.skill.Fx.spawn(bramble.at().getWorld(), Particle.BLOCK, bramble.at().clone().add(0, 0.5, 0), 14, 0.4, 0.3, 0.4, 0,
                Material.SWEET_BERRY_BUSH.createBlockData());
        bramble.at().getWorld().playSound(bramble.at(), org.bukkit.Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.8f, 1.2f);
        if (bramble.rearm() > 0 && plugin.random().nextDouble() * 100 < bramble.rearm() && mine.size() < BRAMBLE_MAX) {
            final long[] times = rearmTimes(now, bramble.born(), bramble.until());
            mine.add(new Bramble(bramble.owner(), bramble.at(), times[0], times[1], times[2], bramble.radius(), bramble.profile(), bramble.delayed(), bramble.rearm()));
            startBrambleTask();
        }
    }

    void forget(final UUID owner) {
        brambles.remove(owner);
        quillReady.remove(owner);
        pillarsThisUse.remove(owner);
    }

    // ---- 刺爆針 ----

    private final Map<UUID, Long> quillReady = new HashMap<>();

    /**
     * 刺爆針：在被打的敵人周圍（skills.yml 的半徑，照站長的自訂距離）造成你的荊棘傷害（物理＋傳奇的元素荊棘，吃「增加荊棘傷害」與範圍傷害），
     * 打不到被打的那隻（原作：Cannot Hit the target this was Triggered from）。冷卻照 skills.yml（0.15 秒）。沒有荊棘傷害就不放。
     */
    boolean quillBurst(final Player player, final String levelFrom, final LivingEntity struck) {
        final SkillDefinition sub = service.config().skill(QUILL_BURST);
        final StatSheet sheet = plugin.players().sheet(player);
        if (sub == null || struck == null || sheet == null) {
            return false;
        }
        final long now = plugin.getServer().getCurrentTick();
        if (quillReady.getOrDefault(player.getUniqueId(), 0L) > now) {
            return false;
        }
        final SkillProfile thorns = tw.exilecore.combat.Thorns.profile(sheet);
        if (thorns == null) {
            return false;
        }
        quillReady.put(player.getUniqueId(), now + Math.max(1, Math.round(sub.cooldown() * 20)));
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(levelFrom);
        final int level = position < 0 ? 1 : GemAttributes.usableLevel(service.config(), state.skills().skill(position), sheet);
        final SkillBuild build = SkillBuild.of(service.config(), sub, level, state.level(), false, List.of(), state.weaponTags(), sheet);
        final SkillProfile profile = thorns.derive(QUILL_BURST, Set.of(Tags.AREA, Tags.PHYSICAL), Set.of());
        final Location at = struck.getLocation();
        for (final LivingEntity each : service.targets().around(at, build.radius(), player)) {
            if (!each.getUniqueId().equals(struck.getUniqueId()) && plugin.combat() != null && plugin.combat().skillDamage() != null) {
                plugin.combat().skillDamage().hit(player, each, profile, null);
            }
        }
        Fx.spawn(at.getWorld(), Particle.CRIT, at.clone().add(0, 0.8, 0), 18, build.radius() * 0.4, 0.3, build.radius() * 0.4, 0.15);
        at.getWorld().playSound(at, org.bukkit.Sound.ENCHANT_THORNS_HIT, 0.8f, 1.0f);
        return true;
    }
}
