package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.character.VirtualCharacter;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.passive.JewelSockets;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 網頁計算器讀進來的格式、次數限制、虛擬角色（2026-10，用真的天賦樹與技能資料）。 */
class BuildCalcTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static PassiveTree tree;
    static SkillConfig skills;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
        skills = new SkillConfig();
        skills.load(DATA);
    }

    /** 從起點往外配 count 點。 */
    static Set<String> walk(final String classId, final int count) {
        final PassiveNode start = tree.start(classId);
        final Set<String> allocated = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            PassiveNode next = null;
            for (final PassiveNode node : tree.ordered()) {
                if (tree.canAllocate(allocated, start, node)) {
                    next = node;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            allocated.add(next.id());
        }
        return allocated;
    }

    private static SkillDefinition anyDamageSkill() {
        return skills.all().stream().filter(d -> !d.hidden() && d.dealsDamage() && d.summon() == null && d.aura() == null)
                .findFirst().orElseThrow();
    }

    private static JsonObject request(final String code) {
        final JsonObject json = new JsonObject();
        json.addProperty("tree", code);
        json.addProperty("level", 70);
        return json;
    }

    @Test
    void readsTreeLevelAndClass() throws Exception {
        final Set<String> nodes = walk("witch", 12);
        final BuildCalc.Build build = BuildCalc.parse(request(TreeCode.encode(tree, "witch", nodes)).toString(), tree, skills);
        assertEquals(CharacterClass.WITCH, build.characterClass());
        assertEquals(70, build.level());
        assertEquals(nodes, build.passives());
        // 2026-10-26：多了展露之指的第三個戒指欄（PlayerState.GEAR_SLOTS，11 格）；這次之前是 JEWELLERY_SLOTS 的 10 格
        assertEquals(PlayerState.GEAR_SLOTS.length, build.gear().size(), "十一個裝備欄都有位置（空的是 null）");
        assertTrue(build.gear().stream().allMatch(java.util.Objects::isNull));
        assertNull(build.supportsFor());
    }

    @Test
    void badInputIsRejectedWithAMessage() {
        assertThrows(BuildCalc.BadRequest.class, () -> BuildCalc.parse("not json", tree, skills));
        assertThrows(BuildCalc.BadRequest.class, () -> BuildCalc.parse("[1,2]", tree, skills));
        final BuildCalc.BadRequest bad = assertThrows(BuildCalc.BadRequest.class,
                () -> BuildCalc.parse(request("EC1.999.witch.AA").toString(), tree, skills));
        assertNotNull(bad.getMessage());
        assertFalse(bad.getMessage().isBlank());
    }

    @Test
    void levelIsClamped() throws Exception {
        final JsonObject json = request(TreeCode.encode(tree, "witch", Set.of()));
        json.addProperty("level", 999);
        assertEquals(VirtualCharacter.MAX_LEVEL, BuildCalc.parse(json.toString(), tree, skills).level());
        json.addProperty("level", -5);
        assertEquals(1, BuildCalc.parse(json.toString(), tree, skills).level());
    }

    @Test
    void attributeChoicesComeFromTheTreeCodeAndOverrideTheSnapshot() throws Exception {
        final Set<String> nodes = walk("ranger", 30);
        final String attrNode = nodes.stream().filter(id -> tree.node(id).modifiers().stream()
                .anyMatch(m -> tw.exilecore.stats.StatKeys.ATTR_CHOICE.equals(m.stat()))).findFirst().orElse(null);
        final JsonObject json = request(TreeCode.encode(tree, "ranger", nodes, attrNode == null ? Map.of() : Map.of(attrNode, "int")));
        final JsonObject progress = new JsonObject();
        progress.addProperty("attr.someOldNode", "str"); // 快照裡舊的選擇：以代碼為準
        progress.addProperty("boss.coast", "1");
        progress.addProperty("profile.hidden", "1"); // 不認得的鍵不收
        progress.addProperty("q.prologue", "done");
        json.add("progress", progress);
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        assertFalse(build.progress().containsKey("attr.someOldNode"));
        assertFalse(build.progress().containsKey("profile.hidden"));
        assertEquals("1", build.progress().get("boss.coast"));
        assertEquals("done", build.progress().get("q.prologue"));
        if (attrNode != null) {
            assertEquals("int", build.progress().get("attr." + attrNode));
        }
    }

    @Test
    void ascendancyGoesIntoProgress() throws Exception {
        final JsonObject json = request(TreeCode.encode(tree, "witch", Set.of()));
        json.addProperty("asc", "lich");
        final JsonArray nodes = new JsonArray();
        nodes.add("a1");
        nodes.add("bad node!"); // 奇怪的代號不收
        nodes.add("a2");
        json.add("ascNodes", nodes);
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        assertEquals("lich", build.progress().get(tw.exilecore.ascendancy.Ascension.CHOSEN));
        assertEquals("a1,a2", build.progress().get(tw.exilecore.ascendancy.Ascension.NODES));
        // asc 寫 null：沒有昇華（快照裡的也拿掉）
        final JsonObject progress = new JsonObject();
        progress.addProperty("asc", "lich");
        json.add("progress", progress);
        json.add("asc", com.google.gson.JsonNull.INSTANCE);
        assertNull(BuildCalc.parse(json.toString(), tree, skills).progress().get(tw.exilecore.ascendancy.Ascension.CHOSEN));
    }

    @Test
    void skillsBindsAndSupportsFor() throws Exception {
        final SkillDefinition skill = anyDamageSkill();
        final String support = skills.supports().iterator().next().id();
        final JsonObject json = request(TreeCode.encode(tree, "witch", Set.of()));
        final JsonArray list = new JsonArray();
        final JsonObject unknown = new JsonObject();
        unknown.addProperty("id", "no_such_skill");
        list.add(unknown); // 第 0 個不認得：略過，後面的照樣讀
        final JsonObject s = new JsonObject();
        s.addProperty("id", skill.id());
        s.addProperty("level", 99);
        s.addProperty("sockets", 3);
        final JsonArray supports = new JsonArray();
        supports.add(support);
        supports.add("no_such_support");
        s.add("supports", supports);
        list.add(s);
        json.add("skills", list);
        final JsonArray binds = new JsonArray();
        binds.add(1); // 左鍵 → 第 1 個（認得的那個）
        binds.add(0); // 右鍵 → 第 0 個（不認得：不綁）
        json.add("binds", binds);
        json.addProperty("supportsFor", 1);
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        final SkillLoadout loadout = build.skills();
        final int position = loadout.find(skill.id());
        assertTrue(position >= 0);
        final SkillLoadout.Entry entry = loadout.skill(position);
        assertEquals(SkillLoadout.CORRUPT_CAP, entry.level(), "等級夾在上限內（腐化的 Lv.22）");
        assertEquals(3, entry.sockets());
        assertEquals(List.of(support), entry.supportIds(), "不認得的輔助當成空孔");
        assertEquals(position, loadout.bound(SkillLoadout.KEY_LEFT));
        assertEquals(-1, loadout.bound(SkillLoadout.KEY_RIGHT));
        assertEquals(position, build.supportsFor());
        assertNull(build.instilFor(), "沒寫就不比較注入");
        // 2026-10：項鍊注入比較：指定技能（第 1 個）→ 伺服器的位置；不認得的、-1 → 自動挑傷害最高的
        json.addProperty("instilFor", 1);
        assertEquals(position, BuildCalc.parse(json.toString(), tree, skills).instilFor());
        json.addProperty("instilFor", 0);
        assertEquals(-1, BuildCalc.parse(json.toString(), tree, skills).instilFor());
        json.addProperty("instilFor", -1);
        assertEquals(-1, BuildCalc.parse(json.toString(), tree, skills).instilFor());
    }

    @Test
    void gearAndJewelsAreReadAsItemData() throws Exception {
        final JsonObject json = request(TreeCode.encode(tree, "witch", Set.of()));
        final JsonArray gear = new JsonArray();
        final JsonObject ring = JsonParser.parseString("{\"base\":\"x_ring\",\"rarity\":\"RARE\",\"ilvl\":60,"
                + "\"affixes\":[{\"id\":\"life\",\"tier\":1,\"vars\":{\"v\":80}}]}").getAsJsonObject();
        gear.add(ring);
        gear.add("garbage");
        json.add("gear", gear);
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        final ItemData data = build.gear().get(0);
        assertNotNull(data);
        assertEquals("x_ring", data.base());
        assertEquals(Rarity.RARE, data.rarity());
        assertEquals(80, data.affixes().get(0).vars().get("v"));
        assertNull(build.gear().get(1), "看不懂的當成沒穿");
    }

    @Test
    void modifiersRoundTrip() {
        final Modifier modifier = new Modifier("life.max", ModType.INCREASED, 5, Set.of("x"), "稱號 星痕獵人");
        assertEquals(modifier, BuildCalc.modifier(BuildCalc.modifierJson(modifier)));
        assertNull(BuildCalc.modifier(JsonParser.parseString("{\"stat\":\"a\",\"type\":\"NOPE\",\"value\":1}")));
        assertNull(BuildCalc.modifier(JsonParser.parseString("{\"stat\":\"a\",\"type\":\"FLAT\",\"value\":\"NaN\"}")));
    }

    @Test
    void limiterAllowsBurstThenRefills() {
        final BuildCalc.Limiter limiter = new BuildCalc.Limiter(2, 3);
        assertTrue(limiter.allow("a", 0));
        assertTrue(limiter.allow("a", 0));
        assertTrue(limiter.allow("a", 0));
        assertFalse(limiter.allow("a", 0), "一下子最多 3 次");
        assertTrue(limiter.allow("b", 0), "別的 IP 不受影響");
        assertFalse(limiter.allow("a", 200));
        assertTrue(limiter.allow("a", 600), "每秒補 2 次");
    }

    /** 2026-11-02 照站長：批次算差異的次數照組數扣（熱圖一次送幾十組）。 */
    @Test
    void limiterChargesByCost() {
        final BuildCalc.Limiter limiter = new BuildCalc.Limiter(10, 20);
        assertTrue(limiter.allow("a", 0, 15));
        assertFalse(limiter.allow("a", 0, 6), "只剩 5 次：要 6 次整筆不扣");
        assertTrue(limiter.allow("a", 0, 5));
        assertEquals(2, limiter.retryAfter("a", 0, 15), "每秒補 10 次：要 15 次等 2 秒");
        assertTrue(limiter.allow("a", 1500, 15));
        assertTrue(limiter.allow("b", 0, 999), "超過一下子的上限：當成上限（不會永遠不能算）");
    }

    @Test
    void variantsAddAndRemoveNodes() throws Exception {
        final Set<String> nodes = walk("witch", 14);
        final String[] list = nodes.toArray(new String[0]);
        final String last = list[list.length - 1];
        final JsonObject json = request(TreeCode.encode(tree, "witch", nodes));
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        final PassiveNode extra = tree.ordered().stream().filter(n -> !n.isStart() && !nodes.contains(n.id())).findFirst().orElseThrow();
        final JsonArray variants = new JsonArray();
        final JsonObject v = new JsonObject();
        final JsonArray add = new JsonArray();
        add.add(extra.id());
        add.add("沒有這個節點");
        add.add(tree.start("witch").id());
        v.add("add", add);
        final JsonArray remove = new JsonArray();
        remove.add(last);
        v.add("remove", remove);
        variants.add(v);
        variants.add(new JsonObject());
        json.add("variants", variants);
        final List<BuildCalc.Variant> parsed = BuildCalc.parseVariants(json, tree);
        assertEquals(2, parsed.size());
        assertEquals(Set.of(extra.id()), parsed.get(0).add(), "不認得的、起點都略過");
        final BuildCalc.Build changed = BuildCalc.withVariant(build, parsed.get(0));
        assertTrue(changed.passives().contains(extra.id()));
        assertFalse(changed.passives().contains(last));
        assertEquals(nodes.size(), changed.passives().size());
        assertEquals(nodes, build.passives(), "原本的角色不變");
        assertEquals(build.passives(), BuildCalc.withVariant(build, parsed.get(1)).passives(), "空的一組＝原本的");
        assertNull(changed.supportsFor());
        final JsonArray tooMany = new JsonArray();
        for (int i = 0; i <= BuildCalc.MAX_VARIANTS; i++) {
            tooMany.add(new JsonObject());
        }
        json.add("variants", tooMany);
        assertThrows(BuildCalc.BadRequest.class, () -> BuildCalc.parseVariants(json, tree));
    }

    @Test
    void variantDropsAttributeChoiceAndJewelOfRefundedNodes() throws Exception {
        final BuildCalc.Build base = new BuildCalc.Build(CharacterClass.WITCH, 10, Set.of("a", "s"), Map.of("attr.a", "dex", "q.x", "1"),
                List.of(), Map.of("s", new ItemData()), new SkillLoadout(), List.of(), null, null);
        final BuildCalc.Build changed = BuildCalc.withVariant(base, new BuildCalc.Variant(Set.of(), Set.of("a", "s")));
        assertEquals(Map.of("q.x", "1"), changed.progress());
        assertTrue(changed.jewels().isEmpty());
        assertTrue(changed.passives().isEmpty());
    }

    @Test
    void lightNumbersKeepComparedKeysAndEhp() {
        final Map<String, Double> numbers = new java.util.LinkedHashMap<>();
        numbers.put("life", 1234.567);
        numbers.put("str", 50.0);
        numbers.put("resist.fire", 75.0);
        numbers.put("maxhit.physical", 1000.0);
        final JsonObject o = BuildCalc.lightNumbers(numbers, 5432.6);
        assertEquals(1234.57, o.get("life").getAsDouble(), 1e-9);
        assertEquals(75, o.get("resist.fire").getAsDouble(), 1e-9);
        assertFalse(o.has("str"), "只回傳要比較的");
        // 2026-11-02 照站長：ehp 是 PoB2 的 EHP（這次之前是 maxhit.* 的平均）
        assertEquals(5433, o.get("ehp").getAsDouble(), 1e-9);
        assertFalse(BuildCalc.lightNumbers(numbers, Double.POSITIVE_INFINITY).has("ehp"), "打不死的不放");
        assertFalse(BuildCalc.lightNumbers(numbers, null).has("ehp"), "沒算的不放");
    }

    @Test
    void enemySettingsAreReadWithPob2Defaults() throws Exception {
        assertEquals(BuildCalc.EnemySettings.DEFAULT, BuildCalc.parseEnemy(new JsonObject()));
        assertEquals(tw.exilecore.stats.EffectiveHitPool.Tier.PINNACLE, BuildCalc.EnemySettings.DEFAULT.tier(), "預設是終局 Boss");
        final JsonObject json = JsonParser.parseString("{\"enemy\":{\"level\":70,\"tier\":1,\"mix\":[1,0,0,0,0]}}").getAsJsonObject();
        final BuildCalc.EnemySettings e = BuildCalc.parseEnemy(json);
        assertEquals(70, e.level());
        assertEquals(tw.exilecore.stats.EffectiveHitPool.Tier.BOSS, e.tier());
        assertEquals(1, e.mix()[0], 1e-9);
        // 壞掉的：不認得的種類照預設、全 0 的比例＝預設分配、等級夾住
        final BuildCalc.EnemySettings bad = BuildCalc.parseEnemy(JsonParser.parseString(
                "{\"enemy\":{\"level\":9999,\"tier\":9,\"mix\":[0,0,0,0,0]}}").getAsJsonObject());
        assertEquals(VirtualCharacter.MAX_LEVEL, bad.level());
        assertEquals(tw.exilecore.stats.EffectiveHitPool.Tier.PINNACLE, bad.tier());
        assertNull(bad.mix());
        assertEquals(BuildCalc.EnemySettings.DEFAULT, BuildCalc.parseEnemy(JsonParser.parseString("{\"enemy\":{\"tier\":\"x\"}}").getAsJsonObject()));
        // 計算器送來的整份也讀得到
        final JsonObject body = request(TreeCode.encode(tree, "witch", walk("witch", 3), Map.of()));
        body.add("enemy", json.get("enemy"));
        assertEquals(tw.exilecore.stats.EffectiveHitPool.Tier.BOSS, BuildCalc.parse(body.toString(), tree, skills).enemy().tier());
        assertEquals(BuildCalc.EnemySettings.DEFAULT, BuildCalc.parse(request(TreeCode.encode(tree, "witch", walk("witch", 3), Map.of())).toString(),
                tree, skills).enemy(), "舊的計算器沒送 enemy：預設");
        // 一組配點保留敵人設定
        final BuildCalc.Build build = BuildCalc.parse(body.toString(), tree, skills);
        assertEquals(build.enemy(), BuildCalc.withVariant(build, new BuildCalc.Variant(Set.of(), Set.of())).enemy());
    }

    @Test
    void ehpJsonHasPob2Fields() throws IOException {
        final tw.exilecore.monster.MonsterConfig monsters = new tw.exilecore.monster.MonsterConfig();
        monsters.load(DATA);
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet()
                .setBase(tw.exilecore.stats.StatKeys.RESIST_MAX, 75).setBase(tw.exilecore.stats.DamageCalculator.FLAG_PLAYER_SIDE, 1)
                .setBase(tw.exilecore.stats.StatKeys.LIFE_MAX, 3000).setBase(tw.exilecore.stats.StatKeys.ES_MAX, 1000)
                .setBase(tw.exilecore.stats.StatKeys.MANA_MAX, 500).setBase(tw.exilecore.stats.StatKeys.ARMOUR, 5000)
                .setBase(tw.exilecore.stats.StatKeys.RESIST_FIRE, 75).setBase(tw.exilecore.stats.StatKeys.RESIST_COLD, 75)
                .setBase(tw.exilecore.stats.StatKeys.RESIST_LIGHTNING, 75);
        final BuildCalc.EnemySettings settings = BuildCalc.EnemySettings.DEFAULT;
        assertEquals(87, BuildCalc.enemyLevelOf(settings, 40, 84), "終局 Boss：最高階＋3（2026-11-03；這次之前 T16）");
        final tw.exilecore.stats.EffectiveHitPool.Enemy enemy = BuildCalc.enemy(settings, 40, monsters, 84);
        final double[] range = monsters.damageAt(87);
        assertEquals((range[0] + range[1]) / 2 * 12, enemy.total(), 1e-6, "終局 Boss：傷害表 × 12");
        final JsonObject o = BuildCalc.ehpJson(sheet, tw.exilecore.character.StatsReport.pools(sheet), enemy, settings, 84);
        for (final String key : List.of("value", "hits", "damagingHits", "hitChance", "damageChance", "critEffect", "maxhit", "taken", "enemy")) {
            assertTrue(o.has(key), key);
        }
        assertTrue(o.get("value").getAsDouble() > 0);
        assertEquals(o.get("value").getAsDouble(), o.get("hits").getAsDouble() * enemy.total(), enemy.total() * 0.01 + 1);
        assertEquals(84, o.getAsJsonObject("enemy").get("level").getAsInt());
        assertEquals("終局 Boss", o.getAsJsonObject("enemy").get("tierName").getAsString());
        assertEquals(3, o.getAsJsonObject("enemy").get("penetration").getAsDouble(), 1e-9);
        final JsonObject maxHit = o.getAsJsonObject("maxhit");
        // 火焰：75% 抗性 − 3% 穿透 ＝ 72%：生命 3000 ＋ 護盾 1000 → 4000 ÷ 0.28 ≈ 14286
        assertEquals(4000 / 0.28, maxHit.get("fire").getAsDouble(), 1.01);
        // 混沌：0 抗性、護盾兩倍：500 ＋ 3000
        assertEquals(3500, maxHit.get("chaos").getAsDouble(), 1.01);
        assertEquals(5, maxHit.size());
        // 全混沌、混沌免疫：EHP 無限大寫 -2、最多吃得下的混沌寫 -1（免疫）
        final tw.exilecore.stats.StatSheet ci = sheet.overlay(List.of(Modifier.flat(tw.exilecore.stats.StatKeys.FLAG_CHAOS_IMMUNE, 1, "測")));
        final BuildCalc.EnemySettings chaos = new BuildCalc.EnemySettings(0, tw.exilecore.stats.EffectiveHitPool.Tier.NORMAL, new double[] {0, 0, 0, 0, 1});
        final JsonObject ciJson = BuildCalc.ehpJson(ci, tw.exilecore.character.StatsReport.pools(ci), BuildCalc.enemy(chaos, 40, monsters, 84), chaos, 40);
        assertEquals(-2, ciJson.get("value").getAsDouble(), 1e-9);
        assertEquals(-1, ciJson.getAsJsonObject("maxhit").get("chaos").getAsDouble(), 1e-9);
    }

    @Test
    void virtualCharacterCarriesClassLevelAndLoadout() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.add(new SkillLoadout.Entry(anyDamageSkill().id(), 5));
        final PlayerState state = VirtualCharacter.state(CharacterClass.DRUID, 250, Set.of("n1"), Map.of("boss.coast", "1"), loadout);
        assertEquals(CharacterClass.DRUID, state.characterClass());
        assertEquals(VirtualCharacter.MAX_LEVEL, state.level(), "等級夾在上限");
        assertTrue(state.passives().contains("n1"));
        assertEquals("1", state.progress().get("boss.coast"));
        assertEquals(1, state.skills().size());
        assertTrue(state.hasCharacter());
    }

    @Test
    void jewelsFromDataNeedTheSocketAllocated() throws IOException {
        final ItemRepository repository = new ItemRepository();
        repository.load(DATA);
        final PassiveNode socket = tree.ordered().stream().filter(PassiveNode::isSocket).findFirst().orElseThrow();
        final tw.exilecore.item.ItemBase jewelBase = repository.bases().stream()
                .filter(b -> b.slot() == tw.exilecore.item.ItemSlot.JEWEL && !b.isRadiusJewel()).findFirst().orElseThrow();
        final ItemData jewel = new ItemData(jewelBase.id(), Rarity.MAGIC, 50);
        final tw.exilecore.item.Affix affix = repository.affixes().stream()
                .filter(a -> a.spawnTags().stream().anyMatch(jewelBase.tags()::contains)).findFirst().orElse(null);
        if (affix != null) {
            jewel.affixes().add(new tw.exilecore.item.RolledAffix(affix.id(), affix.tiers().getFirst().tier(),
                    affix.tiers().getFirst().roll(new java.util.Random(1))));
        }
        final Map<String, ItemData> jewels = Map.of(socket.id(), jewel);
        assertTrue(JewelSockets.modifiers(repository, jewels, Set.of(), tree).isEmpty(), "孔沒配：不算");
        final List<Modifier> mods = JewelSockets.modifiers(repository, jewels, Set.of(socket.id()), tree);
        assertEquals(tw.exilecore.item.ItemStats.modifiers(repository, jewel), mods, "孔配了：照物品資料算");
    }

    @Test
    void normalizeFillsWhatThePlannerLeavesOut() throws IOException {
        final ItemRepository repository = new ItemRepository();
        repository.load(DATA);
        assertNull(BuildCalc.normalize(repository, new ItemData("no_such_base", Rarity.RARE, 80)), "不認得的基底當成沒穿");
        // 有固定詞綴的基底：網頁沒給數值就照最高
        final tw.exilecore.item.ItemBase withImplicit = repository.bases().stream().filter(b -> b.implicit() != null).findFirst().orElseThrow();
        final ItemData plain = new ItemData(withImplicit.id(), Rarity.RARE, 80);
        plain.setIdentified(false);
        final ItemData normalized = BuildCalc.normalize(repository, plain);
        assertTrue(normalized.identified(), "一律當成已鑑定");
        assertFalse(normalized.implicitVars().isEmpty(), "固定詞綴的數值補上");
        assertTrue(plain.implicitVars().isEmpty(), "不改原本的");
        // 傳奇：版本 0（網頁新配的）＝現在的版本
        final tw.exilecore.item.UniqueItem unique = repository.uniques().iterator().next();
        final String json = "{\"base\":\"" + unique.base() + "\",\"rarity\":\"UNIQUE\",\"ilvl\":80,\"unique\":\"" + unique.id() + "\"";
        final ItemData fresh = new com.google.gson.Gson().fromJson(json + ",\"uniqueVersion\":0}", ItemData.class);
        assertEquals(unique.version(), BuildCalc.normalize(repository, fresh).uniqueVersion());
        final ItemData old = new com.google.gson.Gson().fromJson(json + "}", ItemData.class);
        assertEquals(1, BuildCalc.normalize(repository, old).uniqueVersion(), "遊戲裡的舊物品（沒記版本）照第 1 版");
    }

    /** 2026-10-26：永恆珠寶——網頁天賦樹即時換成征服後的節點（伺服器算雜湊，跟遊戲同一套）。 */
    @Test
    void timelessConquestJsonMatchesGame() throws Exception {
        // 找一個範圍內有基石、核心的珠寶孔
        PassiveNode socket = null;
        for (final PassiveNode node : tree.ordered()) {
            if (node.isSocket() && tw.exilecore.passive.TimelessJewels.inRadius(tree, node).stream().anyMatch(n -> n.type() == PassiveNode.Type.KEYSTONE)
                    && tw.exilecore.passive.TimelessJewels.inRadius(tree, node).stream().filter(n -> n.type() == PassiveNode.Type.NOTABLE).count() >= 4) {
                socket = node;
                break;
            }
        }
        assertNotNull(socket, "樹上要有範圍內有基石的珠寶孔");
        final JsonObject request = JsonParser.parseString("{\"socket\":\"" + socket.id() + "\",\"legion\":1,\"seed\":99999,\"conqueror\":2}").getAsJsonObject();
        final tw.exilecore.passive.TimelessJewels.Conquest conquest = BuildCalc.parseConquest(request, tree);
        assertEquals(tw.exilecore.passive.TimelessJewels.SEED_MAX, conquest.seed(), "種子夾到範圍內");
        final JsonObject json = BuildCalc.conquestJson(tree, conquest, Set.of());
        final Map<String, PassiveNode> game = tw.exilecore.passive.TimelessJewels.conquered(tree, conquest, Set.of());
        final JsonObject nodes = json.getAsJsonObject("nodes");
        assertEquals(game.size(), nodes.size());
        for (final Map.Entry<String, PassiveNode> entry : game.entrySet()) {
            assertEquals(entry.getValue().name(), nodes.getAsJsonObject(entry.getKey()).get("name").getAsString());
        }
        assertEquals("循環教義", json.get("keystone").getAsString());
        assertEquals("巴魯克", json.get("conquerorName").getAsString());
        // 換種子：核心跟著變（同一個種子永遠一樣）
        final JsonObject other = BuildCalc.conquestJson(tree, new tw.exilecore.passive.TimelessJewels.Conquest(socket.id(), conquest.legion(), 1234, 2), Set.of());
        assertEquals(other.toString(), BuildCalc.conquestJson(tree, new tw.exilecore.passive.TimelessJewels.Conquest(socket.id(), conquest.legion(), 1234, 2), Set.of()).toString());
        assertFalse(other.getAsJsonObject("nodes").toString().equals(nodes.toString()), "換種子，核心要變");
        // 深淵族：屬性點保留原本的效果、多給貢禮（add）
        final JsonObject abyss = BuildCalc.conquestJson(tree, BuildCalc.parseConquest(
                JsonParser.parseString("{\"socket\":\"" + socket.id() + "\",\"legion\":2,\"seed\":500,\"conqueror\":9}").getAsJsonObject(), tree), Set.of());
        assertEquals("遠目獻祭", abyss.get("keystone").getAsString(), "征服者夾到範圍內");
        for (final Map.Entry<String, com.google.gson.JsonElement> entry : abyss.getAsJsonObject("nodes").entrySet()) {
            final PassiveNode original = tree.node(entry.getKey());
            final JsonObject node = entry.getValue().getAsJsonObject();
            if (original.attributeChoice() > 0) {
                assertTrue(node.has("add"));
                assertEquals("+3 貢禮", node.getAsJsonArray("lines").get(0).getAsString());
            }
        }
        assertThrows(BuildCalc.BadRequest.class, () -> BuildCalc.parseConquest(JsonParser.parseString("{\"socket\":\"nope\",\"legion\":1}").getAsJsonObject(), tree));
    }
}
