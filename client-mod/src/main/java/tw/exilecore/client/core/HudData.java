package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 伺服器送來的畫面資料（exilecore:hud，格式見插件的 HudSnapshot）。純 Java（測試用）。
 * 時間都是「送出時還剩幾 tick」；{@link #elapsed} 是收到之後過了幾 tick（畫的時候自己倒數，不用伺服器每 tick 送）。
 */
public record HudData(long serverTick, List<Slot> slots, List<Buff> buffs, Target target, Vitals vitals, Self self, Resources res,
                      Sides sides) {

    public HudData(final long serverTick, final List<Slot> slots, final List<Buff> buffs, final Target target, final Vitals vitals) {
        this(serverTick, slots, buffs, target, vitals, Self.NONE, Resources.NONE, Sides.NONE);
    }

    /**
     * 2026-11-02（1.4，插件 vit 的 st、gd、sf、lv、xp、xpn）：自己身上的異常、詛咒（status）、守護、入場保護、等級與經驗。
     * 舊插件沒有這些：status 空的、level 0。
     */
    public record Self(List<Part> status, long guard, boolean safe, int level, long xp, long xpNeeded) {
        public static final Self NONE = new Self(List.of(), 0, false, 0, 0, 0);

        /** 這一級的進度（0～1；滿級 1）。 */
        public double progress() {
            return xpNeeded <= 0 ? 1 : Math.max(0, Math.min(1, xp / (double) xpNeeded));
        }
    }

    /** 2026-11-02（1.4）：一種充能。 */
    public record Charge(String name, String symbol, int color, int count, int max) {
    }

    /** 2026-11-02（1.4，插件的 res）：怒氣、充能、其他資源（連擊、灌注、熱度、三相…，一段一段用兩個空白隔開）。 */
    public record Resources(int rage, int rageMax, boolean decaying, List<Charge> charges, List<Part> other) {
        public static final Resources NONE = new Resources(0, 1, false, List.of(), List.of());

        public boolean isEmpty() {
            return rage <= 0 && charges.isEmpty() && other.isEmpty();
        }
    }

    /** 2026-11-02（1.4）：一種召喚物（limit 0 = 沒有上限）。 */
    public record Pet(String name, int count, int limit) {
    }

    /** 2026-11-02（1.4）：剛撿到的一樣東西；left = 送出時還剩幾 tick。 */
    public record Loot(String name, int count, int color, long left, long total) {
        public long left(final long elapsed) {
            return Math.max(0, left - elapsed);
        }
    }

    /** 2026-11-02（1.4，插件的 auras、pets、loot）：以前接在動作列後面、球外側的那些字。 */
    public record Sides(List<String> auras, List<Pet> pets, List<Loot> loot) {
        public static final Sides NONE = new Sides(List.of(), List.of(), List.of());
    }

    /** gloryNow／gloryNeed：2026-11-02（1.4，插件的 gl、gn）要榮耀的技能現在有幾點、要幾點；不用榮耀的 gloryNeed = 0。 */
    public record Slot(int slot, String skillId, String name, String item, String model, boolean bound, long cooldown,
                       long cooldownTotal, int usesLeft, int maxUses, int manaCost, String problem, int gloryNow, int gloryNeed) {

        public Slot(final int slot, final String skillId, final String name, final String item, final String model, final boolean bound,
                    final long cooldown, final long cooldownTotal, final int usesLeft, final int maxUses, final int manaCost,
                    final String problem) {
            this(slot, skillId, name, item, model, bound, cooldown, cooldownTotal, usesLeft, maxUses, manaCost, problem, 0, 0);
        }

        /** 榮耀夠了沒。 */
        public boolean gloryReady() {
            return gloryNeed > 0 && gloryNow >= gloryNeed;
        }

        /** 榮耀的比例（0～1）。 */
        public double gloryFraction() {
            return gloryNeed <= 0 ? 0 : Math.max(0, Math.min(1, gloryNow / (double) gloryNeed));
        }

        /** 過了 elapsed tick 之後冷卻還剩幾 tick。 */
        public long cooldownLeft(final long elapsed) {
            return Math.max(0, cooldown - elapsed);
        }

        /** 冷卻還剩的比例（1 = 剛開始、0 = 好了）。 */
        public double cooldownFraction(final long elapsed) {
            final long left = cooldownLeft(elapsed);
            return left <= 0 || cooldownTotal <= 0 ? 0 : Math.min(1, left / (double) cooldownTotal);
        }

        /** 現在能不能放（過了 elapsed tick 之後；冷卻好了「冷卻」這個原因就不算）。 */
        public boolean castable(final long elapsed) {
            if (problem == null || problem.isEmpty()) {
                return true;
            }
            return problem.equals("冷卻") && cooldownLeft(elapsed) <= 0;
        }

        /** 可以存好幾次的技能，過了 elapsed tick 之後大概有幾次（冷卻好了算回一次）。 */
        public int uses(final long elapsed) {
            if (maxUses <= 1) {
                return 0;
            }
            return Math.min(maxUses, usesLeft + (cooldown > 0 && cooldownLeft(elapsed) <= 0 ? 1 : 0));
        }
    }

    /** important：2026-11-02（1.4，插件的 i）護符、神龕這種重要的增益（增益列排前面）。 */
    public record Buff(String name, long left, long total, int stacks, int slot, boolean important) {

        public Buff(final String name, final long left, final long total, final int stacks, final int slot) {
            this(name, left, total, stacks, slot, false);
        }

        public long left(final long elapsed) {
            return Math.max(0, left - elapsed);
        }

        public double fraction(final long elapsed) {
            return total <= 0 ? 0 : Math.min(1, left(elapsed) / (double) total);
        }
    }

    public record Part(String text, int color) {
    }

    /** invulnerable：2026-11-02（1.4，插件的 inv）打不動（無敵、庇護、開場）；舊插件沒有 = false。 */
    public record Target(String name, int color, String rarity, int level, long life, long maxLife, int shieldPercent, boolean boss,
                         List<Part> status, boolean invulnerable) {

        public Target(final String name, final int color, final String rarity, final int level, final long life, final long maxLife,
                      final int shieldPercent, final boolean boss, final List<Part> status) {
            this(name, color, rarity, level, life, maxLife, shieldPercent, boss, status, false);
        }

        public double lifeFraction() {
            return maxLife <= 0 ? 0 : Math.max(0, Math.min(1, life / (double) maxLife));
        }
    }

    /** reservedMana：保留的魔力（2026-11-02，插件 rmp；舊插件沒有 = 0）。 */
    public record Vitals(long life, long maxLife, long reservedLife, long energyShield, long maxEnergyShield, long mana, long maxMana,
                         double manaShare, long reservedMana) {

        public Vitals(final long life, final long maxLife, final long reservedLife, final long energyShield, final long maxEnergyShield,
                      final long mana, final long maxMana, final double manaShare) {
            this(life, maxLife, reservedLife, energyShield, maxEnergyShield, mana, maxMana, manaShare, 0);
        }

        /** 2026-11-02：混沌免疫（最大生命 1、有能量護盾）：生命球整顆畫能量護盾。 */
        public boolean chaosInoculation() {
            return maxLife <= 1 && maxEnergyShield > 0;
        }
    }

    /** 讀伺服器送來的 JSON；格式不對回傳 null。 */
    public static HudData parse(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            final List<Slot> slots = new ArrayList<>();
            for (final JsonElement element : array(root, "slots")) {
                final JsonObject o = element.getAsJsonObject();
                slots.add(new Slot(i(o, "s", 0), s(o, "id"), s(o, "n"), s(o, "i"), o.has("m") ? s(o, "m") : null, i(o, "b", 1) != 0,
                        l(o, "cd"), l(o, "ct"), i(o, "u", 0), i(o, "mu", 1), i(o, "mc", 0), o.has("p") ? s(o, "p") : null,
                        i(o, "gl", 0), i(o, "gn", 0)));
            }
            final List<Buff> buffs = new ArrayList<>();
            for (final JsonElement element : array(root, "buffs")) {
                final JsonObject o = element.getAsJsonObject();
                buffs.add(new Buff(s(o, "n"), l(o, "l"), l(o, "t"), i(o, "k", 0), i(o, "s", -1), i(o, "i", 0) != 0));
            }
            Target target = null;
            if (root.has("target") && root.get("target").isJsonObject()) {
                final JsonObject o = root.getAsJsonObject("target");
                final List<Part> parts = parts(array(o, "st"));
                target = new Target(s(o, "n"), i(o, "c", 0xFFFFFF), s(o, "r"), i(o, "lv", 0), l(o, "hp"), l(o, "mhp"), i(o, "sh", -1),
                        i(o, "boss", 0) != 0, parts, i(o, "inv", 0) != 0);
            }
            Vitals vitals = null;
            Self self = Self.NONE;
            if (root.has("vit") && root.get("vit").isJsonObject()) {
                final JsonObject o = root.getAsJsonObject("vit");
                vitals = new Vitals(l(o, "hp"), l(o, "mhp"), l(o, "rl"), l(o, "es"), l(o, "mes"), l(o, "mp"), l(o, "mmp"),
                        o.has("ms") ? o.get("ms").getAsDouble() : 0, l(o, "rmp"));
                self = new Self(parts(array(o, "st")), l(o, "gd"), i(o, "sf", 0) != 0, i(o, "lv", 0), l(o, "xp"), l(o, "xpn"));
            }
            Resources res = Resources.NONE;
            if (root.has("res") && root.get("res").isJsonObject()) {
                final JsonObject o = root.getAsJsonObject("res");
                final List<Charge> charges = new ArrayList<>();
                for (final JsonElement element : array(o, "ch")) {
                    final JsonObject c = element.getAsJsonObject();
                    charges.add(new Charge(s(c, "n"), s(c, "s"), i(c, "c", 0xFFFFFF), i(c, "k", 0), i(c, "m", 0)));
                }
                res = new Resources(i(o, "rg", 0), Math.max(1, i(o, "rgm", 1)), i(o, "rgd", 0) != 0, charges, parts(array(o, "o")));
            }
            final List<String> auras = new ArrayList<>();
            for (final JsonElement element : array(root, "auras")) {
                auras.add(element.getAsString());
            }
            final List<Pet> pets = new ArrayList<>();
            for (final JsonElement element : array(root, "pets")) {
                final JsonObject p = element.getAsJsonObject();
                pets.add(new Pet(s(p, "n"), i(p, "k", 0), i(p, "m", 0)));
            }
            final List<Loot> loot = new ArrayList<>();
            for (final JsonElement element : array(root, "loot")) {
                final JsonObject p = element.getAsJsonObject();
                loot.add(new Loot(s(p, "n"), i(p, "k", 1), i(p, "c", 0xC8C8C8), l(p, "l"), Math.max(1, l(p, "t"))));
            }
            return new HudData(l(root, "t"), slots, buffs, target, vitals, self, res, new Sides(auras, pets, loot));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 秒數的文字：10 秒以上整數（「12」），以下一位小數（「3.4」）。 */
    public static String seconds(final long ticks) {
        if (ticks >= 200) {
            return Long.toString((ticks + 19) / 20);
        }
        final long tenths = (ticks + 1) / 2;
        return tenths / 10 + "." + tenths % 10;
    }

    /** 增益剩的時間：一分鐘以上寫「2m」，以下照 {@link #seconds}。 */
    public static String duration(final long ticks) {
        return ticks >= 20 * 60 ? (ticks + 20 * 60 - 1) / (20 * 60) + "m" : seconds(ticks) + "s";
    }

    /** 2026-11-02：一串字段 {@code [{"t":字,"c":顏色}]}（目標框、自己的異常、資源、提示、面板共用）。 */
    public static List<Part> parts(final JsonArray array) {
        final List<Part> parts = new ArrayList<>();
        for (final JsonElement element : array) {
            if (element.isJsonObject()) {
                final JsonObject p = element.getAsJsonObject();
                parts.add(new Part(s(p, "t"), i(p, "c", 0xFFFFFF)));
            }
        }
        return parts;
    }

    /**
     * 2026-11-02：照兩個以上的空白把字段切成一段一段（插件用兩個空白隔開異常、資源）；每段前後的空白拿掉、空的段不要。
     */
    public static List<List<Part>> segments(final List<Part> parts) {
        final List<List<Part>> out = new ArrayList<>();
        List<Part> current = new ArrayList<>();
        for (final Part part : parts) {
            final String[] pieces = part.text().split(" {2,}", -1);
            for (int k = 0; k < pieces.length; k++) {
                if (k > 0) {
                    flushSegment(out, current);
                    current = new ArrayList<>();
                }
                if (!pieces[k].isEmpty()) {
                    current.add(new Part(pieces[k], part.color()));
                }
            }
        }
        flushSegment(out, current);
        return out;
    }

    private static void flushSegment(final List<List<Part>> out, final List<Part> segment) {
        final List<Part> trimmed = new ArrayList<>();
        for (final Part part : segment) {
            trimmed.add(part);
        }
        while (!trimmed.isEmpty() && trimmed.getFirst().text().isBlank()) {
            trimmed.removeFirst();
        }
        while (!trimmed.isEmpty() && trimmed.getLast().text().isBlank()) {
            trimmed.removeLast();
        }
        if (trimmed.isEmpty()) {
            return;
        }
        final Part first = trimmed.getFirst();
        trimmed.set(0, new Part(first.text().stripLeading(), first.color()));
        final Part last = trimmed.getLast();
        trimmed.set(trimmed.size() - 1, new Part(last.text().stripTrailing(), last.color()));
        out.add(trimmed);
    }

    private static JsonArray array(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String s(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long l(final JsonObject o, final String key) {
        return o.has(key) ? o.get(key).getAsLong() : 0;
    }

    private static int i(final JsonObject o, final String key, final int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }
}
