package tw.exilecore.skill;

import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-27 照 PoE2 0.5 的兩顆「常駐＋按鍵放出」精魂寶石（這次之前兩顆都是自己編數值的主動技能：鐵壁守是 4 秒護甲增益、
 * 瘟囊爆是冷卻 5 秒、固定傷害的毒爆）：
 * <ul>
 *   <li>鐵壁守（Iron Ward，保留 30 精魂）：開著的時候，被擊中時護甲與其他減傷擋下的物理傷害存起來（1 級 100%、20 級 480%、40 級 880%，
 *       上限 65 → 22,886 → 82,074）；按鍵放出：以你為中心 6 格的骨刺爆開，造成跟存量一樣多的物理傷害、暈眩累積更多 200%，
 *       存越滿範圍越大（滿了更多 200% 範圍）。</li>
 *   <li>瘟囊爆（Plague Bearer，保留 30 精魂）：開著的時候，你自己的擊中造成的中毒預計傷害有 4% 存起來（上限 43 → 15,257 → 54,716）；
 *       按鍵放出：以你為中心 1.8 格爆開，造成跟存量一樣多的物理傷害、一定中毒、暈眩累積更少 80%，存越滿範圍越大（滿了更多 600% 範圍）；
 *       放出來的這一下造成的中毒不再存。</li>
 * </ul>
 * 中毒的預計傷害（平衡等效：引擎在擊中事件時還沒決定中不中毒）：照 ailments.yml 的中毒（每秒 20%、2 秒）＝ 這一下物理＋混沌傷害的 40%
 * × 中毒機率（技能的 poison.chance，最多 100%）。
 * 數值照 poe2db（Iron_Ward、Plague_Bearer 的等級表）。放出的傷害是隱藏技能 iron_ward_release／plague_bearer_release（skills.yml）的
 * 範圍與修飾器，基礎傷害換成存量。存量下線清掉。
 */
final class StoredReleases implements Listener {

    static final String IRON_WARD = "iron_rampart_guard";
    static final String PLAGUE_BEARER = "pestilence_sac_burst";
    static final String IRON_WARD_RELEASE = "iron_ward_release";
    static final String PLAGUE_BEARER_RELEASE = "plague_bearer_release";
    /** 光環的效果（說明用；開著才存，看 AuraService 開著沒）。 */
    static final String FLAG_IRON_WARD = "flag.iron_rampart_guard";
    static final String FLAG_PLAGUE_BEARER = "flag.pestilence_sac_burst";

    /** 鐵壁守：擋下的物理傷害存幾 %（1～40 級，poe2db）。 */
    static final double[] IRON_WARD_PERCENT = {100, 120, 140, 160, 180, 200, 220, 240, 260, 280, 300, 320, 340, 360, 380, 400, 420, 440, 460, 480,
            500, 520, 540, 560, 580, 600, 620, 640, 660, 680, 700, 720, 740, 760, 780, 800, 820, 840, 860, 880};
    /** 鐵壁守：存量上限（1～40 級，poe2db）。 */
    static final double[] IRON_WARD_CAP = {65, 116, 195, 296, 432, 602, 828, 1124, 1497, 1980, 2589, 3359, 4335, 5555, 7092, 9029, 11451, 14457,
            18213, 22886, 23858, 25868, 29481, 33951, 39480, 41454, 43526, 45702, 47988, 50388, 52905, 55551, 58329, 61245, 64308, 67524, 70901,
            74445, 78167, 82074};
    /** 鐵壁守：存滿時範圍更多 200%。 */
    static final double IRON_WARD_AREA = 2.0;

    /** 瘟囊爆：中毒預計傷害存 4%（每一級一樣）。 */
    static final double PLAGUE_PERCENT = 4;
    /** 瘟囊爆：存量上限（1～40 級，poe2db）。 */
    static final double[] PLAGUE_CAP = {43, 77, 130, 197, 288, 401, 552, 749, 998, 1320, 1726, 2239, 2890, 3703, 4728, 6019, 7634, 9638, 12142,
            15257, 15905, 17245, 19654, 22634, 26320, 27636, 29017, 30468, 31992, 33592, 35270, 37034, 38886, 40830, 42872, 45016, 47267, 49630,
            52111, 54716};
    /** 瘟囊爆：存滿時範圍更多 600%。 */
    static final double PLAGUE_AREA = 6.0;
    /** 中毒預計傷害 = 物理＋混沌 × 每秒 20% × 2 秒。 */
    static final double POISON_EXPECTED = 0.4;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, double[]> stored = new HashMap<>();

    StoredReleases(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    static double at(final double[] table, final int level) {
        return table[Math.max(1, Math.min(table.length, level)) - 1];
    }

    /** 存進去（不超過上限）；回傳存完的量。 */
    static double store(final double current, final double add, final double cap) {
        return Math.max(0, Math.min(cap, current + Math.max(0, add)));
    }

    /** 存滿的程度換成範圍（半徑）倍率：範圍更多 extra × 滿的比例，半徑開根號。 */
    static double radiusScale(final double amount, final double cap, final double extra) {
        final double fill = cap <= 0 ? 0 : Math.max(0, Math.min(1, amount / cap));
        return Math.sqrt(1 + extra * fill);
    }

    /** 瘟囊爆：這一下的中毒預計傷害（物理＋混沌 × 40% × 中毒機率）。 */
    static double expectedPoison(final double physicalAndChaos, final double chancePercent) {
        return Math.max(0, physicalAndChaos) * POISON_EXPECTED * Math.max(0, Math.min(100, chancePercent)) / 100.0;
    }

    double amount(final UUID player, final String skillId) {
        final double[] values = stored.get(player);
        return values == null ? 0 : values[IRON_WARD.equals(skillId) ? 0 : 1];
    }

    private int level(final PlayerState state, final String skillId) {
        if (state == null || state.skills() == null) {
            return 0;
        }
        final SkillLoadout.Entry entry = state.skills().skill(state.skills().find(skillId));
        return entry == null ? 0 : entry.level();
    }

    private boolean active(final Player player, final String skillId) {
        return service.auras().isActive(player.getUniqueId(), skillId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!event.result().hit() || event.result().blocked()) {
            return;
        }
        if (event.defender() instanceof Player defender && active(defender, IRON_WARD)) {
            final double prevented = event.result().outgoing().get(DamageType.PHYSICAL) - event.result().dealt().get(DamageType.PHYSICAL);
            if (prevented > 0) {
                final int level = level(plugin.players().get(defender), IRON_WARD);
                if (level > 0) {
                    final double[] values = stored.computeIfAbsent(defender.getUniqueId(), uuid -> new double[2]);
                    values[0] = store(values[0], prevented * at(IRON_WARD_PERCENT, level) / 100.0, at(IRON_WARD_CAP, level));
                }
            }
        }
        if (event.attacker() instanceof Player attacker && event.credit() == attacker && active(attacker, PLAGUE_BEARER)
                && event.skill() != null && !PLAGUE_BEARER_RELEASE.equals(event.skill().id())) {
            final DamagePacket out = event.result().outgoing();
            final double base = out.get(DamageType.PHYSICAL) + out.get(DamageType.CHAOS);
            if (base > 0) {
                final StatSheet effective = event.skill().effective(plugin.players().sheet(attacker));
                final double chance = effective.flat(tw.exilecore.stats.StatKeys.POISON_CHANCE, event.skill().tags());
                final double expected = expectedPoison(base, chance);
                final int level = level(plugin.players().get(attacker), PLAGUE_BEARER);
                if (expected > 0 && level > 0) {
                    final double[] values = stored.computeIfAbsent(attacker.getUniqueId(), uuid -> new double[2]);
                    // 2026-10-28 照 PoE2 Plague Bearer 的品質：另外存中毒傷害的 (0—2)%
                    values[1] = store(values[1], expected * (PLAGUE_PERCENT + service.qualityFlat(attacker, PLAGUE_BEARER,
                            tw.exilecore.stats.GemQualityKeys.PLAGUE_BEARER_PERCENT)) / 100.0, at(PLAGUE_CAP, level));
                }
            }
        }
    }

    /**
     * 按下鐵壁守、瘟囊爆的按鍵（光環開著時 SkillService 呼叫）：把存的放出來。不是這兩顆回傳 null。
     */
    String press(final Player player, final SkillBuild build) {
        final String id = build.definition().id();
        final boolean ward = IRON_WARD.equals(id);
        if (!ward && !PLAGUE_BEARER.equals(id)) {
            return null;
        }
        final double[] values = stored.get(player.getUniqueId());
        final double amount = values == null ? 0 : values[ward ? 0 : 1];
        if (amount < 1) {
            return ward ? "<gray>鐵壁守還沒存到傷害（被擊中時擋下的物理傷害才會存）" : "<gray>瘟囊爆還沒存到毒（你的擊中讓敵人中毒才會存）";
        }
        final SkillDefinition release = service.config().skill(ward ? IRON_WARD_RELEASE : PLAGUE_BEARER_RELEASE);
        if (release == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        // 2026-10-28 照 PoE2：寶石的品質帶到爆開上（鐵壁守：暈眩累積更多 (0—50)%）
        final SkillBuild base = SkillBuild.of(service.config(), release, build.level(), state.level(), false, build.supports(), state.weaponTags(),
                plugin.players().sheet(player), java.util.Set.of(), build.qualityModifiers());
        final SkillProfile profile = base.profile();
        final DamagePacket packet = new DamagePacket().set(DamageType.PHYSICAL, amount);
        final SkillBuild withDamage = base.withProfile(new SkillProfile(profile.id(), profile.tags(), profile.effectiveness(), packet,
                new DamagePacket(packet), profile.conversion(), profile.local()));
        final double cap = ward ? at(IRON_WARD_CAP, build.level()) : at(PLAGUE_CAP, build.level());
        final double scale = radiusScale(amount, cap, ward ? IRON_WARD_AREA : PLAGUE_AREA);
        final SkillBuild sized = withDamage.withAreaAndProjectiles(withDamage.areaScale() * scale, withDamage.projectiles());
        values[ward ? 0 : 1] = 0;
        service.procCast(Origin.of(player), sized, null);
        final LivingEntity self = player;
        self.getWorld().spawnParticle(ward ? Particle.CRIT : Particle.ITEM_SLIME, self.getLocation().add(0, 1, 0), 20, 0.6, 0.6, 0.6, 0.05);
        return String.format(Locale.ROOT, "<#8fd3ff>%s：放出 %,d 傷害", ward ? "鐵壁守" : "瘟囊爆", Math.round(amount));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        stored.remove(event.getPlayer().getUniqueId());
    }

    /** 換角色時清掉。 */
    void forget(final UUID player) {
        stored.remove(player);
    }
}
