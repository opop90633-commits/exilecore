package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.HitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：午夜天頂（PoE2 Midnight Zenith，傳奇項鍊暮色瓣落賦予；PoB2 MidnightStarPlayer）。
 * 常駐（開著時）：
 * <ul>
 *   <li>用其他技能的暴擊擊中殺死敵人：獲得等同牠強度的極地花瓣（普通 1、魔法 2、稀有 10、傳奇與 Boss 20；{@link Warcries#power}）；</li>
 *   <li>暴擊擊中稀有或傳奇敵人：獲得 2～7 片（逐級表 limit.zenith_rare_petals）；</li>
 *   <li>每次獲得花瓣時，有 {@value #CONSUME} 片而且冰晶還沒到上限（{@value #BASE_MAX} 顆，品質每 1% +0.1、「+上限」也加）：用掉 {@value #CONSUME} 片，
 *       在那個目標周圍 {@value #RING} 格放 {@value #PER_TRIGGER} 顆冰晶（{@value #SECONDS} 秒；超過上限的那幾顆不放）。</li>
 * </ul>
 * 冰晶照 {@link IceCrystals}：生命照逐級表 limit.crystal_life，被你的擊中打碎時炸開 proc_crystal_midnight（2.2 格冰冷、暴擊 11%）。
 * 這個技能自己（冰晶爆炸）的暴擊不給花瓣（base_skill_cannot_generate_midnight_star_petals）。
 */
public final class MidnightZenith implements Listener {

    static final String SKILL = "midnight_zenith";
    static final String BURST = "proc_crystal_midnight";
    /** 一次用掉幾片、放幾顆、基礎上限、放在目標周圍幾格、持續幾秒（unique_nattblom_crystals_per_trigger 5、crystal_radius 8、max 10、duration 10000）。 */
    static final int CONSUME = 10;
    static final int PER_TRIGGER = 5;
    static final int BASE_MAX = 10;
    static final double RING = 0.8;
    static final double SECONDS = 10;
    /** 品質給的冰晶上限（gem_quality.yml，每 1% 品質 0.1）。 */
    static final String QUALITY_MAX = "zenith.max_crystals";
    /** 逐級表：暴擊擊中稀有或傳奇敵人時獲得幾片（skills.yml 的 limit.zenith_rare_petals）。 */
    static final String RARE_PETALS = "zenith_rare_petals";
    /** 致命一擊：死亡前幾 tick 內的那一下算（跟 VaalOffence 一樣，多留一點給投射物）。 */
    static final long KILL_WINDOW = 1;

    private final ExileCore plugin;
    private final SkillService service;
    /** 玩家 → 身上的極地花瓣。 */
    private final Map<UUID, Integer> petals = new HashMap<>();
    /** 敵人 → 最後一下是哪個玩家用其他技能打的暴擊（最後一下不是這種就拿掉）。 */
    private final Map<UUID, LastCrit> lastCrit = new HashMap<>();

    record LastCrit(UUID player, long tick) {
    }

    MidnightZenith(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * 純函式：身上有 have 片、這次獲得 gained 片、現在有 crystals 顆午夜天頂的冰晶、上限 max 顆 → {之後的花瓣, 這次要放幾顆}。
     * 每次獲得只試一次：有 {@value #CONSUME} 片而且還沒到上限就用掉 {@value #CONSUME} 片，放 {@value #PER_TRIGGER} 顆（最多放到上限）。
     */
    static int[] gain(final int have, final int gained, final int crystals, final int max) {
        final int total = Math.max(0, have) + Math.max(0, gained);
        if (gained <= 0 || total < CONSUME || crystals >= max) {
            return new int[] {total, 0};
        }
        return new int[] {total - CONSUME, Math.min(PER_TRIGGER, max - Math.max(0, crystals))};
    }

    /** 純函式：冰晶上限（基礎 {@value #BASE_MAX}，加品質給的（捨去）與「+上限」）。 */
    static int maxCrystals(final double fromQuality, final int limitBonus) {
        return BASE_MAX + (int) Math.floor(Math.max(0, fromQuality) + 1e-9) + Math.max(0, limitBonus);
    }

    /** 這個玩家身上的花瓣（給說明、測試看）。 */
    int petals(final UUID player) {
        return petals.getOrDefault(player, 0);
    }

    private boolean active(final Player player) {
        return player.isOnline() && !player.isDead() && service.auraOn(player, SKILL);
    }

    /** 其他技能（不是午夜天頂自己、也不是它的冰晶爆炸）。 */
    static boolean otherSkill(final String skillId) {
        return skillId != null && !SKILL.equals(skillId) && !BURST.equals(skillId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.defender() instanceof Player || !event.result().hit()
                || event.result().blocked()) {
            return;
        }
        final UUID victim = event.defender().getUniqueId();
        final boolean qualifies = event.result().crit() && event.skill() != null && otherSkill(event.skill().id()) && active(player);
        if (!qualifies) {
            lastCrit.remove(victim); // 最後一下不是用其他技能的暴擊：死了也不算
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        lastCrit.put(victim, new LastCrit(player.getUniqueId(), now));
        if (lastCrit.size() > 4096) {
            lastCrit.entrySet().removeIf(entry -> now - entry.getValue().tick() > 20);
        }
        final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(event.defender()).orElse(null);
        if (data != null && (data.boss() || data.rarity() == MonsterRarity.RARE || data.rarity() == MonsterRarity.UNIQUE)) {
            final SkillBuild build = service.equippedBuild(player, SKILL);
            if (build != null) {
                add(player, build, StageAttacks.limit(build.profile(), RARE_PETALS, 2), event.defender().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        final LastCrit crit = lastCrit.remove(victim.getUniqueId());
        if (crit == null || victim instanceof Player || plugin.monsters() == null
                || plugin.getServer().getCurrentTick() - crit.tick() > KILL_WINDOW) {
            return;
        }
        final Player player = plugin.getServer().getPlayer(crit.player());
        final MonsterData data = plugin.monsters().data(victim).orElse(null);
        if (player == null || data == null || !active(player)) {
            return;
        }
        final SkillBuild build = service.equippedBuild(player, SKILL);
        if (build != null) {
            add(player, build, (int) Math.round(Warcries.power(data)), victim.getLocation());
        }
    }

    /** 獲得 gained 片花瓣；夠了就在 at 周圍放冰晶。 */
    private void add(final Player player, final SkillBuild build, final int gained, final Location at) {
        if (gained <= 0) {
            return;
        }
        final IceCrystals crystals = service.crystals();
        final int max = maxCrystals(build.qualityFlat(QUALITY_MAX), SkillLimits.bonus(plugin.players().sheet(player), build));
        final int[] next = gain(petals(player.getUniqueId()), gained, crystals.count(player.getUniqueId(), SKILL), max);
        petals.put(player.getUniqueId(), next[0]);
        if (next[1] > 0 && at.getWorld() != null && at.getWorld().equals(player.getWorld())) {
            crystals.spawnRing(player, build, at, next[1], RING, SECONDS);
            Fx.spawn(at.getWorld(), Particle.CHERRY_LEAVES, at.clone().add(0, 1, 0), 24, 0.6, 0.5, 0.6, 0.02);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        petals.remove(event.getPlayer().getUniqueId());
    }
}
