package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2 0.5 的 Mana Tempest（魔湧；這次之前是保留 30 精魂的常駐光環：法術傷害、施法速度增加，魔力消耗增加 20%）：
 * <ul>
 *   <li>施放時在腳下召出一團風暴（半徑 2.6 公尺 = 3.9 格，原作 do_not_apply_area_modifiers：不吃範圍加成）。</li>
 *   <li>站在風暴裡，你的法術被強化：技能的 level_modifiers 裡「獲得 x% 傷害為額外閃電」與「分裂 x 個目標」（{@link #SPLIT}）
 *       變成身上的增益；離開風暴後效果再留 2 秒。</li>
 *   <li>風暴每秒消耗最大魔力的 1%，在風暴裡花掉的魔力與生命有 30% 加到每秒的消耗上（越花越快）；付不起或走出風暴就消散。</li>
 *   <li>冷卻 1 秒，風暴還在的時候冷卻不回復（消散之後才開始算）。</li>
 * </ul>
 * 技能資料：mechanic guard（只是借用「放了給自己效果」那條路）、modifiers 帶 {@link #FLAG}。
 */
final class ManaTempest {

    /** 技能的旗標（FLAT 1）：這個 guard 技能是魔湧，不舉盾、不用盾。 */
    static final String FLAG = "flag.mana_tempest";
    /**
     * FLAT：被強化的法術投射物打中敵人時分裂出幾發，射向附近其他的敵人（PoE2 Split；ProjectileEngine 處理，
     * 分裂出來的不會再分裂、不打剛打中的那隻）。
     */
    static final String SPLIT = "skill.split";
    /** 風暴半徑（格；原作 active_skill_base_area_of_effect_radius 26 = 2.6 公尺）。 */
    static final double RADIUS = tw.exilecore.core.Units.metres(2.6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 離開風暴後效果再留幾 tick（原作 2 秒）。 */
    static final int LINGER_TICKS = 40;
    /** 每秒基礎消耗：最大魔力的幾 %（原作 ManaPercentPerMinute 60）。 */
    static final double BASE_PERCENT = 1.0;
    /** 風暴裡花掉的魔力與生命有幾 % 加到每秒消耗（原作 30%）。 */
    static final double SPENT_SHARE = 0.30;
    /** 風暴存在時把冷卻一直推到這麼久之後（冷卻不回復），消散時改成正常的冷卻。 */
    static final int HELD_COOLDOWN = 20 * 60 * 30;
    static final String BUFF_ID = "mana_tempest";
    static final int PERIOD = 5;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Storm> storms = new HashMap<>();
    private BukkitTask task;

    ManaTempest(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 一團風暴。extraPerSecond：花掉的魔力、生命累積加上去的每秒消耗。 */
    static final class Storm {
        final String skillId;
        final String name;
        final World world;
        final Location center;
        final List<Modifier> effects;
        final int cooldownTicks;
        double extraPerSecond;
        long nextDrain;
        /** 2026-10-28 照 PoE2 Mana Tempest 的品質：離開之後效果再留幾 tick（2 秒＋品質的 0～2 秒）。 */
        int linger = LINGER_TICKS;

        Storm(final String skillId, final String name, final Location center, final List<Modifier> effects, final int cooldownTicks, final long now) {
            this.skillId = skillId;
            this.name = name;
            this.world = center.getWorld();
            this.center = center;
            this.effects = effects;
            this.cooldownTicks = cooldownTicks;
            this.nextDrain = now + 20;
        }
    }

    static boolean is(final SkillDefinition definition) {
        return definition != null && SkillExecutor.hasFlag(definition, FLAG);
    }

    /** 每秒的消耗（最大魔力 1% + 花掉的 30% 累積）。 */
    static double costPerSecond(final double maxMana, final double extraPerSecond) {
        return Math.max(0, maxMana) * BASE_PERCENT / 100.0 + Math.max(0, extraPerSecond);
    }

    /** 站在風暴裡（水平距離在半徑內、上下 3 格內）。 */
    static boolean inside(final Location center, final Location at) {
        if (center == null || at == null || center.getWorld() != at.getWorld()) {
            return false;
        }
        final double dx = at.getX() - center.getX();
        final double dz = at.getZ() - center.getZ();
        return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(at.getY() - center.getY()) <= 3;
    }

    /** 強化的內容：技能（含輔助）的「獲得額外閃電」「分裂」那幾條。 */
    static List<Modifier> effects(final SkillBuild build) {
        final List<Modifier> list = new ArrayList<>();
        for (final Modifier modifier : build.profile().local()) {
            if (modifier.stat().equals(SPLIT) || modifier.stat().startsWith(StatKeys.DAMAGE_EXTRA) && !modifier.stat().startsWith(StatKeys.DAMAGE_EXTRA_PER_MANA)) {
                list.add(new Modifier(modifier.stat(), modifier.type(), modifier.value(), modifier.tags(), build.definition().name()));
            }
        }
        return List.copyOf(list);
    }

    /** 放出風暴（SkillService 在扣完消耗、開始冷卻之後呼叫）。 */
    void cast(final Player player, final SkillRuntime runtime, final SkillBuild build, final long now) {
        final Storm storm = new Storm(build.definition().id(), build.definition().name(), player.getLocation().clone(), effects(build),
                build.cooldownTicks(), now);
        storm.linger = LINGER_TICKS + (int) Math.round(Math.max(0, build.qualityFlat(tw.exilecore.stats.GemQualityKeys.LINGER_SECONDS)) * 20);
        storms.put(player.getUniqueId(), storm);
        runtime.startCooldown(storm.skillId, now, HELD_COOLDOWN); // 風暴還在時冷卻不回復
        service.buff(player, BUFF_ID, storm.name, storm.linger, storm.effects);
        player.getWorld().playSound(storm.center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 0.9f, 1.4f);
        draw(storm);
        final PlayerState state = plugin.players().get(player);
        state.notice("<#8fb8ff>✦ " + ItemRenderer.escape(storm.name) + "：站在風暴裡法術被強化（每秒消耗 "
                + Math.round(costPerSecond(state.maxMana(), 0)) + " 魔力，越花越快）", now + 40);
        plugin.players().display(player, state);
        if (task == null) {
            task = plugin.repeat("skill.mana-tempest", this::tick, PERIOD, PERIOD); // 2026-10-27：計時給 /ec perf 看
        }
    }

    /** 在風暴裡花掉了魔力或生命（技能消耗）：30% 加到每秒消耗。 */
    void spent(final Player player, final double amount) {
        final Storm storm = storms.get(player.getUniqueId());
        if (storm != null && amount > 0 && inside(storm.center, player.getLocation())) {
            storm.extraPerSecond += amount * SPENT_SHARE;
        }
    }

    private void tick() {
        final long now = plugin.getServer().getCurrentTick();
        final Iterator<Map.Entry<UUID, Storm>> it = storms.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, Storm> entry = it.next();
            final Storm storm = entry.getValue();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            String ended = null;
            if (!inside(storm.center, player.getLocation())) {
                ended = "走出風暴";
            } else if (now >= storm.nextDrain) {
                storm.nextDrain += 20;
                final PlayerState state = plugin.players().get(player);
                final double cost = costPerSecond(state.maxMana(), storm.extraPerSecond);
                if (state.mana() + 1e-9 < cost) {
                    ended = "魔力不夠撐住風暴";
                } else {
                    state.setMana(state.mana() - cost);
                    plugin.players().display(player, state);
                }
            }
            if (ended != null) {
                it.remove();
                end(player, storm, ended, now);
                continue;
            }
            service.buff(player, BUFF_ID, storm.name, storm.linger, storm.effects);
            if (now % 10 < PERIOD) {
                draw(storm);
            }
        }
        if (storms.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 風暴消散：效果再留 2 秒（增益自己到期），冷卻從現在開始算。 */
    private void end(final Player player, final Storm storm, final String why, final long now) {
        final SkillRuntime runtime = service.runtime(player);
        runtime.startCooldown(storm.skillId, now, Math.max(1, storm.cooldownTicks));
        service.showCooldown(player, storm.skillId, Math.max(1, storm.cooldownTicks));
        final PlayerState state = plugin.players().get(player);
        state.notice("<gray>" + ItemRenderer.escape(storm.name) + " 消散（" + why + "）", now + 30);
        plugin.players().display(player, state);
    }

    /** 玩家離線、關服：清掉。 */
    void clear(final UUID player) {
        storms.remove(player);
    }

    private void draw(final Storm storm) {
        if (storm.world == null) {
            return;
        }
        for (int i = 0; i < 16; i++) {
            final double angle = Math.PI * 2 * i / 16;
            final Location at = storm.center.clone().add(Math.cos(angle) * RADIUS, 0.2, Math.sin(angle) * RADIUS);
            Fx.spawn(storm.world, Particle.ELECTRIC_SPARK, at, 1, 0.05, 0.3, 0.05, 0.01);
        }
        Fx.spawn(storm.world, Particle.ENCHANT, storm.center.clone().add(0, 1.2, 0), 10, RADIUS / 2, 0.8, RADIUS / 2, 0.2);
    }
}
