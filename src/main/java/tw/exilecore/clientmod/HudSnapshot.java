package tw.exilecore.clientmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 2026-11-02：送給星骸輔助模組的畫面資料（S→C exilecore:hud）。純資料、純函式（測試用）。
 * <p>
 * 時間都記成「到哪個伺服器 tick 結束」，{@link #toJson(long)} 才換成「還剩幾 tick」（欄位 t 是送出時的 tick，模組照收到後過了多久自己倒數）；
 * {@link #signature()} 用結束的 tick 組，倒數本身不算變化，只有真的變了（放了技能、增益換了、血量變了）才重送。
 * JSON 欄位（短的名字，封包小）：
 * <pre>
 * {"v":1,"t":伺服器 tick,
 *  "slots":[{"s":按鍵格,"id":技能代號,"n":名稱,"i":原版物品,"m":custom_model_data 字串,"b":有綁技能(1/0),
 *            "cd":冷卻剩幾 tick,"ct":冷卻總長,"u":剩幾次,"mu":最多幾次,"mc":魔力消耗,"p":放不了的原因,
 *            "gl":榮耀現在,"gn":榮耀要幾點（2026-11-02 模組 1.4；不用榮耀的技能不寫）}],
 *  "buffs":[{"n":名稱,"l":剩幾 tick,"t":總長,"k":層數,"s":按鍵格(-1 = 不是技能格的),"i":重要(1；護符、神龕，2026-11-02 1.4)}],
 *  "target":{"n":名稱,"c":顏色(0xRRGGBB),"r":稀有度,"lv":等級,"hp":生命,"mhp":最大生命,"sh":護盾 %(-1 = 沒有),"boss":Boss(1/0),
 *            "st":[{"t":字,"c":顏色}],"inv":打不動(1；2026-11-02 模組 1.4，沒有不寫)},
 *  "vit":{"hp":生命,"mhp":最大生命,"rl":保留的生命,"es":能量護盾,"mes":最大能量護盾,"mp":魔力,"mmp":最大魔力,"ms":傷害先扣魔力的比例(0～1),
 *         "rmp":保留的魔力（2026-11-02 加的；0 不寫，舊模組忽略），
 *         下面是 2026-11-02 模組 1.4 加的（只多欄位；舊模組忽略）：
 *         "st":[{"t":字,"c":顏色}] 自己身上的異常、詛咒（只送給招呼寫了 status 的模組；沒有不寫），
 *         "gd":守護量（不到 1 不寫），"sf":入場保護(1；沒有不寫),
 *         "lv":角色等級(沒有角色不寫),"xp":這一級的經驗,"xpn":到下一級要幾點(滿級 0)},
 *  "res":{"rg":怒氣,"rgm":怒氣上限,"rgd":消退中(1),"ch":[{"n":充能名稱,"s":符號,"c":顏色,"k":幾顆,"m":上限}],"o":[{"t":字,"c":顏色}]}
 *        （2026-11-02 模組 1.4：戰鬥資源，只送給招呼寫了 res 的模組；什麼都沒有不寫），
 *  "auras":[光環名稱],"pets":[{"n":名稱,"k":幾隻,"m":上限(0 = 沒有上限)}],"loot":[{"n":名稱,"k":數量,"c":顏色,"l":還剩幾 tick,"t":總長}]
 *        （2026-11-02 模組 1.4：快捷欄兩側那些字（HotbarSides），只送給招呼寫了 sides 的模組；空的不寫）}
 * </pre>
 * 沒有的部分不寫（例如沒有目標就沒有 target）。
 */
public record HudSnapshot(List<Slot> slots, List<Buff> buffs, Target target, Vitals vitals, Self self, Resources res, Sides sides) {

    public HudSnapshot(final List<Slot> slots, final List<Buff> buffs, final Target target, final Vitals vitals) {
        this(slots, buffs, target, vitals, null, null, null);
    }

    public HudSnapshot(final List<Slot> slots, final List<Buff> buffs, final Target target, final Vitals vitals, final Self self,
                       final Resources res) {
        this(slots, buffs, target, vitals, self, res, null);
    }

    /** 2026-11-02（模組 1.4）：一種召喚物：幾隻、上限（0 = 沒有上限）。 */
    public record Pet(String name, int count, int limit) {
    }

    /** 2026-11-02（模組 1.4）：剛撿到的一樣東西（LootFeed）：until = 到哪個 tick 消失、total = 總共顯示多久。 */
    public record Loot(String name, int count, int color, long until, long total) {
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：快捷欄兩側那些字（光環、召喚物、撿到的東西；HotbarSides）改由模組畫：
     * 光環併進增益列、召喚物與撿到的東西是右邊的面板。這次之前接在動作列後面（球的外側），跟模組畫的東西擠在一起。
     */
    public record Sides(List<String> auras, List<Pet> pets, List<Loot> loot) {
    }

    public static final int VERSION = 1;

    /**
     * 一個技能格。cooldownEnd = 冷卻到哪個 tick 好（≤ 現在 = 好了）；maxUses ≤ 1 時不寫次數。
     */
    public record Slot(int slot, String skillId, String name, String item, String model, boolean bound, long cooldownEnd,
                       long cooldownTotal, int usesLeft, int maxUses, int manaCost, String problem, int gloryNow, int gloryNeed) {

        public Slot(final int slot, final String skillId, final String name, final String item, final String model, final boolean bound,
                    final long cooldownEnd, final long cooldownTotal, final int usesLeft, final int maxUses, final int manaCost,
                    final String problem) {
            this(slot, skillId, name, item, model, bound, cooldownEnd, cooldownTotal, usesLeft, maxUses, manaCost, problem, 0, 0);
        }
    }

    /** 一個增益。end = 到哪個 tick 結束；slot = 是哪個技能格的（-1 = 不是）；important = 護符、神龕（2026-11-02 1.4：模組排前面）。 */
    public record Buff(String name, long end, long total, int stacks, int slot, boolean important) {

        public Buff(final String name, final long end, final long total, final int stacks, final int slot) {
            this(name, end, total, stacks, slot, false);
        }
    }

    /**
     * 目標框。shieldPercent = -1 是沒有護盾；status = 異常、詛咒、印記、曝露（已經拆好顏色）。
     * invulnerable = 現在打不動（2026-11-02 模組 1.4：無敵、庇護光環、地圖開場無敵；模組畫成灰色加鎖頭，跟怪物頭上的血條一樣）。
     */
    public record Target(String name, int color, String rarity, int level, double life, double maxLife, int shieldPercent,
                         boolean boss, List<MiniParts.Part> status, boolean invulnerable) {

        public Target(final String name, final int color, final String rarity, final int level, final double life, final double maxLife,
                      final int shieldPercent, final boolean boss, final List<MiniParts.Part> status) {
            this(name, color, rarity, level, life, maxLife, shieldPercent, boss, status, false);
        }
    }

    /**
     * 自己的生命、護盾、魔力。manaShare = 受到的傷害有多少比例先扣魔力（0～1）。
     * reservedMana = 保留的魔力（2026-11-02 加的：魔力球頂的暗色那段；現在光環保留精魂，魔力保留一直是 0，先接好）。
     */
    public record Vitals(double life, double maxLife, double reservedLife, double energyShield, double maxEnergyShield,
                         double mana, double maxMana, double manaShare, double reservedMana) {

        public Vitals(final double life, final double maxLife, final double reservedLife, final double energyShield, final double maxEnergyShield,
                      final double mana, final double maxMana, final double manaShare) {
            this(life, maxLife, reservedLife, energyShield, maxEnergyShield, mana, maxMana, manaShare, 0);
        }
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：自己身上的東西（寫在 vit 裡）。status = 異常、詛咒（已經拆好顏色；不送就是空的）；
     * guard = 守護量；safe = 入場保護；level／xp／xpNeeded = 角色等級、這一級的經驗、到下一級要幾點（滿級 0）。
     */
    public record Self(List<MiniParts.Part> status, double guard, boolean safe, int level, long xp, long xpNeeded) {
    }

    /** 2026-11-02（模組 1.4）：一種充能（暴擊球、狂怒球…）：幾顆、上限。color 是 0xRRGGBB。 */
    public record Charge(String name, String symbol, int color, int count, int max) {
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：戰鬥資源（以前是畫面上方那條 Boss 血條的名稱）：怒氣（目前、上限、消退中）、各種充能、
     * 其他（連擊、灌注、熱度、三相…，已經拆好顏色，一段一段用 {@code "  "} 隔開）。
     */
    public record Resources(int rage, int rageMax, boolean decaying, List<Charge> charges, List<MiniParts.Part> other) {

        public boolean isEmpty() {
            return rage <= 0 && charges.isEmpty() && other.isEmpty();
        }
    }

    /** 2026-11-02：一串字段（{@code [{"t":字,"c":顏色}]}；目標框、提示列、面板共用）。 */
    public static JsonArray partsJson(final List<MiniParts.Part> parts) {
        final JsonArray array = new JsonArray();
        for (final MiniParts.Part part : parts) {
            final JsonObject p = new JsonObject();
            p.addProperty("t", part.text());
            p.addProperty("c", part.color());
            array.add(p);
        }
        return array;
    }

    /** 這一刻要送的 JSON。 */
    public String toJson(final long now) {
        return build(now, false).toString();
    }

    /** 拿來比有沒有變的字串（時間用結束的 tick，倒數本身不算變化）。 */
    public String signature() {
        return build(0, true).toString();
    }

    private JsonObject build(final long now, final boolean absolute) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        if (!absolute) {
            root.addProperty("t", now);
        }
        final JsonArray slotArray = new JsonArray();
        for (final Slot slot : slots) {
            final JsonObject o = new JsonObject();
            o.addProperty("s", slot.slot());
            o.addProperty("id", slot.skillId());
            o.addProperty("n", slot.name());
            o.addProperty("i", slot.item());
            if (slot.model() != null) {
                o.addProperty("m", slot.model());
            }
            o.addProperty("b", slot.bound() ? 1 : 0);
            final long left = absolute ? slot.cooldownEnd() : Math.max(0, slot.cooldownEnd() - now);
            if (absolute || left > 0) {
                o.addProperty("cd", left);
                o.addProperty("ct", Math.max(slot.cooldownTotal(), absolute ? 0 : left));
            }
            if (slot.maxUses() > 1) {
                o.addProperty("u", slot.usesLeft());
                o.addProperty("mu", slot.maxUses());
            }
            if (slot.manaCost() > 0) {
                o.addProperty("mc", slot.manaCost());
            }
            if (slot.problem() != null) {
                o.addProperty("p", slot.problem());
            }
            if (slot.gloryNeed() > 0) {
                o.addProperty("gl", Math.max(0, slot.gloryNow())); // 2026-11-02（1.4）：榮耀現在／要幾點；舊模組忽略
                o.addProperty("gn", slot.gloryNeed());
            }
            slotArray.add(o);
        }
        root.add("slots", slotArray);
        final JsonArray buffArray = new JsonArray();
        for (final Buff buff : buffs) {
            final JsonObject o = new JsonObject();
            o.addProperty("n", buff.name());
            final long left = absolute ? buff.end() : Math.max(0, buff.end() - now);
            o.addProperty("l", left);
            o.addProperty("t", Math.max(buff.total(), absolute ? 0 : left));
            if (buff.stacks() > 1) {
                o.addProperty("k", buff.stacks());
            }
            o.addProperty("s", buff.slot());
            if (buff.important()) {
                o.addProperty("i", 1); // 2026-11-02（1.4）：舊模組忽略
            }
            buffArray.add(o);
        }
        root.add("buffs", buffArray);
        if (target != null) {
            final JsonObject o = new JsonObject();
            o.addProperty("n", target.name());
            o.addProperty("c", target.color());
            o.addProperty("r", target.rarity());
            o.addProperty("lv", target.level());
            o.addProperty("hp", Math.round(target.life()));
            o.addProperty("mhp", Math.round(target.maxLife()));
            o.addProperty("sh", target.shieldPercent());
            o.addProperty("boss", target.boss() ? 1 : 0);
            if (target.invulnerable()) {
                o.addProperty("inv", 1); // 2026-11-02（1.4）：舊模組忽略
            }
            o.add("st", partsJson(target.status()));
            root.add("target", o);
        }
        if (vitals != null) {
            final JsonObject o = new JsonObject();
            o.addProperty("hp", Math.round(vitals.life()));
            o.addProperty("mhp", Math.round(vitals.maxLife()));
            o.addProperty("rl", Math.round(vitals.reservedLife()));
            o.addProperty("es", Math.round(vitals.energyShield()));
            o.addProperty("mes", Math.round(vitals.maxEnergyShield()));
            o.addProperty("mp", Math.round(vitals.mana()));
            o.addProperty("mmp", Math.round(vitals.maxMana()));
            o.addProperty("ms", Math.round(clamp01(vitals.manaShare()) * 100) / 100.0);
            if (Math.round(vitals.reservedMana()) > 0) {
                o.addProperty("rmp", Math.round(vitals.reservedMana()));
            }
            if (self != null) {
                if (!self.status().isEmpty()) {
                    o.add("st", partsJson(self.status()));
                }
                if (self.guard() >= 1) {
                    o.addProperty("gd", Math.round(self.guard()));
                }
                if (self.safe()) {
                    o.addProperty("sf", 1);
                }
                if (self.level() > 0) {
                    o.addProperty("lv", self.level());
                    o.addProperty("xp", Math.max(0, self.xp()));
                    o.addProperty("xpn", Math.max(0, self.xpNeeded()));
                }
            }
            root.add("vit", o);
        }
        if (res != null && !res.isEmpty()) {
            final JsonObject o = new JsonObject();
            if (res.rage() > 0) {
                o.addProperty("rg", res.rage());
                o.addProperty("rgm", Math.max(1, res.rageMax()));
                if (res.decaying()) {
                    o.addProperty("rgd", 1);
                }
            }
            if (!res.charges().isEmpty()) {
                final JsonArray charges = new JsonArray();
                for (final Charge charge : res.charges()) {
                    final JsonObject c = new JsonObject();
                    c.addProperty("n", charge.name());
                    c.addProperty("s", charge.symbol());
                    c.addProperty("c", charge.color());
                    c.addProperty("k", charge.count());
                    c.addProperty("m", Math.max(charge.count(), charge.max()));
                    charges.add(c);
                }
                o.add("ch", charges);
            }
            if (!res.other().isEmpty()) {
                o.add("o", partsJson(res.other()));
            }
            root.add("res", o);
        }
        if (sides != null) {
            if (!sides.auras().isEmpty()) {
                final JsonArray auras = new JsonArray();
                sides.auras().forEach(auras::add);
                root.add("auras", auras);
            }
            if (!sides.pets().isEmpty()) {
                final JsonArray pets = new JsonArray();
                for (final Pet pet : sides.pets()) {
                    final JsonObject p = new JsonObject();
                    p.addProperty("n", pet.name());
                    p.addProperty("k", pet.count());
                    if (pet.limit() > 0) {
                        p.addProperty("m", pet.limit());
                    }
                    pets.add(p);
                }
                root.add("pets", pets);
            }
            if (!sides.loot().isEmpty()) {
                final JsonArray loot = new JsonArray();
                for (final Loot item : sides.loot()) {
                    final JsonObject p = new JsonObject();
                    p.addProperty("n", item.name());
                    p.addProperty("k", item.count());
                    p.addProperty("c", item.color());
                    p.addProperty("l", absolute ? item.until() : Math.max(0, item.until() - now));
                    p.addProperty("t", item.total());
                    loot.add(p);
                }
                root.add("loot", loot);
            }
        }
        return root;
    }

    static double clamp01(final double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    /**
     * 受到的傷害有多少比例先扣魔力（0～1；畫愛心的紅藍比例用）。純函式（測試用）。
     *
     * @param fromManaPercent       「受到的傷害有 x% 先從魔力扣除」（傳奇、天賦，StatKeys.DAMAGE_FROM_MANA）
     * @param elementalFromManaPct  「元素傷害有 x% 先扣魔力」（瓦拉什塔的直覺）：只算元素那部分，當成一半的傷害是元素的
     * @param harmony               心宇和諧而且現在魔力高於生命（擊中傷害全部先扣魔力）
     * @param mana                  現在的魔力（0 = 扣不到，不畫藍色）
     */
    public static double manaShare(final double fromManaPercent, final double elementalFromManaPct, final boolean harmony, final double mana) {
        if (mana <= 0) {
            return 0;
        }
        if (harmony) {
            return 1;
        }
        final double direct = clamp01(fromManaPercent / 100.0);
        final double elemental = clamp01(elementalFromManaPct / 100.0) * 0.5;
        return clamp01(1 - (1 - direct) * (1 - elemental));
    }
}
