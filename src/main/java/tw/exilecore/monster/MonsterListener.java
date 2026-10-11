package tw.exilecore.monster;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.List;
import java.util.Optional;

/**
 * 原版敵對生物生成時等級化，死亡時走我們的掉落表。
 */
public final class MonsterListener implements Listener {

    private final ExileCore plugin;
    private final MonsterService monsters;
    private final DropTable drops;

    public MonsterListener(final ExileCore plugin, final MonsterService monsters, final DropTable drops) {
        this.plugin = plugin;
        this.monsters = monsters;
        this.drops = drops;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Enemy)) {
            return;
        }
        // CUSTOM 是插件自己生成的（例如 /ec spawn），由呼叫端自己決定等級
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (monsters.isMonster(entity)) {
            return;
        }
        monsters.decorateNatural(entity, monsters.levelFor(entity.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTransform(final EntityTransformEvent event) {
        // 我們的怪物不變身（殭屍泡水變沉屍、骷髏凍成流髑、豬布林殭屍化…），不然等級、稀有度、詞綴都會掉
        if (event.getEntity() instanceof LivingEntity living && monsters.isMonster(living)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(final EntityCombustEvent event) {
        // 白天曬太陽著火是這個事件本身（被方塊、實體點燃的是子類別）：我們的怪物不會被太陽燒死
        if (event.getClass() == EntityCombustEvent.class && event.getEntity() instanceof LivingEntity living
                && monsters.isMonster(living)) {
            event.setCancelled(true);
        }
    }

    /**
     * 載入的怪物（區塊重載、伺服器重開）把原版的受傷無敵時間再關一次：這個值不會存檔，
     * 不關的話 0.5 秒內的第二下會被原版吞掉（連擊、多段技能、打假人只算一下）。
     */
    @EventHandler
    public void onAdd(final EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof LivingEntity living && !(living instanceof Player) && monsters.isMonster(living)) {
            living.setMaximumNoDamageTicks(0);
        }
    }

    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        // 消失、卸載的怪物也要清掉快取，不然屬性表會一直累積；受到的傷害紀錄也一起清
        monsters.forget(event.getEntity().getUniqueId());
        monsters.ledger().forget(event.getEntity().getUniqueId());
        if (plugin.combat() != null) {
            plugin.combat().debuffs().forget(event.getEntity().getUniqueId()); // 印記
        }
    }

    /**
     * 怪物死亡：原版掉落與經驗關掉，改成
     * <ul>
     *   <li>經驗：傷害最多的隊伍、附近的隊員照等級分（{@link KillCredit#awardExperience}）；</li>
     *   <li>掉落：傷害 5% 以上的每個人各自擲一份（自己的物品數量、稀有度、星圖），直接進自己的背包；</li>
     *   <li>地圖：殺怪數、地圖 Boss 的完成（一次）。</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final Optional<MonsterData> dataOptional = monsters.data(entity);
        if (dataOptional.isEmpty()) {
            return;
        }
        final MonsterData data = dataOptional.get();
        // 傳奇的擊殺效果（回復、爆炸、疾速）：在清掉屬性表之前拿怪物的最大生命；召喚出來的小怪也算
        final Player killer = entity.getKiller();
        if (plugin.combat() != null) {
            final double maxLife = monsters.sheet(entity, data).get(StatKeys.LIFE_MAX);
            if (killer != null && plugin.combat().signatures() != null) {
                plugin.combat().signatures().killed(killer); // 昇華的招牌天賦：獻祭
            }
            if (killer != null && plugin.combat().killEffects() != null) {
                plugin.combat().killEffects().onKill(killer, entity, maxLife);
            }
            if (killer != null) {
                plugin.players().get(killer).markRecent(tw.exilecore.stats.Recently.KILL, plugin.getServer().getCurrentTick()); // 「最近擊殺過」
            }
            // 屍體（骸爆用）：召喚出來的小怪也算
            final org.bukkit.Location at = entity.getLocation();
            plugin.combat().corpses().add(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), maxLife, plugin.getServer().getCurrentTick(),
                    data.boss() ? null : entity.getType().name());
        }
        monsters.forget(entity.getUniqueId());

        // 原版掉落與經驗全部關掉，只走我們的掉落表
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (monsters.isSummoned(entity)) {
            return; // 召喚出來的小怪不掉東西、不給經驗（不然召喚者就是提款機）
        }

        if (data.boss()) {
            // Boss 倒下：傷害排行（打過的人各收到一份，最後一行是自己的）
            BossDamageReport.send(plugin, entity, data, monsters.ledger().damageTo(entity.getUniqueId()));
        }
        final KillCredit credit = plugin.monsterModule().credit();
        credit.awardExperience(entity, data, killer);

        double quantityBonus = monsters.config().rarity(data.rarity()).quantityBonus();
        double rarityBonus = monsters.config().rarity(data.rarity()).rarityBonus();
        if (plugin.maps() != null) {
            // 地圖的物品數量與稀有度（地圖詞綴、品質）
            final double[] bonus = plugin.maps().dropBonus(entity);
            quantityBonus += bonus[0];
            rarityBonus += bonus[1];
        }
        final Location location = entity.getLocation();
        final List<Player> sharers = credit.sharers(entity, killer);
        if (sharers.isEmpty()) {
            // 沒有玩家有份（摔死、被別的怪打死）：擲一份掉在地上，誰都能撿
            rollAndDeliver(entity, data, null, quantityBonus, rarityBonus, location);
        } else {
            for (final Player player : sharers) {
                final StatSheet sheet = plugin.players().sheet(player);
                rollAndDeliver(entity, data, player, quantityBonus + sheet.increased(StatKeys.DROP_QUANTITY, java.util.Set.of()),
                        rarityBonus + sheet.increased(StatKeys.DROP_RARITY, java.util.Set.of()), location);
                if (plugin.quests() != null) {
                    plugin.quests().onKill(player, location, data.rarity()); // 任務：擊殺、撿任務物品
                }
                if (plugin.bounties() != null) {
                    plugin.bounties().onKill(player, data.level(), data.rarity(), data.boss()); // 每日委託：擊殺、撿東西、打 Boss
                }
                if (plugin.events() != null) {
                    plugin.events().onKill(player, data); // 每月挑戰：擊殺、稀有、Boss、主題擊殺
                }
                if (plugin.guilds() != null) {
                    plugin.guilds().onKill(player, data); // 公會經驗（2026-10）
                }
            }
        }
        if (plugin.maps() != null) {
            // 殺怪數；地圖 Boss：完成（星圖、首殺）——每個人的那份已經擲完了
            plugin.maps().onMonsterDeath(entity);
        }
    }

    /** 替一個人（null = 沒有人）擲一份掉落並交給他：過濾器、自動撿取、金幣直接進角色；不能撿的掉在地上、先歸他。 */
    private void rollAndDeliver(final LivingEntity entity, final MonsterData data, final Player owner, final double baseQuantity,
                                final double rarityBonus, final Location location) {
        // 全服掉落加成（管理網頁、drops.yml 的 server_bonus）：數量加在物品上（地圖也算），金幣另外乘金幣倍數（rollGold 裡）
        final double server = drops.serverQuantity();
        double quantityBonus = baseQuantity + server;
        double goldBonus = baseQuantity;
        double fatigue = 1.0;
        if (owner != null && plugin.zones() != null) {
            // 防刷：同一個區域刷太久（角色等級已經到這個區域的最高等級），件數與金幣慢慢變少；地圖裡不算
            final double factor = plugin.zones().lootFactor(owner, location, data.rarity() == MonsterRarity.UNIQUE);
            fatigue = Math.max(0, Math.min(1, factor));
            if (factor < 1) {
                quantityBonus = Math.max(0, 1 + baseQuantity + server) * factor - 1;
                goldBonus = Math.max(0, 1 + baseQuantity) * factor - 1;
            }
        }
        // 2026-10：裝備基底照這個人的職業調整（傳奇不吃）
        final tw.exilecore.item.LootBias bias = owner == null || plugin.characters() == null ? tw.exilecore.item.LootBias.NONE
                : plugin.characters().lootBias(owner);
        final List<DropTable.Drop> rolled = new java.util.ArrayList<>(drops.roll(data.level(), data.rarity(), quantityBonus,
                rarityBonus + drops.serverRarity(), plugin.items().generator(), plugin.currencies().repository(), plugin.items().random(), bias));
        if (plugin.maps() != null) {
            // 地圖怪掉地圖（照這個人的星圖）、地圖 Boss 的地圖、傳奇、天穹碎片
            plugin.maps().rollLoot(entity, data, owner, quantityBonus, rolled);
        }
        rollMirror(owner, rolled, fatigue);
        final long goldAmount = drops.rollGold(data.level(), data.rarity(), goldBonus, plugin.items().random());
        if (owner != null && plugin.loot() != null
                && plugin.loot().handleKill(owner, location, data, rolled, goldAmount, this::toStack)) {
            return;
        }
        if (goldAmount > 0) {
            plugin.gold().drop(location, goldAmount, owner); // 流水帳在撿起來的時候記
        }
        for (final DropTable.Drop drop : rolled) {
            final ItemStack stack = toStack(drop);
            if (stack == null) {
                continue; // 技能或藥劑模組沒啟用
            }
            if (drop.isCurrency()) {
                plugin.economy().ledger().produced(drop.currency().id(), tw.exilecore.economy.EconomyLedger.DROP, stack.getAmount());
            } else if ((drop.kind() == DropTable.Kind.SKILL_GEM || drop.kind() == DropTable.Kind.SUPPORT_GEM) && !drop.isSpecificGem()
                    && plugin.currencies() != null) {
                // 未切割寶石算通貨（2026-10）：掉在地上的也記產出（跟自動撿的一樣）
                plugin.currencies().read(stack).ifPresent(type ->
                        plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.DROP, stack.getAmount()));
            }
            dropOwned(plugin, location, stack, owner, highlight(drop));
        }
    }

    /**
     * 映星鏡（2026-10，複製裝備的通貨）：全域掉落——任何地方的任何怪物（不看等級、稀有度），每個有份的人各擲一次，
     * 機率是 maps.yml 的 drops.mirror_mob_chance（2026-10：0.00001%，追逐級傳奇的十分之一）；不吃物品數量、稀有度、全服加成。
     * 地圖 Boss 另外照 drops.mirror_chance 再擲一次（MapBosses.rollChase）。區域刷太久的衰減照算；沒有人有份的不擲。
     */
    private void rollMirror(final Player owner, final List<DropTable.Drop> rolled, final double fatigue) {
        if (owner == null || plugin.currencies() == null) {
            return;
        }
        if (plugin.items().random().nextDouble() >= tw.exilecore.item.ItemGenerator.mirrorChance() * fatigue) {
            return;
        }
        final tw.exilecore.currency.CurrencyType mirror = plugin.currencies().mirrorType();
        if (mirror != null) {
            rolled.add(DropTable.Drop.currency(mirror));
        }
    }

    /** 地上的標籤：普通裝備以外的東西都顯示名稱（地圖一律顯示）。 */
    public static boolean highlight(final DropTable.Drop drop) {
        return !drop.isEquipment() || drop.item().rarity() != Rarity.NORMAL || drop.item().base().startsWith(MAP_BASE_PREFIX);
    }

    /** 地圖基底代號的前綴（bases/maps.yml）；標籤判斷用，免得這裡要查物品資料。 */
    private static final String MAP_BASE_PREFIX = "map_";

    /**
     * 掉在地上。owner 不是 null 時是他的個人掉落：60 秒內只有他看得到、撿得到，之後誰都能撿（null = 誰都能撿）。
     * highlight 時用物品自己的名稱（已帶稀有度顏色）當地上的標籤。
     */
    public static Item dropOwned(final ExileCore plugin, final Location location, final ItemStack stack, final Player owner,
                                 final boolean highlight) {
        final PersonalDrops personal = plugin.personalDrops();
        final Item dropped = location.getWorld().dropItemNaturally(location, stack, item -> {
            item.setPickupDelay(10);
            if (owner != null) {
                personal.prepare(item, owner); // 別人看不到你的那份
            }
        });
        if (highlight) {
            stack.editMeta(meta -> dropped.customName(meta.displayName()));
            dropped.setCustomNameVisible(true);
        }
        if (owner != null) {
            personal.spawned(dropped, owner);
        }
        return dropped;
    }

    /** 掉落做成物品；寶石與藥劑交給各自的模組（沒啟用回傳 null）。 */
    ItemStack toStack(final DropTable.Drop drop) {
        return toStack(plugin, drop);
    }

    /** 同上（地圖事件的寶箱、流寇也用）。 */
    public static ItemStack toStack(final ExileCore plugin, final DropTable.Drop drop) {
        return switch (drop.kind()) {
            case CURRENCY -> plugin.currencies().create(drop.currency(), 1);
            case EQUIPMENT -> {
                final ItemData item = drop.item();
                yield plugin.items().toStack(item);
            }
            case SKILL_GEM, SUPPORT_GEM -> plugin.skills() == null ? null
                    : drop.isSpecificGem() && drop.kind() == DropTable.Kind.SKILL_GEM
                    ? plugin.skills().gems().create(tw.exilecore.skill.GemData.skill(new tw.exilecore.character.SkillLoadout.Entry(drop.gem(), drop.level())), 1)
                    : drop.isSpecificGem() ? plugin.skills().gems().createSupport(drop.gem())
                    : plugin.skills().gems().createDrop(drop.kind() == DropTable.Kind.SKILL_GEM, drop.level(), plugin.items().random());
            case FLASK -> plugin.flasks() == null ? null : plugin.flasks().createDrop(drop.level(), plugin.items().random());
        };
    }
}
