package tw.exilecore.map;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 終局 Boss（含極境）出現後一分鐘的傷害紀錄（2026-10，查「蝕骨之王一開場血掉一大截」）：寫進伺服器紀錄（logs/latest.log），
 * 搜尋「[Boss 紀錄]」。
 * <ul>
 *   <li>出現：生命、原版血量的換算（原版 1 點血 = 幾點生命）、算進去的人數。</li>
 *   <li>有掉血的每一秒一行：血從幾 % 到幾 %、誰用什麼技能打了幾下扣多少原版血、
 *       不是玩家打的（窒息、摔落、怪物、爆炸…）、沒有傷害事件的扣血（持續傷害）、被擋掉的。</li>
 *   <li>換階段、護盾、死亡各一行。</li>
 * </ul>
 */
final class BossDamageLog implements Listener {

    /** 記多久（tick）。 */
    static final long WINDOW_TICKS = 60 * 20;

    private static final class Track {
        final String name;
        final long start;
        /** 原版 1 點血 = 幾點生命。 */
        final double perVanilla;
        long secondStart;
        double secondStartHealth;
        double eventDamage;
        final Map<String, double[]> sources = new LinkedHashMap<>();

        Track(final String name, final long start, final double perVanilla, final double health) {
            this.name = name;
            this.start = start;
            this.perVanilla = perVanilla;
            this.secondStart = start;
            this.secondStartHealth = health;
        }
    }

    private final ExileCore plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();

    BossDamageLog(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** Boss 剛出現：開始記。 */
    void track(final LivingEntity boss, final String where, final String name, final int level) {
        final double life = plugin.monsters().data(boss).map(data -> plugin.monsters().sheet(boss, data)
                .get(tw.exilecore.stats.StatKeys.LIFE_MAX)).orElse(0.0);
        final int players = plugin.monsters().playersAround(boss);
        final double max = maxHealth(boss);
        final double perVanilla = max > 0 && life > 0 ? life / max : 1;
        final long now = plugin.getServer().getCurrentTick();
        tracks.put(boss.getUniqueId(), new Track(name, now, perVanilla, boss.getHealth()));
        plugin.getSLF4JLogger().info(String.format(Locale.ROOT,
                "[Boss 紀錄] %s：%s Lv.%d 出現：生命 %,.0f（算 %d 人）、原版血量 %.0f/%.0f（原版 1 點 = %,.1f 生命）",
                where, name, level, life, players, boss.getHealth(), max, perVanilla));
    }

    /** 換階段、護盾這類事件：寫一行（先把這一秒到目前為止的傷害寫出來）。 */
    void note(final LivingEntity boss, final String what) {
        final Track track = tracks.get(boss.getUniqueId());
        if (track == null) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        flush(boss, track, now);
        plugin.getSLF4JLogger().info(String.format(Locale.ROOT, "[Boss 紀錄] %s %.1f 秒：%s（%.1f%%）", track.name, (now - track.start) / 20.0,
                what, percent(boss)));
    }

    /** 每 tick：滿一秒寫一行；時間到、Boss 不見了就停。 */
    void tick() {
        if (tracks.isEmpty()) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final Iterator<Map.Entry<UUID, Track>> iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Track> entry = iterator.next();
            final Track track = entry.getValue();
            final Entity entity = plugin.getServer().getEntity(entry.getKey());
            final LivingEntity boss = entity instanceof LivingEntity living ? living : null;
            if (boss == null || !boss.isValid() || boss.isDead()) {
                if (boss != null) {
                    flush(boss, track, now); // 倒下前最後那一段
                }
                plugin.getSLF4JLogger().info(String.format(Locale.ROOT, "[Boss 紀錄] %s %.1f 秒：%s", track.name,
                        (now - track.start) / 20.0, boss != null && boss.isDead() ? "倒下" : "不見了（重置或實例關閉）"));
                iterator.remove();
                continue;
            }
            if (now - track.secondStart >= 20) {
                flush(boss, track, now);
            }
            if (now - track.start >= WINDOW_TICKS) {
                plugin.getSLF4JLogger().info(String.format(Locale.ROOT, "[Boss 紀錄] %s：記滿 %d 秒，停止（%.1f%%）", track.name,
                        WINDOW_TICKS / 20, percent(boss)));
                iterator.remove();
            }
        }
    }

    /** 每一下傷害（被擋掉的也記）：誰、用什麼、扣了多少原版血。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(final EntityDamageEvent event) {
        final Track track = tracks.get(event.getEntity().getUniqueId());
        if (track == null) {
            return;
        }
        final String key = (event.isCancelled() ? "擋掉 " : "") + source(event);
        final double[] value = track.sources.computeIfAbsent(key, k -> new double[2]);
        value[0]++;
        if (!event.isCancelled()) {
            final double damage = event.getFinalDamage();
            value[1] += damage;
            track.eventDamage += damage;
        }
    }

    private void flush(final LivingEntity boss, final Track track, final long now) {
        final double health = boss.getHealth();
        final double lost = track.secondStartHealth - health;
        if (Math.abs(lost) > 1e-6 || !track.sources.isEmpty()) {
            final List<String> parts = new ArrayList<>();
            for (final Map.Entry<String, double[]> source : track.sources.entrySet()) {
                parts.add(String.format(Locale.ROOT, "%s ×%d = %.1f", source.getKey(), Math.round(source.getValue()[0]), source.getValue()[1]));
            }
            final double other = lost - track.eventDamage;
            if (Math.abs(other) > 0.05) {
                parts.add(String.format(Locale.ROOT, "%s %.1f", other > 0 ? "沒有傷害事件的扣血（持續傷害）" : "回血", Math.abs(other)));
            }
            final double max = maxHealth(boss);
            plugin.getSLF4JLogger().info(String.format(Locale.ROOT, "[Boss 紀錄] %s %.1f–%.1f 秒：%.1f%% → %.1f%%（原版 %+.1f ≈ 生命 %+,.0f）｜%s",
                    track.name, (track.secondStart - track.start) / 20.0, (now - track.start) / 20.0,
                    max > 0 ? track.secondStartHealth / max * 100 : 0, max > 0 ? health / max * 100 : 0, -lost, -lost * track.perVanilla,
                    parts.isEmpty() ? "（沒有傷害事件）" : String.join("｜", parts)));
        }
        track.secondStart = now;
        track.secondStartHealth = health;
        track.eventDamage = 0;
        track.sources.clear();
    }

    /** 這一下是誰打的：玩家（技能名稱）、怪物、環境。 */
    private String source(final EntityDamageEvent event) {
        final String cause = event.getCause().name();
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity owner = byEntity.getDamager();
            if (owner instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                owner = shooter;
            }
            if (owner instanceof Player player) {
                final String skill = plugin.combat() == null || !(event.getEntity() instanceof LivingEntity target) ? null
                        : plugin.combat().skillDamage().currentSkill(target);
                return player.getName() + " " + (skill == null ? "原版的" + cause : skillName(skill));
            }
            final String name = owner.customName() == null ? owner.getType().name()
                    : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(owner.customName());
            return "非玩家 " + name + "（" + cause + "）";
        }
        return "環境 " + cause;
    }

    private String skillName(final String profileId) {
        final String base = profileId.contains(".") && !profileId.startsWith("unique.") && !profileId.startsWith("boss.")
                ? profileId.substring(0, profileId.indexOf('.')) : profileId;
        if (plugin.skills() != null) {
            final tw.exilecore.skill.SkillDefinition definition = plugin.skills().config().skill(base);
            if (definition != null) {
                return definition.name() + (base.equals(profileId) ? "" : "（" + profileId.substring(base.length() + 1) + "）");
            }
        }
        return profileId;
    }

    private static double maxHealth(final LivingEntity entity) {
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 0 : max.getValue();
    }

    private static double percent(final LivingEntity boss) {
        final double max = maxHealth(boss);
        return max > 0 ? boss.getHealth() / max * 100 : 0;
    }
}
