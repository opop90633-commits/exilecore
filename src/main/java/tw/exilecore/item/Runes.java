package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 符文（2026-10，照 PoE2 的 Rune）：裝備有固定幾個符文孔，把符文（通貨）用在裝備上就嵌進一個空孔，
 * 照裝備是武器還是防具給不同的加成。嵌了拿不下來（只能用新的裝備）。定義在 data/runes.yml。純資料，不依賴 Bukkit。
 * <p>
 * 2026-10-26 照 PoE2 0.5：13 個家族 × 4 階（次級、普通、高階、完美），每一顆分三類加成：武器（weapon，PoE2 的 Martial Weapon）、
 * 法杖類（caster：法杖、長杖、權杖，PoE2 的 Wand or Staff；沒寫照 weapon）、防具與副手（armour）。一條加成可以有好幾個修飾器（mods）。
 * 防具上的護甲／閃避／能量護盾增加是本地的（跟防具詞綴一樣算進那件的數值，見 {@link ItemStats}）。
 * <p>
 * 符文孔：雙手武器 2、單手武器 1、胸甲 2、頭盔／褲子／鞋子／副手 1、飾品與地圖、珠寶沒有。
 */
public final class Runes {

    public static final String FILE = "runes.yml";

    /** 一條加成：說明文字與修飾器（2026-10-26：可以好幾個，例如附加傷害的最小、最大）。 */
    public record Line(String text, List<AffixMod> mods) {
        public Line {
            mods = List.copyOf(mods);
        }

        public Line(final String text, final AffixMod mod) {
            this(text, List.of(mod));
        }

        /** 第一個修飾器（舊的寫法一條只有一個）。 */
        public AffixMod mod() {
            return mods.getFirst();
        }
    }

    /**
     * 一種符文。caster：嵌在法杖類（有 caster 標籤：法杖、長杖、權杖）上的加成，沒寫照 weapon（2026-10-26）。
     * level：需求等級（PoE2 符文的 Requires Level；也是從幾級的怪物開始掉，0 = 沒寫）。
     * bondedWeapon／bondedCaster／bondedArmour：2026-10-27 照 PoE2 0.5 的羈絆詞綴（Bonded），只有昇華・聖賢智慧（Wisdom of the Maji）
     * 才有效果（{@link #bondedModifiers}）；法杖類沒寫就沒有（不照武器的）。
     */
    public record Rune(String id, String name, List<Line> weapon, List<Line> caster, List<Line> armour, int level,
                       List<Line> bondedWeapon, List<Line> bondedCaster, List<Line> bondedArmour,
                       Map<String, List<Line>> slots, boolean martialOnly, int limit, Map<String, List<Line>> bondedSlots) {
        public Rune {
            weapon = List.copyOf(weapon);
            caster = caster == null || caster.isEmpty() ? weapon : List.copyOf(caster);
            armour = List.copyOf(armour);
            bondedWeapon = bondedWeapon == null ? List.of() : List.copyOf(bondedWeapon);
            bondedCaster = bondedCaster == null ? List.of() : List.copyOf(bondedCaster);
            bondedArmour = bondedArmour == null ? List.of() : List.copyOf(bondedArmour);
            slots = slots == null ? Map.of() : Map.copyOf(slots);
            limit = Math.max(0, limit);
            bondedSlots = bondedSlots == null ? Map.of() : Map.copyOf(bondedSlots);
        }

        public Rune(final String id, final String name, final List<Line> weapon, final List<Line> caster, final List<Line> armour, final int level,
                    final List<Line> bondedWeapon, final List<Line> bondedCaster, final List<Line> bondedArmour) {
            this(id, name, weapon, caster, armour, level, bondedWeapon, bondedCaster, bondedArmour, Map.of(), false, 0, Map.of());
        }

        public Rune(final String id, final String name, final List<Line> weapon, final List<Line> caster, final List<Line> armour, final int level) {
            this(id, name, weapon, caster, armour, level, List.of(), List.of(), List.of());
        }

        /** 2026-11-04：魂核符文（soul core）、魔偶符文（idol）：有部位專屬的加成、只能嵌軍用武器、或一個角色僅限幾顆。 */
        public boolean soulCore() {
            return martialOnly || limit > 0 || !slots.isEmpty();
        }

        public Rune(final String id, final String name, final List<Line> weapon, final List<Line> armour) {
            this(id, name, weapon, List.of(), armour, 0);
        }

        /**
         * 嵌在這種基底上的加成。
         * 2026-11-04 照 PoE2 的魂核（Soul Core）：防具先看部位專屬的（slots 的 head／chest／legs／feet／shield），沒有才照 armour；
         * 只限軍用武器的（martial_only）嵌在法杖類上沒有加成（{@link Runes#uselessOn} 會擋）。
         */
        public List<Line> on(final ItemBase base) {
            final List<Line> own = slots.get(slotKey(base));
            if (own != null) {
                return own; // 2026-11-04：部位專屬的（魂核的頭盔、魔偶的權杖…）
            }
            if (!base.isWeapon()) {
                return armour;
            }
            if (base.tags().contains(CASTER_TAG)) {
                return martialOnly ? List.of() : caster;
            }
            return weapon;
        }

        /** 2026-10-27：嵌在這種基底上的羈絆詞綴（只有聖賢智慧才有效果）。 */
        public List<Line> bondedOn(final ItemBase base) {
            final List<Line> own = bondedSlots.get(slotKey(base));
            if (own != null) {
                return own; // 2026-11-04：魔偶的羈絆也分部位（bonded.slots）
            }
            if (!base.isWeapon()) {
                return bondedArmour;
            }
            if (base.tags().contains(CASTER_TAG)) {
                return martialOnly ? List.of() : bondedCaster;
            }
            return bondedWeapon;
        }

        /**
         * 2026-11-07 照站長「瓦爾傳奇照原作」（艾瑟琳的華儀「也照某個部位的效果算」）：照部位代號（{@link #SLOT_KEYS}，加上軍用武器 weapon、
         * 法杖類 caster）給的加成，跟 {@link #on} 的規則一樣：部位專屬的先，沒有的話權杖、法杖類照 caster（只限軍用武器的沒有），
         * 軍用武器照 weapon，其他（防具、盾、法器）照 armour。
         */
        public List<Line> onSlot(final String key) {
            final List<Line> own = slots.get(key);
            if (own != null) {
                return own;
            }
            return switch (key) {
                case "weapon" -> weapon;
                case "caster", "sceptre" -> martialOnly ? List.of() : caster;
                default -> armour;
            };
        }
    }

    /** 法杖類武器的標籤（法杖、長杖、權杖；PoE2 符文的 Wand or Staff 那一類）。 */
    public static final String CASTER_TAG = "caster";

    /** 2026-11-04：runes.yml 的 slots 可以寫的部位（PoE2 魂核的 Helmet／Body Armour／Gloves／Boots／Shield and Buckler；手套照慣例是腿甲）。 */
    public static final List<String> SLOT_KEYS = List.of("head", "chest", "legs", "feet", "shield", "buckler", "focus", "sceptre");

    /** 2026-11-04（魔偶符文・狐狸）：FLAT 1 = 嵌在這件上的符文、魔偶的羈絆詞綴都生效（不用聖賢智慧；本地的，ItemStats 讀）。 */
    public static final String LOCAL_BONDED = "local.rune_bonded";

    /** 2026-11-04：魂核符文「x% 需求轉換為力量／敏捷／智慧」（本地的，ItemStats.requirements 讀；後面接 str／dex／int）。 */
    public static final String REQ_CONVERT = "local.req_convert.";

    /** 2026-11-04：這種防具基底在 runes.yml 的 slots 裡的代號（副手的盾、輕盾是 shield；法器之類不是盾的副手沒有）。 */
    static String slotKey(final ItemBase base) {
        if (base.isWeapon()) {
            return base.tags().contains("sceptre") ? "sceptre" : "";
        }
        if (base.isShield()) {
            // 盾（力量、力敏、力智）、輕盾（敏捷的）、法器（智慧的）：照基底的標籤分
            return base.tags().contains("focus") ? "focus" : base.tags().contains("dex_only") ? "buckler" : "shield";
        }
        return switch (base.slot()) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            default -> "";
        };
    }

    private Runes() {
    }

    /** 這件基底有幾個符文孔。 */
    public static int slots(final ItemBase base) {
        if (base == null || !base.isWearable() || base.isJewellery()) {
            return 0;
        }
        if (base.isWeapon()) {
            return base.tags().contains("two_hand") ? 2 : 1;
        }
        return base.slot() == ItemSlot.CHEST ? 2 : 1;
    }

    /**
     * 2026-10-26 照 PoE2 的工匠石（Artificer's Orb：武器、法杖、長杖、防具）：這種基底的符文孔是用工匠石一個一個加的。
     * 權杖、箭袋不行（PoE2 不能加孔），那些照舊（基底有孔就全開）。
     */
    public static boolean artificerTarget(final ItemBase base) {
        return slots(base) > 0 && !base.tags().contains("sceptre") && !base.isQuiver();
    }

    /** 這件物品有幾個符文孔：傳奇寫了 rune_sockets 照傳奇（2026-10），不然照基底；再加蝕變石多開的（2026-10-21，可以超過上限）。 */
    public static int slots(final ItemRepository repository, final ItemBase base, final ItemData data) {
        final int extra = data == null ? 0 : data.corruptSockets();
        if (repository != null && data != null && data.unique() != null) {
            final int own = repository.uniqueRuneSockets(data.unique());
            if (own >= 0) {
                return own + extra;
            }
        }
        // 2026-10-26：工匠石拿掉了，符文孔照基底全開（中間那段時間做出來、記著 0 孔的也一樣全開）
        // 2026-11-04 照站長：攫握指環、日冕墜飾（bases 的 rune_sockets）有自己的符文孔；嵌的符文照防具那一套（手套、頭盔都是防具）
        final int own = repository == null || base == null ? 0 : repository.baseRuneSockets(base.id());
        return Math.max(slots(base), own) + extra;
    }

    static Map<String, Rune> load(final Path dataFolder) throws IOException {
        final Map<String, Rune> map = new LinkedHashMap<>();
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            return map;
        }
        final YamlNode root = YamlNode.load(file).node("runes");
        for (final String id : root.keys()) {
            final YamlNode node = root.node(id);
            final YamlNode bonded = node.node("bonded"); // 2026-10-27 照 PoE2：羈絆詞綴（可以不寫）
            // 2026-11-04 照 PoE2 的魂核：slots（部位專屬的加成）、martial_only（只能嵌軍用武器）、limit（一個角色僅限幾顆，0 = 不限）；有 slots 的 armour 可以不寫
            final Map<String, List<Line>> slots = slotLines(node);
            map.put(id, new Rune(id, node.requireString("name"), slots.isEmpty() || node.has("weapon") ? lines(node, "weapon") : List.of(),
                    node.has("caster") ? lines(node, "caster") : List.of(), slots.isEmpty() || node.has("armour") ? lines(node, "armour") : List.of(),
                    Math.max(0, node.integer("level", 0)),
                    optionalLines(bonded, "weapon"), optionalLines(bonded, "caster"), optionalLines(bonded, "armour"),
                    slots, node.bool("martial_only", false), Math.max(0, node.integer("limit", 0)), bonded == null ? Map.of() : slotLines(bonded)));
        }
        return map;
    }

    /** 2026-11-04：slots 底下各部位的加成（可以沒有）。 */
    private static Map<String, List<Line>> slotLines(final YamlNode node) {
        final Map<String, List<Line>> slots = new LinkedHashMap<>();
        if (node != null && node.has("slots")) {
            final YamlNode slotNode = node.node("slots");
            for (final String key : slotNode.keys()) {
                if (!SLOT_KEYS.contains(key)) {
                    throw new IllegalArgumentException(slotNode.path() + "." + key + "：不認得的部位（只有 " + String.join("、", SLOT_KEYS) + "）");
                }
                slots.put(key, lines(slotNode, key));
            }
        }
        return slots;
    }

    /** 2026-11-04（魔偶符文・狐狸）：這件物品上有沒有嵌著「嵌在這件上的符文獲得羈絆加成」的符文。 */
    public static boolean bondedUnlocked(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data == null || data.runes().isEmpty() || base == null || repository == null) {
            return false;
        }
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.on(base)) {
                for (final AffixMod mod : line.mods()) {
                    if (LOCAL_BONDED.equals(mod.stat())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 2026-10-27：可以沒有的一組加成（羈絆詞綴）。 */
    private static List<Line> optionalLines(final YamlNode node, final String key) {
        return node == null || !node.has(key) ? List.of() : lines(node, key);
    }

    private static List<Line> lines(final YamlNode node, final String key) {
        final List<Line> list = new ArrayList<>();
        for (final YamlNode child : node.list(key)) {
            if (child.has("mods")) { // 2026-10-26：一條加成好幾個修飾器 { text, mods: [ {stat…}, … ] }
                final List<AffixMod> mods = new ArrayList<>();
                for (final YamlNode mod : child.list("mods")) {
                    mods.add(AffixMod.parse(mod));
                }
                list.add(new Line(child.requireString("text"), mods));
            } else {
                list.add(new Line(child.requireString("text"), AffixMod.parse(child)));
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "." + key + "：至少要一條（{ text, stat, type, value }）");
        }
        return list;
    }

    /**
     * 符文效果的倍率：傳奇寫的 rune_effect，加上這件物品自己的「嵌在這件物品上的符文效果增加 x%」詞綴
     * （2026-10-26，淵座束帶 local.rune_effect；擲出來的數值）。
     */
    public static double effect(final ItemRepository repository, final ItemData data) {
        return effect(repository, data, null);
    }

    /**
     * 2026-11-04：同上，這一顆符文的倍率：魂核符文（core_＊）再加上這件的「嵌在這件上的魂核效果增加 x%」（瑪哈薩多的陰謀 local.core_effect）。
     * rune 是 null 時只算一般的。
     */
    public static double effect(final ItemRepository repository, final ItemData data, final Rune rune) {
        double effect = 1 + (data.unique() == null || repository == null ? 0 : repository.uniqueRuneEffect(data.unique()));
        final boolean core = rune != null && isSoulCore(rune.id()); // 2026-11-07：遠古魂核（thesis_＊）也是魂核（艾瑟琳的華儀的「魂核效果增加」也吃）；這次之前只看 core_＊
        if (repository != null && data.identified()) {
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                if (affix == null) {
                    continue;
                }
                for (final AffixMod mod : affix.mods()) {
                    if (tw.exilecore.item.unique.UniqueStats.LOCAL_RUNE_EFFECT.equals(mod.stat())
                            || core && tw.exilecore.item.unique.UniqueStats.LOCAL_CORE_EFFECT.equals(mod.stat())) {
                        effect += mod.toModifier(rolled.vars(), "").value();
                    }
                }
            }
        }
        return Math.max(0, effect);
    }

    /** 2026-11-04：魂核符文的代號開頭（runes.yml 的 core_＊）。 */
    public static final String CORE_PREFIX = "core_";
    /** 2026-11-07：遠古魂核（假說，Ancient Augment）的代號開頭（runes.yml 的 thesis_＊）；也算魂核（艾瑟琳的華儀能嵌、也吃「也照某部位」）。 */
    public static final String THESIS_PREFIX = "thesis_";

    /** 2026-11-07 照站長「瓦爾傳奇照原作」（艾瑟琳的華儀）：這顆是不是魂核（core_＊ 與遠古魂核 thesis_＊；魔偶、一般符文不是）。 */
    public static boolean isSoulCore(final String runeId) {
        return runeId != null && (runeId.startsWith(CORE_PREFIX) || runeId.startsWith(THESIS_PREFIX));
    }

    /** 2026-11-07：這件物品自己的（已鑑定的詞綴裡）FLAT 本地屬性加總。 */
    private static double localFlat(final ItemRepository repository, final ItemData data, final String stat) {
        if (repository == null || data == null || !data.identified()) {
            return 0;
        }
        double total = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            for (final AffixMod mod : affix.mods()) {
                if (stat.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.FLAT) {
                    total += mod.toModifier(rolled.vars(), "").value();
                }
            }
        }
        return total;
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」（艾瑟琳的華儀，PoE2 Atziri's Splendour）：嵌在這件上的魂核「也照哪些部位的效果算」：
     * 瓦爾詞綴 local.core_as.head／legs／feet（頭盔、手套＝腿甲、鞋子），原本那條 local.core_extra_slot 照這件的 UUID 抽一個
     * （{@link UniquePicks#CORE_SLOT}）。照這個順序、不重複。沒有回傳空的。
     */
    public static List<String> coreExtraSlots(final ItemRepository repository, final ItemData data) {
        if (repository == null || data == null || data.unique() == null) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        if (localFlat(repository, data, tw.exilecore.item.unique.VaalKeys.LOCAL_CORE_EXTRA_SLOT) > 0) {
            out.addAll(UniquePicks.picks(data, UniquePicks.CORE_SLOT, 1));
        }
        for (final String[] pair : new String[][] {{tw.exilecore.item.unique.VaalKeys.LOCAL_CORE_AS_HEAD, "head"},
                {tw.exilecore.item.unique.VaalKeys.LOCAL_CORE_AS_LEGS, "legs"}, {tw.exilecore.item.unique.VaalKeys.LOCAL_CORE_AS_FEET, "feet"}}) {
            if (!out.contains(pair[1]) && localFlat(repository, data, pair[0]) > 0) {
                out.add(pair[1]);
            }
        }
        return out;
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」（艾瑟琳的華儀）：這顆符文嵌在這件上給的全部加成：{@link Rune#on} 自己那一欄，
     * 魂核再加上 {@link #coreExtraSlots} 每個部位的（{@link Rune#onSlot}）。跟已經算過的同一組（例如胸甲與手套都照 armour）不重複給——
     * 原作「也當成鞋子」是多拿鞋子專屬的那條，防具共通的那條本來就有了。軍用武器、法杖類那幾組嵌在非武器上時，
     * 改那件武器自己數值的（攻速、物理增加、附加傷害、本地暴擊傷害加成）沒有武器可改，拿掉。
     */
    public static List<Line> linesOn(final ItemRepository repository, final ItemBase base, final ItemData data, final Rune rune) {
        final List<Line> own = rune.on(base);
        if (!isSoulCore(rune.id())) {
            return own;
        }
        final List<String> extras = coreExtraSlots(repository, data);
        if (extras.isEmpty()) {
            return own;
        }
        final List<List<Line>> seen = new ArrayList<>();
        seen.add(own);
        final List<Line> out = new ArrayList<>(own);
        for (final String key : extras) {
            final List<Line> lines = rune.onSlot(key);
            if (lines.isEmpty() || seen.stream().anyMatch(done -> done == lines || done.equals(lines))) {
                continue;
            }
            seen.add(lines);
            final boolean weaponLines = !base.isWeapon() && ("weapon".equals(key) || "caster".equals(key) || "sceptre".equals(key));
            for (final Line line : lines) {
                if (!weaponLines) {
                    out.add(line);
                    continue;
                }
                final List<AffixMod> kept = line.mods().stream().filter(mod -> !LocalMods.isWeaponLocal(mod)
                        && !(tw.exilecore.stats.StatKeys.CRIT_MULTI.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.FLAT && mod.tags().isEmpty())).toList();
                if (!kept.isEmpty()) {
                    out.add(kept.size() == line.mods().size() ? line : new Line(line.text(), kept));
                }
            }
        }
        return out;
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」（艾瑟琳的華儀「這件物品只能鑲嵌魂核符文」，{@link tw.exilecore.item.unique.VaalKeys#LOCAL_ONLY_SOUL_CORES}）
     * 與嵌了沒有加成的（{@link #uselessOn}）：這顆符文不能嵌進這件的原因；可以回傳 null。嵌空孔、覆蓋舊的都問這個。
     */
    public static String socketProblem(final ItemRepository repository, final ItemBase base, final ItemData data, final Rune rune) {
        if (rune == null || base == null) {
            return null;
        }
        if (!isSoulCore(rune.id()) && localFlat(repository, data, tw.exilecore.item.unique.VaalKeys.LOCAL_ONLY_SOUL_CORES) > 0) {
            return "這件物品只能鑲嵌魂核符文（魂核、遠古魂核）";
        }
        if (uselessOn(repository, base, data, rune)) {
            // 2026-11-04 照站長：攫握指環、日冕墜飾沒有護甲、閃避、能量護盾，只加這些的符文嵌了沒效果
            // 2026-11-04 照 PoE2 的魂核：只限軍用武器的嵌在法杖類上、只有某個部位才有加成的嵌在別的部位上，一樣沒效果
            return rune.soulCore() ? "這顆魂核符文嵌在這種裝備上沒有加成（看說明：武器、法杖類、哪個部位）"
                    : "這個符文只加那件裝備的護甲、閃避、能量護盾，戒指、項鍊沒有這些，嵌了沒效果";
        }
        return null;
    }

    /**
     * 2026-11-04：符文說明裡的數字乘上符文效果（effect 是 {@link #effect} 的倍率）：整數照四捨五入，有小數的留一位。
     * 倍率 1 原樣回傳。說明裡的數字都是那條符文的數值（修飾器也是整條乘 effect），所以全部一起乘。
     */
    public static String scaledText(final String text, final double effect) {
        if (text == null || Math.abs(effect - 1) < 1e-9) {
            return text;
        }
        final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String number = matcher.group();
            final double value = Double.parseDouble(number) * effect;
            final String scaled = number.contains(".") || Math.abs(value - Math.round(value)) >= 0.05 && value < 10
                    ? String.format(java.util.Locale.ROOT, "%.1f", value) : Long.toString(Math.round(value));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(scaled));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 嵌在武器上、改武器自己數值的符文修飾器（2026-10-17，LocalMods 用；已乘上傳奇的符文效果增加）。 */
    static List<Modifier> weaponLocal(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.runes().isEmpty() || base == null || !base.isWeapon()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            final double effect = effect(repository, data, rune); // 2026-11-04：每一顆各自算（魂核另外吃瑪哈薩多的陰謀）
            for (final Line line : rune.on(base)) {
                for (final AffixMod mod : line.mods()) {
                    if (LocalMods.isWeaponLocal(mod) && !mod.stat().startsWith(REQ_CONVERT) && !LOCAL_BONDED.equals(mod.stat())) { // 2026-11-04：魂核的需求轉換不是武器面板的
                        final Modifier modifier = mod.toModifier(Map.of(), "符文 " + rune.name());
                        list.add(effect == 1 ? modifier
                                : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                    }
                }
            }
        }
        return list;
    }

    /** 嵌著的符文給的修飾器（武器本地的不算，見 {@link #weaponLocal}）。 */
    public static List<Modifier> modifiers(final ItemRepository repository, final ItemBase base, final ItemData data) { // 2026-10-26：昇華・聖賢智慧也要讀（public）
        if (data.runes().isEmpty()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            final double effect = effect(repository, data, rune); // 傳奇的符文效果增加；2026-11-04：每一顆各自算（魂核另外吃瑪哈薩多的陰謀）
            for (final Line line : linesOn(repository, base, data, rune)) { // 2026-11-07（艾瑟琳的華儀）：魂核也照別的部位算；這次之前 rune.on(base)
                for (final AffixMod mod : line.mods()) {
                    if (base.isWeapon() && LocalMods.isWeaponLocal(mod)) {
                        continue; // 2026-10-17：武器本地的（增加 x% 物理傷害、攻擊速度）算進武器面板（LocalMods）
                    }
                    if (!base.isWeapon() && (ItemStats.isArmourLocal(base, mod) || isDefenceIncrease(mod))) {
                        continue; // 2026-10-26：防具本地的（增加 x% 護甲、閃避、能量護盾）算進那件防具的數值（ItemStats）；那件沒有的那種防禦就沒效果
                    }
                    if (mod.stat().startsWith(REQ_CONVERT) || LOCAL_BONDED.equals(mod.stat())) {
                        continue; // 2026-11-04：魂核的需求轉換、狐狸的羈絆解鎖是那件物品自己的（ItemStats 讀）
                    }
                    final Modifier modifier = mod.toModifier(Map.of(), "符文 " + rune.name());
                    list.add(effect == 1 ? modifier
                            : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                }
            }
        }
        return list;
    }

    /**
     * 2026-10-27 照 PoE2 0.5（昇華・聖賢智慧：Gain the benefits of Bonded modifiers on Runes and Idols）：嵌著的符文的羈絆詞綴給的修飾器
     * （都不是本地的，直接加在角色身上；已乘上傳奇的符文效果增加）。沒有聖賢智慧的人不會呼叫這個。
     */
    public static List<Modifier> bondedModifiers(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.runes().isEmpty() || base == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            final double effect = effect(repository, data, rune); // 2026-11-04：每一顆各自算（魂核另外吃瑪哈薩多的陰謀）
            for (final Line line : rune.bondedOn(base)) {
                for (final AffixMod mod : line.mods()) {
                    final Modifier modifier = mod.toModifier(Map.of(), "羈絆：" + rune.name());
                    list.add(effect == 1 ? modifier
                            : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                }
            }
        }
        return list;
    }

    /** 2026-10-26：嵌在防具上、改那件防具自己數值的符文修飾器（護甲／閃避／能量護盾的增加；已乘上傳奇的符文效果增加）。 */
    static double armourLocalSum(final ItemRepository repository, final ItemBase base, final ItemData data, final String stat,
                                 final tw.exilecore.stats.ModType type) {
        if (data.runes().isEmpty() || base == null || base.isWeapon()) {
            return 0;
        }
        double total = 0;
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            final double effect = effect(repository, data, rune); // 2026-10-27：跟其他符文加成一樣算上 local.rune_effect 詞綴；2026-11-04：每一顆各自算
            final List<Line> lines = new ArrayList<>(rune.armour());
            // 2026-11-07（艾瑟琳的華儀）：魂核「也照某部位算」多出來的那幾組（linesOn 在自己那一欄後面接的），本地的防禦增加也算在這件上
            final List<Line> all = linesOn(repository, base, data, rune);
            lines.addAll(all.subList(Math.min(all.size(), rune.on(base).size()), all.size()));
            for (final Line line : lines) {
                for (final AffixMod mod : line.mods()) {
                    if (stat.equals(mod.stat()) && mod.type() == type && ItemStats.isArmourLocal(base, mod)) {
                        total += mod.toModifier(Map.of(), "").value() * effect;
                    }
                }
            }
        }
        return total;
    }

    /**
     * 2026-11-07：同 {@link #uselessOn(ItemBase, Rune)}，再看這件物品：魂核嵌在「也照某部位算」的傳奇（艾瑟琳的華儀）上，
     * 別的部位有加成就不算沒效果。
     */
    public static boolean uselessOn(final ItemRepository repository, final ItemBase base, final ItemData data, final Rune rune) {
        if (base != null && rune != null && isSoulCore(rune.id()) && !base.isJewellery() && !linesOn(repository, base, data, rune).isEmpty()) {
            return false;
        }
        return uselessOn(base, rune);
    }

    /**
     * 2026-11-04 照站長「增加 % 護甲／閃避可以防止插進日冕墜飾跟攫握指環嗎」：嵌在飾品（沒有護甲、閃避、能量護盾）上
     * 完全沒有效果的符文（防具那一套只有「增加 x% 護甲／閃避／能量護盾」這類本地的）回傳 true。原作嵌了也沒效果，我們直接擋下來。
     */
    public static boolean uselessOn(final ItemBase base, final Rune rune) {
        if (base == null || rune == null) {
            return false;
        }
        if (rune.soulCore() && (rune.on(base).isEmpty() || base.isJewellery())) { // 2026-11-04：魂核、魔偶原作也不能嵌飾品（攫握指環、日冕墜飾）
            return true; // 2026-11-04 照 PoE2 的魂核：只能嵌軍用武器的嵌在法杖類上、只有頭盔那條的嵌在鞋子上——沒有加成就不給嵌
        }
        if (!base.isJewellery()) {
            return false;
        }
        for (final Line line : rune.on(base)) {
            for (final AffixMod mod : line.mods()) {
                if (!isDefenceIncrease(mod) && !ItemStats.isArmourLocal(base, mod)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 2026-11-04 照 PoE2 的魂核（艾特瑪哈／斯拉托／善堤比：x% 需求轉換為力量／敏捷／智慧）：嵌著的符文的需求轉換，
     * 回傳 [力量, 敏捷, 智慧] 各吃掉其他兩種需求的比例（0～1；已乘符文效果）。沒有回傳 null。
     */
    static double[] requirementConvert(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data == null || data.runes().isEmpty() || base == null || repository == null) {
            return null;
        }
        double[] convert = null;
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            final double effect = effect(repository, data, rune); // 2026-11-04：每一顆各自算（魂核另外吃瑪哈薩多的陰謀）
            for (final Line line : linesOn(repository, base, data, rune)) { // 2026-11-07：魂核也照別的部位算
                for (final AffixMod mod : line.mods()) {
                    if (!mod.stat().startsWith(REQ_CONVERT)) {
                        continue;
                    }
                    final int index = switch (mod.stat().substring(REQ_CONVERT.length())) {
                        case "str" -> 0;
                        case "dex" -> 1;
                        case "int" -> 2;
                        default -> -1;
                    };
                    if (index < 0) {
                        continue;
                    }
                    if (convert == null) {
                        convert = new double[3];
                    }
                    convert[index] = Math.min(1, convert[index] + mod.toModifier(Map.of(), "").value() * effect / 100);
                }
            }
        }
        return convert;
    }

    /**
     * 2026-11-04 照 PoE2 的魂核「僅限 1」：這件物品上嵌著、一個角色僅限幾顆的符文——已經在別件上算過的（seen 裡有）回傳它們的
     * 修飾器來源（「符文 名字」），讓屬性表跳過；沒算過的記進 seen。沒有僅限的回傳空集合。
     */
    public static java.util.Set<String> limitedDuplicates(final ItemRepository repository, final ItemData data, final Map<String, Integer> seen) {
        if (data == null || data.runes().isEmpty() || repository == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> blocked = null;
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null || rune.limit() <= 0) {
                continue;
            }
            // 2026-11-07 照 PoE2「Limited to: 1 Ancient Augment」：四顆假說（thesis_…）合起來一個角色只算一顆
            final int count = seen.merge(id.startsWith("thesis_") ? "ancient_augment" : id, 1, Integer::sum);
            if (count > rune.limit()) {
                if (blocked == null) {
                    blocked = new java.util.HashSet<>();
                }
                blocked.add("符文 " + rune.name());
            }
        }
        return blocked == null ? java.util.Set.of() : blocked;
    }

    /** 「增加 x% 護甲／閃避／能量護盾」（沒有標籤的 INCREASED）：嵌在防具上是本地的。 */
    private static boolean isDefenceIncrease(final AffixMod mod) {
        return mod.type() == tw.exilecore.stats.ModType.INCREASED && mod.tags().isEmpty()
                && (tw.exilecore.stats.StatKeys.ARMOUR.equals(mod.stat()) || tw.exilecore.stats.StatKeys.EVASION.equals(mod.stat())
                || tw.exilecore.stats.StatKeys.ES_MAX.equals(mod.stat()));
    }
}
