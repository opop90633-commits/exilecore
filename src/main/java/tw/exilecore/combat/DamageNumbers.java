package tw.exilecore.combat;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 傷害數字：打中怪物時在它頭上冒出數字（只有打的人看得到），往上飄一下就消失。
 * 顏色看哪一種傷害最多（物理白、火焰橘、冰冷藍、閃電黃、混沌紫），暴擊比較大、加驚嘆號。
 * 被打到時在自己眼前也冒一個（{@link #taken}）：「-123 火焰」，顏色照屬性、後面寫屬性名稱，閃避、格擋寫字；
 * 死亡回顧（/deaths）用同一套名稱。
 * 用文字顯示實體（不存檔、別人看不到）；每個人同時最多 {@link #MAX_PER_VIEWER} 個，打太快時多的不顯示。
 * 選單的「設定」可以分別關掉（打出的、受到的）。
 */
public final class DamageNumbers {

    /** 一個數字停留幾 tick。 */
    private static final int LIFETIME = 16;
    private static final int MAX_PER_VIEWER = 16;

    private final ExileCore plugin;
    private final Map<UUID, Integer> active = new HashMap<>();
    private final Set<TextDisplay> displays = new HashSet<>();

    public DamageNumbers(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private boolean enabled(final Player viewer) {
        return PlayerSettings.enabled(plugin.players().get(viewer), PlayerSettings.DAMAGE_NUMBERS);
    }

    /** 打出的傷害。 */
    public void hit(final Player viewer, final LivingEntity target, final double amount, final boolean crit, final DamagePacket dealt) {
        if (amount < 0.5 || !enabled(viewer)) {
            return;
        }
        final String color = color(dominant(dealt));
        final String text = format(amount) + (crit ? "!" : "");
        spawn(viewer, target, (crit ? "<bold>" : "") + "<" + color + ">" + text, crit ? 1.45f : 1.0f);
    }

    /** 沒打中、被格擋這類的字（灰色、小一點）。 */
    public void word(final Player viewer, final LivingEntity target, final String word) {
        if (!enabled(viewer)) {
            return;
        }
        spawn(viewer, target, "<gray>" + word, 0.8f);
    }

    private boolean takenEnabled(final Player viewer) {
        return PlayerSettings.enabled(plugin.players().get(viewer), PlayerSettings.DAMAGE_TAKEN);
    }

    /**
     * 受到的傷害：眼前冒「-123 火焰」（顏色照傷害最多的那一種；混合的另外寫「物理+火焰」），
     * 大的（超過最大生命一成）比較大。
     *
     * @param amount 扣掉護盾與生命的總量
     * @param dealt  各類型各多少（決定顏色與名稱；null 時只寫數字）
     */
    public void taken(final Player viewer, final double amount, final DamagePacket dealt, final double maxLife) {
        if (amount < 0.5 || !takenEnabled(viewer)) {
            return;
        }
        final DamageType main = dominant(dealt);
        final String label = typeLabel(dealt);
        final boolean big = maxLife > 0 && amount >= maxLife * 0.1;
        spawnInFront(viewer, (big ? "<bold>" : "") + "<" + color(main) + ">-" + format(amount) + (label.isEmpty() ? "" : " <gray>" + label),
                big ? 1.3f : 0.95f);
    }

    /** 自己閃掉、格擋了（灰字）。 */
    public void takenWord(final Player viewer, final String word) {
        if (!takenEnabled(viewer)) {
            return;
        }
        spawnInFront(viewer, "<gray>" + word, 0.8f);
    }

    /** 傷害類型的名稱：單一類型寫它，混合的寫「物理+火焰」（最多兩種，照多到少）。 */
    static String typeLabel(final DamagePacket dealt) {
        if (dealt == null) {
            return "";
        }
        final java.util.List<Map.Entry<DamageType, Double>> parts = new java.util.ArrayList<>();
        for (final Map.Entry<DamageType, Double> entry : dealt.asMap().entrySet()) {
            if (entry.getValue() >= 0.5) {
                parts.add(entry);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        parts.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        final StringBuilder text = new StringBuilder(parts.getFirst().getKey().displayName());
        if (parts.size() > 1 && parts.get(1).getValue() >= parts.getFirst().getValue() * 0.25) {
            text.append('+').append(parts.get(1).getKey().displayName());
        }
        return text.toString();
    }

    /** 在玩家眼前（視線方向 1.6 格、稍微偏下）冒一個字，往上飄。 */
    private void spawnInFront(final Player viewer, final String miniMessage, final float scale) {
        final UUID uuid = viewer.getUniqueId();
        if (active.getOrDefault(uuid, 0) >= MAX_PER_VIEWER) {
            return;
        }
        final RandomGenerator random = plugin.random();
        final Location eye = viewer.getEyeLocation();
        final org.bukkit.util.Vector direction = eye.getDirection().normalize();
        final org.bukkit.util.Vector side = new org.bukkit.util.Vector(-direction.getZ(), 0, direction.getX()).normalize();
        final Location start = eye.clone().add(direction.clone().multiply(1.6)).add(side.multiply((random.nextDouble() - 0.5) * 0.9))
                .add(0, -0.35 + random.nextDouble() * 0.2, 0);
        rise(start, viewer, miniMessage, scale, 0.6);
    }

    /** 在 start 冒一個字，往上飄 climb 格後消失。 */
    private void rise(final Location start, final Player viewer, final String miniMessage, final float scale, final double climb) {
        final UUID uuid = viewer.getUniqueId();
        final Component component = plugin.text().parse(miniMessage);
        final TextDisplay display = start.getWorld().spawn(start, TextDisplay.class, created -> {
            created.setPersistent(false);
            created.setVisibleByDefault(false);
            created.setBillboard(Display.Billboard.CENTER);
            created.setShadowed(true);
            created.setDefaultBackground(false);
            created.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            created.text(component);
            created.setTeleportDuration(LIFETIME - 2);
            if (scale != 1.0f) {
                created.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale),
                        new AxisAngle4f()));
            }
        });
        viewer.showEntity(plugin, display);
        displays.add(display);
        active.merge(uuid, 1, Integer::sum);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) {
                display.teleport(start.clone().add(0, climb, 0));
            }
        }, 2);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            displays.remove(display);
            if (viewer.isOnline()) {
                viewer.hideEntity(plugin, display);
            }
            display.remove();
            active.computeIfPresent(uuid, (key, count) -> count <= 1 ? null : count - 1);
        }, LIFETIME);
    }

    private void spawn(final Player viewer, final LivingEntity target, final String miniMessage, final float scale) {
        final UUID uuid = viewer.getUniqueId();
        if (active.getOrDefault(uuid, 0) >= MAX_PER_VIEWER || !viewer.getWorld().equals(target.getWorld())) {
            return;
        }
        final RandomGenerator random = plugin.random();
        final Location start = target.getLocation().add((random.nextDouble() - 0.5) * 0.8,
                target.getHeight() + 0.2 + random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.8);
        rise(start, viewer, miniMessage, scale, 0.8);
    }

    /** 模組停用：還在飄的數字全部拿掉。 */
    public void clear() {
        for (final TextDisplay display : displays) {
            display.remove();
        }
        displays.clear();
        active.clear();
    }

    static DamageType dominant(final DamagePacket dealt) {
        DamageType best = DamageType.PHYSICAL;
        double most = -1;
        if (dealt != null) {
            for (final Map.Entry<DamageType, Double> entry : dealt.asMap().entrySet()) {
                if (entry.getValue() > most) {
                    most = entry.getValue();
                    best = entry.getKey();
                }
            }
        }
        return best;
    }

    /** 傷害類型的顏色（十六進位，給 MiniMessage 的 {@code <#rrggbb>}）；角色面板、/stats 也用同一套。 */
    public static String color(final DamageType type) {
        return DamageColors.of(type);
    }

    /** 1,234、12.3k、1.2M。 */
    static String format(final double amount) {
        final long value = Math.round(amount);
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%,d", value);
        }
        if (value < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
    }
}
