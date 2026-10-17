package tw.exilecore.monster;

import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.party.PartyConfig;
import tw.exilecore.party.PartyService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 怪物死亡時誰有份：
 * <ul>
 *   <li>掉落：對這隻怪的傷害佔玩家總傷害 5% 以上的人（party.yml 的 loot）各自擲一份；
 *       Boss 再加上跟他們同隊的人（不用打到傷害，2026-10）；Boss 的掉落一律要在 Boss 旁邊 32 格內、活著、不是觀戰。
 *       沒有任何傷害紀錄時（例如被其他插件殺死）才給最後一擊的人。</li>
 *   <li>經驗：算給傷害最多的隊伍（沒隊伍的人自己一隊），附近的隊員照等級分（PoE 的做法）。
 *       怪物因為人多而變硬多少（{@link MonsterService#playersAround}），總經驗就多多少，隊伍每多一人再加一點。</li>
 * </ul>
 * 都要在線、活著、在同一個世界、在附近（在地圖裡：同一張地圖都算）。
 */
public final class KillCredit {

    private static final PartyConfig DEFAULTS = new PartyConfig();

    private final ExileCore plugin;
    private final MonsterService monsters;

    public KillCredit(final ExileCore plugin, final MonsterService monsters) {
        this.plugin = plugin;
        this.monsters = monsters;
    }

    private PartyConfig config() {
        final PartyService party = plugin.party();
        return party == null ? DEFAULTS : party.config();
    }

    /** 掉落有份的玩家（傷害高的在前，最多 max_players 份）；沒有任何傷害紀錄時是最後一擊的人。都要還在附近。 */
    public List<Player> sharers(final LivingEntity entity, final Player killer) {
        final PartyConfig config = config();
        final Map<UUID, Double> damage = monsters.ledger().damageTo(entity.getUniqueId());
        final Set<UUID> ids = new LinkedHashSet<>(DamageLedger.qualifying(damage, config.shareThreshold()));
        if (ids.isEmpty() && killer != null) {
            ids.add(killer.getUniqueId());
        }
        // 2026-10：小怪要自己打到傷害門檻；Boss（區域、地圖、終局…）同隊在附近的人不用打到傷害也有份
        final PartyService party = plugin.party();
        final boolean boss = monsters.data(entity).map(MonsterData::boss).orElse(false);
        if (party != null && boss) {
            for (final UUID id : new ArrayList<>(ids)) {
                ids.addAll(party.groupMembers(id));
            }
        }
        final List<Player> result = new ArrayList<>();
        for (final UUID id : ids) {
            final Player player = plugin.getServer().getPlayer(id);
            // Boss：一律看 Boss 旁邊 32 格（地圖裡也是；死掉、觀戰的不算）；小怪照 share_radius（地圖裡整張地圖都算）
            final boolean counts = boss ? atBoss(player, entity) && lateEntryOk(entity, player) : player != null && near(player, entity, config.shareRadius());
            if (counts) {
                result.add(player);
                if (result.size() >= config.maxCountedPlayers()) {
                    break; // 最多幾份（跟怪物生命最多算幾人一樣）：傷害最高的那幾個人
                }
            }
        }
        return result;
    }

    /** 終局以上的 Boss（2026-10-15）：Boss 生命掉到 75% 以下才進來的人沒有掉落（隊友也一樣）。 */
    private boolean lateEntryOk(final LivingEntity entity, final Player player) {
        return plugin.maps() == null || plugin.maps().bossLootEligible(entity, player);
    }

    /** 給經驗（照上面的規則）。 */
    public void awardExperience(final LivingEntity entity, final MonsterData data, final Player killer) {
        final PartyConfig config = config();
        final PartyService party = plugin.party();
        final Function<UUID, UUID> groupOf = party == null ? id -> id : party::groupOf;
        final Map<UUID, Double> damage = monsters.ledger().damageTo(entity.getUniqueId());
        final UUID group = DamageLedger.topGroup(damage, groupOf);
        UUID anchor = null;
        if (group != null) {
            for (final UUID id : damage.keySet()) {
                if (group.equals(groupOf.apply(id))) {
                    anchor = id;
                    break;
                }
            }
        }
        if (anchor == null) {
            if (killer == null) {
                return; // 沒有玩家打過（例如摔死、被別的怪打死）
            }
            anchor = killer.getUniqueId();
        }
        final List<UUID> members = party == null ? List.of(anchor) : party.groupMembers(anchor);
        final List<Player> recipients = new ArrayList<>();
        for (final UUID id : members) {
            final Player player = plugin.getServer().getPlayer(id);
            final boolean counts = data.boss() ? atBoss(player, entity) : player != null && near(player, entity, config.experienceRadius());
            if (counts && plugin.players().get(player).hasCharacter()) {
                recipients.add(player);
            }
        }
        if (recipients.isEmpty() && killer != null
                && (data.boss() ? atBoss(killer, entity) : near(killer, entity, config.experienceRadius()))
                && plugin.players().get(killer).hasCharacter()) {
            recipients.add(killer); // 傷害最多的隊伍都跑遠了：給最後一擊的人
        }
        if (recipients.isEmpty()) {
            return;
        }
        final Map<UUID, Integer> levels = new LinkedHashMap<>();
        for (final Player player : recipients) {
            levels.put(player.getUniqueId(), plugin.players().get(player).level());
        }
        final Map<UUID, Double> shares = PartyConfig.experienceShares(levels, config.levelWeightExponent());
        // 地圖怪：星圖天賦的「地圖裡的怪給的經驗 +x%」；全服經驗加倍（character.yml 的 experience_bonus）
        final double atlas = plugin.maps() == null ? 0 : plugin.maps().experienceBonus(entity);
        final double bonus = plugin.players().config().experienceBonus();
        final double factor = config.lifeFactor(data.rarity(), monsters.playersAround(entity)) * config.partyBonus(recipients.size()) * (1 + atlas)
                * bonus;
        final String bonusText = plugin.players().config().experienceBonusText();
        for (final Player player : recipients) {
            final PlayerState state = plugin.players().get(player);
            final double exact = plugin.players().experienceForKillExact(state.level(), data.level(), data.rarity())
                    * factor * shares.getOrDefault(player.getUniqueId(), 0.0);
            // 2026-10：累積贊助的經驗加成（每個人自己的）
            // 個人經驗加倍藥：跟全服加倍加起來（全服 ×2、個人 ×2 → ×3）
            // 2026-10-18：全部加起來：全服（已經乘在 factor 裡）＋累積贊助＋燃星者＋稱號等＋個人加倍。
            // personalExperience 已經除過全服倍數；其他幾項也除掉，結果是「全服 + 各項」而不是乘起來
            final double server = Math.max(1e-9, plugin.players().config().experienceBonus());
            final double sponsor = (plugin.sponsors() == null ? 0 : plugin.sponsors().personalExperience(player))
                    + ((plugin.sponsors() == null ? 0 : plugin.sponsors().experienceBonus(player))
                    + (plugin.ascendancy() == null ? 0 : plugin.ascendancy().race().experienceBonus(player)) // 2026-10：燃星者 +5%
                    + plugin.players().sheet(player).increased(tw.exilecore.stats.StatKeys.EXPERIENCE_GAIN, java.util.Set.of())) / server; // 稱號等
            final long amount = Math.round(exact * (1 + sponsor));
            if (amount <= 0) {
                continue;
            }
            // 升級前的等級算的懲罰（這一下升級的話，debug 寫的是給經驗時用的那個）
            final double penalty = plugin.players().config().levelPenalty(state.level(), data.level());
            plugin.players().awardExperience(player, amount);
            if (data.boss() && !state.debug()) {
                // 2026-10：Boss 的經驗平常看不出來（跟小怪一樣默默加），倒下時寫一行
                player.sendMessage(plugin.text().prefixed(String.format(java.util.Locale.ROOT, "<#ffd27f>擊敗 Boss：經驗 +%,d%s", amount,
                        penalty < 0.995 ? "<dark_gray>（等級差：剩 " + tw.exilecore.character.LevelPace.percent(penalty) + "）" : "")));
            }
            if (state.debug()) {
                player.sendMessage(plugin.text().prefixed(String.format(java.util.Locale.ROOT, "<gray>經驗 +%,d%s%s%s", amount,
                        recipients.size() > 1 ? String.format(java.util.Locale.ROOT, "（%d 人分，你佔 %.0f%%）", recipients.size(),
                                shares.getOrDefault(player.getUniqueId(), 0.0) * 100) : "",
                        penalty < 0.995 ? "<dark_gray>（等級差與高等級：剩 " + tw.exilecore.character.LevelPace.percent(penalty) + "）" : "",
                        bonusText == null ? "" : "<#ffd27f>（全服 " + bonusText + "）")));
            }
        }
    }

    /** 在線、活著、不是旁觀、在同一個世界、在範圍內（在地圖裡：同一張地圖都算）。 */
    private boolean atBoss(final Player player, final LivingEntity entity) {
        if (player == null) {
            return false;
        }
        if (plugin.maps() != null) {
            return plugin.maps().atBoss(player, entity.getLocation());
        }
        final double r = tw.exilecore.map.MapService.BOSS_REWARD_RADIUS;
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && player.getWorld().equals(entity.getWorld()) && player.getLocation().distanceSquared(entity.getLocation()) <= r * r;
    }

    private boolean near(final Player player, final LivingEntity entity, final double radius) {
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (!player.getWorld().equals(entity.getWorld())) {
            return false;
        }
        if (plugin.maps() != null && plugin.maps().sameInstance(entity, player)) {
            return true;
        }
        return player.getLocation().distanceSquared(entity.getLocation()) <= radius * radius;
    }
}
