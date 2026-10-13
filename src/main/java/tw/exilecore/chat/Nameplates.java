package tw.exilecore.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.guild.Guild;
import tw.exilecore.item.ItemRenderer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 頭上的名牌（2026-10）：原版頭上只能顯示玩家 ID，這裡把它藏起來，改用一塊跟著玩家的文字（TextDisplay，每刻跟著玩家走；
 * 不能坐在玩家身上：玩家身上有乘客時 teleport 會失敗，地圖傳送門會扣了次數卻進不去）：
 * <pre>
 *   【稱號】角色名稱            ← 稱號、累積贊助的名字顏色、月卡的小月亮，跟聊天一樣
 *   Lv.96 星紋弩衛              ← 等級、職業（昇華了寫昇華職業）
 *   [星骸] 公會名稱             ← 有公會才有
 * </pre>
 * <ul>
 *   <li>原版名牌用計分板的隊伍藏起來（每個人看的是自己那張計分板：主計分板與側欄的每一張都要有這個隊伍）。</li>
 *   <li>自己看不到自己的（第一人稱抬頭會擋到）；潛行時字變空（跟原版一樣看不到）。</li>
 *   <li>傳送、換世界、重生之後重新坐上去；每秒檢查一次有沒有掉下來，文字有變才重送。</li>
 *   <li>還沒選角色的人不掛名牌（原版名牌也藏著，TAB 看得到玩家 ID）。config.yml 的 nameplates: false 關掉。</li>
 * </ul>
 */
public final class Nameplates implements Listener {

    /** 藏原版名牌的隊伍（每張計分板一個）。 */
    static final String TEAM = "ec_nameplate";
    /** 字飄在玩家腳底上方多高（玩家 1.8 格高，字的中心再高一點）。 */
    private static final float HEIGHT = 2.15f;
    private static final float SNEAK_HEIGHT = 1.85f;
    private static final float SCALE = 0.9f;

    private final ExileCore plugin;
    private final Map<UUID, TextDisplay> plates = new HashMap<>();
    private final Map<UUID, String> shown = new HashMap<>();
    private BukkitTask task;
    private BukkitTask follow;

    public Nameplates(final ExileCore plugin) {
        this.plugin = plugin;
    }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::everySecond, 20, 20);
        follow = plugin.getServer().getScheduler().runTaskTimer(plugin, this::everyTick, 1, 1);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (follow != null) {
            follow.cancel();
            follow = null;
        }
        for (final TextDisplay plate : plates.values()) {
            if (plate.isValid()) {
                plate.remove();
            }
        }
        plates.clear();
        shown.clear();
        // 隊伍留著也沒關係（只是藏名牌），但插件關掉就讓原版名牌回來
        final Team team = plugin.getServer().getScoreboardManager().getMainScoreboard().getTeam(TEAM);
        if (team != null) {
            team.unregister();
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("nameplates", true);
    }

    // ---- 文字 ----

    /** 這個人名牌上的字（MiniMessage）；還沒選角色回傳 null。 */
    String text(final Player player) {
        if (plugin.players() == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state != null && state.isLoaded() ? state.character() : null;
        if (character == null) {
            return null;
        }
        final String title = plugin.bounties() == null ? "" : plugin.bounties().titleTag(player);
        final String name = ItemRenderer.escape(character.name());
        final String moon = plugin.sponsors() != null && plugin.sponsors().cardDaysLeft(player) > 0 ? "<#bcd4ff>☾</#bcd4ff>" : "";
        final String style = plugin.sponsors() == null ? null : plugin.sponsors().nameColor(player);
        final String styledName = style == null ? "<white>" + name + "</white>"
                : "<" + style + ">" + name + "</" + (style.startsWith("gradient:") ? "gradient" : style) + ">";
        final StringBuilder text = new StringBuilder(title + moon + styledName);
        final String ascendancy = plugin.ascendancy() == null ? null : plugin.ascendancy().ascendancyName(state);
        final CharacterClass characterClass = character.characterClass();
        text.append("\n<#f5b942>Lv.").append(character.level()).append("</#f5b942> <gray>")
                .append(ascendancy != null ? ascendancy : characterClass.color() + characterClass.displayName());
        if (plugin.guilds() != null) {
            final Guild guild = plugin.guilds().guildOf(player.getUniqueId());
            if (guild != null) {
                text.append("\n").append(plugin.guilds().tag(player)).append(" <#9fd3ff>").append(ItemRenderer.escape(guild.name()));
            }
        }
        return text.toString();
    }

    /** 名字、稱號、等級、公會變了之後叫這個（PlayerStateService.updateListName 會叫）。 */
    public void refresh(final Player player) {
        if (!enabled() || !player.isOnline()) {
            remove(player.getUniqueId());
            return;
        }
        hideVanillaFor(player);
        final String text = text(player);
        if (text == null || player.isDead()) {
            remove(player.getUniqueId());
            return;
        }
        TextDisplay plate = plates.get(player.getUniqueId());
        if (plate == null || !plate.isValid() || plate.getWorld() != player.getWorld()) {
            if (plate != null && plate.isValid()) {
                plate.remove();
            }
            plate = spawn(player);
            plates.put(player.getUniqueId(), plate);
            shown.remove(player.getUniqueId());
        }
        if (player.canSee(plate)) {
            player.hideEntity(plugin, plate); // 2026-10：自己的名牌有時會又看得到（騎馬後退時）：每秒補藏一次
        }
        final String want = player.isSneaking() ? "" : text;
        if (!want.equals(shown.get(player.getUniqueId()))) {
            plate.text(want.isEmpty() ? Component.empty() : plugin.text().parse(want));
            plate.setBackgroundColor(want.isEmpty() ? Color.fromARGB(0, 0, 0, 0) : Color.fromARGB(0x50, 0, 0, 0));
            shown.put(player.getUniqueId(), want);
        }
    }

    private TextDisplay spawn(final Player player) {
        final TextDisplay plate = player.getWorld().spawn(player.getLocation(), TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0x50, 0, 0, 0));
            display.setLineWidth(240);
            display.setPersistent(false);
            display.setViewRange(0.75f);
            display.setTeleportDuration(1); // 每刻跟著走，客戶端做一刻的平滑移動
            display.setTransformation(new org.bukkit.util.Transformation(new Vector3f(0, HEIGHT, 0), new AxisAngle4f(),
                    new Vector3f(SCALE, SCALE, SCALE), new AxisAngle4f()));
        });
        player.hideEntity(plugin, plate); // 自己看不到自己的
        return plate;
    }

    private void remove(final UUID id) {
        final TextDisplay plate = plates.remove(id);
        shown.remove(id);
        if (plate != null && plate.isValid()) {
            plate.remove();
        }
    }

    /** 每刻：名牌跟著玩家走（不坐在玩家身上，見類別說明）。 */
    private void everyTick() {
        for (final Map.Entry<UUID, TextDisplay> entry : plates.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            final TextDisplay plate = entry.getValue();
            if (player == null || !plate.isValid() || plate.getWorld() != player.getWorld()) {
                continue;
            }
            if (player.isInsideVehicle() && player.canSee(plate)) {
                player.hideEntity(plugin, plate); // 2026-10：騎著坐騎時追蹤會重送實體，自己的名牌會露出來：每刻補藏
            }
            final org.bukkit.Location at = player.getLocation();
            at.setPitch(0);
            at.setYaw(0);
            final org.bukkit.Location now = plate.getLocation();
            if (now.getX() != at.getX() || now.getY() != at.getY() || now.getZ() != at.getZ()) {
                plate.teleport(at);
            }
        }
    }

    /** 每秒：文字有變就更新（公會改名、升級這種沒經過 updateListName 的也補得到）。 */
    private void everySecond() {
        if (!enabled()) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                refresh(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("[名牌] {}：{}", player.getName(), e.toString());
            }
        }
        plates.keySet().removeIf(id -> {
            if (plugin.getServer().getPlayer(id) == null) {
                final TextDisplay plate = plates.get(id);
                if (plate != null && plate.isValid()) {
                    plate.remove();
                }
                shown.remove(id);
                return true;
            }
            return false;
        });
    }

    // ---- 藏原版名牌 ----

    /**
     * 每個人看到的名牌照「他自己那張計分板」的隊伍設定，所以這個人現在用的那張（主計分板或側欄的）要有藏名牌的隊伍，
     * 而且所有在線的人都要在隊伍裡。
     */
    private void hideVanillaFor(final Player viewer) {
        final Scoreboard board = viewer.getScoreboard();
        Team team = board.getTeam(TEAM);
        if (team == null) {
            team = board.registerNewTeam(TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS);
            team.setCanSeeFriendlyInvisibles(false);
        }
        final Set<String> names = new HashSet<>();
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            names.add(online.getName());
            if (!team.hasEntry(online.getName())) {
                team.addEntry(online.getName());
            }
        }
        for (final String entry : Set.copyOf(team.getEntries())) {
            if (!names.contains(entry)) {
                team.removeEntry(entry);
            }
        }
    }

    // ---- 事件 ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                refresh(event.getPlayer());
                // 別人的計分板也要把新來的人加進隊伍，才藏得住他的原版名牌
                for (final Player other : plugin.getServer().getOnlinePlayers()) {
                    hideVanillaFor(other);
                }
            }
        }, 5);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(final PlayerChangedWorldEvent event) {
        remove(event.getPlayer().getUniqueId());
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(final PlayerToggleSneakEvent event) {
        later(event.getPlayer());
    }

    private void later(final Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                refresh(player);
            }
        }, 2);
    }

    /** 這個實體是不是誰的名牌（別的模組清實體時用）。 */
    public boolean isPlate(final Entity entity) {
        return entity instanceof TextDisplay && plates.containsValue(entity);
    }
}
