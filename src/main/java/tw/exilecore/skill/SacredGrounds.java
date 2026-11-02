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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 0.5（站長「機制沒做的部分就做出來補齊」）：武器、裝備賦予的「地上的法印／地面」與噬屍宴。這次之前四個都是跟著你走的固定增益（guard）。
 * <ul>
 *   <li>力印（Sigil of Power，SigilOfPowerPlayer）：在腳下放一個 3 公尺的法印（吃範圍，品質 +0.04 公尺／1%），持續 10 → 13.9 秒，同時 1 個。
 *       站在裡面的你與隊友得到增益；你站在裡面每花掉 50% 最大魔力（base_circle_of_power_mana_%_spend_per_upgrade 50）升一階，
 *       最少隔 1 秒（circle_of_power_minimum_time_between_stage_increases_ms 1000），最多 4 階；每階更多 10 → 14 → 19% 法術傷害。0 階沒有效果。</li>
 *   <li>重生之儀（Rite of Restoration，SacredGroundPlayer）：同上，4.5 公尺、每 35% 最大魔力一階；每階更多 10 → 14 → 20% 護甲、閃避、能量護盾，
 *       每分鐘再生 65 → 1300 生命（寫成每秒）。</li>
 *   <li>聖地（Consecrate，StaffConsecratePlayer）：在腳下留下 2.2 公尺（吃範圍）的奉獻地面 8 秒（地面效果），站在上面的你與隊友每秒回復 5% 最大生命、
 *       受到的詛咒效果減少 50%，兩條都乘「奉獻地面效果增加 0 → 38 → 78%」（逐級，寫在技能的 guard.effects）。可以放好幾塊（同一塊範圍不疊加）。</li>
 *   <li>噬屍宴（Feast of Flesh，FeastOfFleshPlayer）：吃掉身邊 4 公尺（吃範圍）內最多 5 具屍體（品質再多 0～3 具），每具在 3 秒內回復 21 → 211 → 554
 *       生命與魔力（feast_of_flesh_recovery_duration 3000）；身邊沒有屍體不能放。沒有冷卻，連放的回復各算各的、加在一起。</li>
 * </ul>
 * 技能資料照舊是 mechanic guard（借用「放了給自己效果」那條路，增益內容寫在 guard.effects：法印是每一階的量、聖地是整份、噬屍宴是每具屍體的總量）。
 */
final class SacredGrounds {

    static final String POWER = "might_seal";
    static final String RESTORATION = "rebirth_ceremony";
    static final String CONSECRATE = "holy_ground";
    static final String FEAST = "corpse_devour_feast";
    /** 這幾個技能交給這裡放（SkillService 的 guard 那條路）。 */
    static final Set<String> SKILLS = Set.of(POWER, RESTORATION, CONSECRATE, FEAST);

    /** 法印半徑（公尺；力印 30、重生之儀 45，PoB active_skill_base_area_of_effect_radius）。 */
    static final double POWER_RADIUS_METRES = 3.0;
    static final double RESTORATION_RADIUS_METRES = 4.5;
    /** 奉獻地面半徑（公尺；22）。 */
    static final double CONSECRATE_RADIUS_METRES = 2.2;
    /** 每花掉最大魔力的幾 % 升一階（力印 50、重生之儀 35）。 */
    static final double POWER_MANA_PERCENT = 50;
    static final double RESTORATION_MANA_PERCENT = 35;
    /** 最多幾階（circle_of_power_max_stages 4）。 */
    static final int MAX_STAGES = 4;
    /** 兩次升階最少隔幾 tick（1 秒）。 */
    static final long STAGE_GAP_TICKS = 20;
    /** 同一個技能的法印同時幾個（base_number_of_sigil_of_power_allowed 1）。 */
    static final int SIGIL_LIMIT = 1;
    /** 奉獻地面每個玩家最多幾塊（原作沒寫上限，效能保險）。 */
    static final int CONSECRATE_LIMIT = 10;
    /** 品質：法印半徑 +公尺（FLAT；gem_quality.yml 每 1% 0.04）。 */
    static final String SIGIL_RADIUS = "sigil.radius";
    /** 噬屍宴：吃屍體的範圍（公尺）、一次最多幾具、回復分幾秒。 */
    static final double FEAST_RADIUS_METRES = 4.0;
    static final int FEAST_CORPSES = 5;
    static final double FEAST_SECONDS = 3.0;
    /** 站在裡面的增益多久沒刷新就掉（tick）；每 {@link #PERIOD} tick 刷新一次。 */
    static final int BUFF_TICKS = 12;
    static final int PERIOD = 5;
    /** 增益代號的前綴（＋技能代號）。 */
    static final String BUFF_PREFIX = "sacred.";

    private final ExileCore plugin;
    private final SkillService service;
    private final List<Ground> grounds = new ArrayList<>();
    /** 噬屍宴：玩家 → 還在回復的幾份 {每秒生命, 每秒魔力, 結束 tick}。 */
    private final Map<UUID, List<double[]>> feasts = new HashMap<>();
    private BukkitTask task;

    SacredGrounds(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    static boolean handles(final String skillId) {
        return skillId != null && SKILLS.contains(skillId);
    }

    static boolean sigil(final String skillId) {
        return POWER.equals(skillId) || RESTORATION.equals(skillId);
    }

    /** 基礎半徑（公尺，不含範圍加成）；不是這裡的技能回傳 0。 */
    static double baseRadiusMetres(final String skillId) {
        return switch (skillId) {
            case POWER -> POWER_RADIUS_METRES;
            case RESTORATION -> RESTORATION_RADIUS_METRES;
            case CONSECRATE -> CONSECRATE_RADIUS_METRES;
            case FEAST -> FEAST_RADIUS_METRES;
            default -> 0;
        };
    }

    /** 每升一階要花幾 % 最大魔力。 */
    static double manaPercent(final String skillId) {
        return RESTORATION.equals(skillId) ? RESTORATION_MANA_PERCENT : POWER_MANA_PERCENT;
    }

    /** 實際半徑（格）：（基礎 ＋ 品質的公尺）換成格 × 範圍倍率。純函式。 */
    static double radius(final String skillId, final double qualityMetres, final double areaScale) {
        final double metres = baseRadiusMetres(skillId) + (sigil(skillId) ? Math.max(0, qualityMetres) : 0);
        return tw.exilecore.core.Units.metres(metres) * Math.max(0.1, areaScale);
    }

    static double radius(final SkillBuild build) {
        return radius(build.definition().id(), build.qualityFlat(SIGIL_RADIUS), build.areaScale());
    }

    /** 法印的階段：花掉的魔力累積到門檻、離上一次升階超過 1 秒就升一階，最多 4 階。純邏輯（測試用）。 */
    static final class Stages {
        int stages;
        double spent;
        long lastStage = Long.MIN_VALUE / 4;

        /** 花了 amount 魔力（門檻 threshold）：累積起來、試著升階。回傳有沒有升。 */
        boolean spend(final double amount, final double threshold, final long now) {
            if (amount > 0 && stages < MAX_STAGES) {
                spent += amount;
            }
            return advance(threshold, now);
        }

        /** 累積夠了、隔夠久就升一階（多出來的留著，下一秒再升）。 */
        boolean advance(final double threshold, final long now) {
            if (stages >= MAX_STAGES || threshold <= 0 || spent + 1e-9 < threshold || now - lastStage < STAGE_GAP_TICKS) {
                return false;
            }
            stages++;
            spent -= threshold;
            lastStage = now;
            if (stages >= MAX_STAGES) {
                spent = 0;
            }
            return true;
        }
    }

    /** 噬屍宴：吃幾具（身邊的屍體、上限 5 ＋ 品質）。純函式。 */
    static int feastCount(final int nearby, final double qualityExtra) {
        return Math.max(0, Math.min(nearby, FEAST_CORPSES + OriginalSkills.qualityInt(qualityExtra)));
    }

    /** 噬屍宴：每秒回復（每具的總量 × 具數 ÷ 3 秒）。純函式。 */
    static double feastRate(final double perCorpse, final int corpses) {
        return Math.max(0, perCorpse) * Math.max(0, corpses) / FEAST_SECONDS;
    }

    /** 一塊法印／奉獻地面。 */
    private static final class Ground {
        final UUID owner;
        final String skillId;
        final String name;
        final World world;
        final Location center;
        final double radius;
        final long until;
        /** 法印：每一階的增益；聖地：整份增益。 */
        final List<ScaledMod> effects;
        final int level;
        final Stages stages = new Stages();
        boolean gone;

        Ground(final UUID owner, final SkillBuild build, final Location center, final double radius, final long until) {
            this.owner = owner;
            this.skillId = build.definition().id();
            this.name = build.definition().name();
            this.world = center.getWorld();
            this.center = center;
            this.radius = radius;
            this.until = until;
            this.effects = build.definition().params().mark().effects();
            this.level = build.level();
        }
    }

    /** 站在裡面（水平距離在半徑內、上下 3 格內）。純函式。 */
    static boolean inside(final double dx, final double dy, final double dz, final double radius) {
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= 3;
    }

    private static boolean inside(final Ground ground, final Location at) {
        return at != null && at.getWorld() == ground.world && inside(at.getX() - ground.center.getX(), at.getY() - ground.center.getY(),
                at.getZ() - ground.center.getZ(), ground.radius);
    }

    /** 放之前的檢查：噬屍宴身邊要有屍體。可以放回傳 null。 */
    String problem(final Player player, final SkillBuild build) {
        if (FEAST.equals(build.definition().id())) {
            return nearbyCorpses(player, build).isEmpty() ? "附近沒有屍體可以吃" : null;
        }
        return null;
    }

    private List<tw.exilecore.combat.Corpses.Corpse> nearbyCorpses(final Player player, final SkillBuild build) {
        if (plugin.combat() == null || plugin.combat().corpses() == null) {
            return List.of();
        }
        final Location at = player.getLocation();
        final List<tw.exilecore.combat.Corpses.Corpse> near = new ArrayList<>(plugin.combat().corpses().near(at.getWorld().getUID(), at.getX(), at.getZ(),
                radius(build), plugin.getServer().getCurrentTick()));
        near.sort(java.util.Comparator.comparingDouble(c -> (c.x() - at.getX()) * (c.x() - at.getX()) + (c.z() - at.getZ()) * (c.z() - at.getZ())));
        return near;
    }

    /** 放出去（SkillService 在扣完消耗、開始冷卻之後呼叫）。回傳提示文字。 */
    String cast(final Player player, final SkillBuild build, final long now) {
        final String id = build.definition().id();
        if (FEAST.equals(id)) {
            return feast(player, build, now);
        }
        final double radius = radius(build);
        final long until = now + (sigil(id) ? build.durationTicks(build.definition().params().mark().duration())
                : build.groundDurationTicks(build.definition().params().mark().duration())); // 聖地是地面效果（Persistent Ground）
        final int limit = sigil(id) ? SIGIL_LIMIT : CONSECRATE_LIMIT;
        final List<Ground> mine = new ArrayList<>();
        for (final Ground ground : grounds) {
            if (!ground.gone && ground.owner.equals(player.getUniqueId()) && ground.skillId.equals(id)) {
                mine.add(ground);
            }
        }
        mine.sort(java.util.Comparator.comparingLong(g -> g.until));
        while (mine.size() >= limit) {
            mine.removeFirst().gone = true; // 超過上限：最舊的先消失
        }
        grounds.removeIf(g -> g.gone);
        final Location center = player.getLocation().clone();
        grounds.add(new Ground(player.getUniqueId(), build, center, radius, until));
        service.ownBuff(player, BUFF_PREFIX + id, id);
        player.getWorld().playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, sigil(id) ? 1.5f : 1.2f);
        start();
        return sigil(id) ? "<#c9d6e3>✦ " + ItemRenderer.escape(build.definition().name()) + "：站在法印裡花魔力升階（每 "
                + Math.round(manaPercent(id)) + "% 最大魔力一階，最多 " + MAX_STAGES + " 階）"
                : "<#fff2a8>✦ " + ItemRenderer.escape(build.definition().name()) + "：站在奉獻地面上回復生命";
    }

    private String feast(final Player player, final SkillBuild build, final long now) {
        final List<tw.exilecore.combat.Corpses.Corpse> near = nearbyCorpses(player, build);
        final int eaten = feastCount(near.size(), build.qualityFlat(tw.exilecore.stats.GemQualityKeys.CORPSES_FLAT));
        for (int i = 0; i < eaten; i++) {
            final tw.exilecore.combat.Corpses.Corpse corpse = near.get(i);
            plugin.combat().corpses().remove(corpse);
            Fx.spawn(player.getWorld(), Particle.SOUL, new Location(player.getWorld(), corpse.x(), corpse.y() + 0.5, corpse.z()), 8, 0.3, 0.3, 0.3, 0.02);
        }
        if (eaten <= 0) {
            return "<gray>" + ItemRenderer.escape(build.definition().name()) + "：附近沒有屍體";
        }
        plugin.players().get(player).markRecent(tw.exilecore.stats.Recently.CORPSE, now); // 「最近消耗過屍體」
        double life = 0;
        double mana = 0;
        for (final ScaledMod effect : build.definition().params().mark().effects()) {
            final double value = effect.toModifier(build.level(), 1.0, "").value();
            if (StatKeys.LIFE_REGEN.equals(effect.stat())) {
                life += value;
            } else if (StatKeys.MANA_REGEN.equals(effect.stat())) {
                mana += value;
            }
        }
        feasts.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>())
                .add(new double[] {feastRate(life, eaten), feastRate(mana, eaten), now + Math.round(FEAST_SECONDS * 20)});
        service.ownBuff(player, BUFF_PREFIX + FEAST, FEAST);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 1f, 0.6f);
        start();
        return "<#c0392b>✦ " + ItemRenderer.escape(build.definition().name()) + "：吃掉 " + eaten + " 具屍體，3 秒內回復 "
                + Math.round(life * eaten) + " 生命、" + Math.round(mana * eaten) + " 魔力";
    }

    /** 你花了魔力（技能消耗、引導的逐秒消耗）：站在自己的法印裡就累積升階。 */
    void spent(final Player player, final double mana) {
        if (mana <= 0 || grounds.isEmpty()) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final long now = plugin.getServer().getCurrentTick();
        for (final Ground ground : grounds) {
            if (!ground.gone && sigil(ground.skillId) && ground.owner.equals(player.getUniqueId()) && inside(ground, player.getLocation())) {
                if (ground.stages.spend(mana, state.maxMana() * manaPercent(ground.skillId) / 100.0, now)) {
                    staged(player, ground);
                }
            }
        }
    }

    private void staged(final Player player, final Ground ground) {
        player.getWorld().playSound(ground.center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 0.8f + 0.2f * ground.stages.stages);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c9d6e3>" + ItemRenderer.escape(ground.name) + " " + ground.stages.stages + " 階"));
    }

    private void start() {
        if (task == null) {
            task = plugin.repeat("skill.sacred-grounds", this::tick, PERIOD, PERIOD);
        }
    }

    private void tick() {
        final long now = plugin.getServer().getCurrentTick();
        for (final Iterator<Ground> it = grounds.iterator(); it.hasNext(); ) {
            final Ground ground = it.next();
            final Player owner = plugin.getServer().getPlayer(ground.owner);
            if (ground.gone || now >= ground.until || owner == null || !owner.isOnline() || owner.getWorld() != ground.world) {
                it.remove();
                continue;
            }
            if (sigil(ground.skillId) && inside(ground, owner.getLocation())
                    && ground.stages.advance(plugin.players().get(owner).maxMana() * manaPercent(ground.skillId) / 100.0, now)) {
                staged(owner, ground); // 隔 1 秒內花的魔力留著，時間到再升
            }
            final double scale = sigil(ground.skillId) ? ground.stages.stages : 1.0;
            if (scale > 0) {
                final List<Modifier> mods = new ArrayList<>();
                final String label = sigil(ground.skillId) ? ground.name + " " + ground.stages.stages + " 階" : ground.name;
                for (final ScaledMod effect : ground.effects) {
                    mods.add(effect.toModifier(ground.level, scale, label));
                }
                for (final Player ally : allies(owner)) {
                    if (inside(ground, ally.getLocation())) {
                        service.buff(ally, BUFF_PREFIX + ground.skillId, label, BUFF_TICKS, mods);
                    }
                }
            }
            if (now % 10 < PERIOD) {
                draw(ground, now);
            }
        }
        for (final Iterator<Map.Entry<UUID, List<double[]>>> it = feasts.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, List<double[]>> entry = it.next();
            entry.getValue().removeIf(part -> part[2] <= now);
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (entry.getValue().isEmpty() || player == null || !player.isOnline()) {
                it.remove();
                continue;
            }
            double life = 0;
            double mana = 0;
            long until = now;
            for (final double[] part : entry.getValue()) {
                life += part[0];
                mana += part[1];
                until = Math.max(until, (long) part[2]);
            }
            final String name = service.config().skill(FEAST) == null ? "噬屍宴" : service.config().skill(FEAST).name();
            service.buff(player, BUFF_PREFIX + FEAST, name, (int) Math.max(1, Math.min(BUFF_TICKS, until - now)), List.of(
                    Modifier.flat(StatKeys.LIFE_REGEN, life, name), Modifier.flat(StatKeys.MANA_REGEN, mana, name)));
        }
        if (grounds.isEmpty() && feasts.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 你與隊友（同一隊、同一個世界）。 */
    private List<Player> allies(final Player owner) {
        final List<Player> list = new ArrayList<>();
        list.add(owner);
        if (plugin.party() != null) {
            for (final UUID member : plugin.party().groupMembers(owner.getUniqueId())) {
                final Player ally = member.equals(owner.getUniqueId()) ? null : plugin.getServer().getPlayer(member);
                if (ally != null && ally.getWorld() == owner.getWorld()) {
                    list.add(ally);
                }
            }
        }
        return list;
    }

    /** 2026-11-02：外框走 {@link Fx#zone}（每 0.5 秒重畫、點數照周長；同一個人疊在一起的只畫一個）。 */
    private void draw(final Ground ground, final long now) {
        final boolean sigil = sigil(ground.skillId);
        final Particle particle = sigil ? (POWER.equals(ground.skillId) ? Particle.ENCHANT : Particle.HAPPY_VILLAGER) : Particle.END_ROD;
        Fx.zone(ground, ground.center.clone().add(0, 0.15, 0), ground.radius, Fx.Look.edge(particle, null), (int) Math.max(0, ground.until - now),
                ground.owner, false);
        if (sigil && ground.stages.stages > 0) {
            Fx.as(ground.owner, () -> Fx.ambient(() -> Fx.spawn(ground.world, Particle.ENCHANT, ground.center.clone().add(0, 0.8, 0),
                    4 * ground.stages.stages, ground.radius / 3, 0.4, ground.radius / 3, 0.3)));
        }
    }

    /** 技能說明（技能面板）：取代 guard 的「N 秒內：…」那一行。 */
    static String tooltip(final SkillBuild build) {
        final String id = build.definition().id();
        final List<String> parts = new ArrayList<>();
        for (final ScaledMod effect : build.definition().params().mark().effects()) {
            parts.add("<white>" + effectText(effect.toModifier(build.level(), 1.0, "")) + "</white>");
        }
        final double radius = radius(build);
        final double seconds = build.definition().params().mark().duration() * (sigil(id) ? build.durationScale() : build.groundDurationScale());
        if (sigil(id)) {
            return String.format(Locale.ROOT, "<gray>在腳下放法印：半徑 <white>%.1f</white> 格、<white>%.1f</white> 秒、同時 1 個；站在裡面的你與隊友，"
                            + "你在裡面每花掉 <white>%d%%</white> 最大魔力升一階（最少隔 1 秒、最多 %d 階），每階：", radius, seconds,
                    Math.round(manaPercent(id)), MAX_STAGES) + String.join("<gray>、", parts);
        }
        if (CONSECRATE.equals(id)) {
            return String.format(Locale.ROOT, "<gray>在腳下留下奉獻地面：半徑 <white>%.1f</white> 格、<white>%.1f</white> 秒；站在上面的你與隊友：",
                    radius, seconds) + String.join("<gray>、", parts);
        }
        final List<String> per = new ArrayList<>();
        for (final ScaledMod effect : build.definition().params().mark().effects()) {
            final String what = StatKeys.LIFE_REGEN.equals(effect.stat()) ? "生命" : StatKeys.MANA_REGEN.equals(effect.stat()) ? "魔力" : null;
            if (what != null) {
                per.add("<white>" + Math.round(effect.toModifier(build.level(), 1.0, "").value()) + "</white> " + what);
            }
        }
        return String.format(Locale.ROOT, "<gray>吃掉身邊 <white>%.1f</white> 格內最多 <white>%d</white> 具屍體，每具 3 秒內回復 ", radius,
                feastCount(Integer.MAX_VALUE, build.qualityFlat(tw.exilecore.stats.GemQualityKeys.CORPSES_FLAT))) + String.join("<gray>、", per)
                + "<gray>（沒有屍體不能放）";
    }

    /** 一條增益的說明（受到的詛咒效果寫成白話；其他照 ModifierText）。純函式。 */
    static String effectText(final Modifier modifier) {
        if (StatKeys.CURSE_EFFECT_ON_SELF.equals(modifier.stat()) && modifier.type() == tw.exilecore.stats.ModType.INCREASED) {
            return "受到的詛咒效果" + (modifier.value() < 0 ? "減少 " : "增加 ") + GemQualityText.number(Math.abs(modifier.value()) * 100) + "%";
        }
        return ModifierText.describe(modifier);
    }

    /** 玩家離線、換角色：清掉。 */
    void clear(final UUID player) {
        for (final Ground ground : grounds) {
            if (ground.owner.equals(player)) {
                ground.gone = true;
            }
        }
        grounds.removeIf(g -> g.gone);
        feasts.remove(player);
    }
}
