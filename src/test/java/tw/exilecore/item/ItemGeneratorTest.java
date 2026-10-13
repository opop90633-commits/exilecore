package tw.exilecore.item;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用 jar 裡的真實資料檔跑生成與通貨操作，檢查 PoE 規則的不變量。 */
class ItemGeneratorTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    /**
     * 通貨掉落率照 PoE2：蛻變、增幅最常見，稀有通貨裡崇高（至臻石）最常見，
     * 接著富豪、點金、混沌，機會、神聖、剝離很少見；神聖大約比崇高少 60–75 倍。掉落等級照 poe2db。
     * 2026-10：沒人用的微光石、添輝石、礪鋒石、甲冑殘片減半（品質通貨變得比至臻石少）。
     * 2026-10 第二次：小伺服器沒有交易，至臻石 1000 → 400、豐饒石 570 → 300、小鑿星石 160 → 60
     * （至臻石變得比品質通貨少；神聖大約比崇高少 27 倍）。
     * 2026-10 第三、四次（照 100 人伺服器調高價通貨）：至臻石 400 → 250 → 80、亂流石 170 → 55、湮滅石 13 → 4、神諭石 15 → 10 → 2、
     * 恩澤石 55 → 25、小鑿星石 60 → 24、鑿星石 30 → 9、僥倖石 22 → 9（至臻石變得比豐饒石、星火石少，湮滅石比神諭石常見；神聖比崇高少 40 倍）。
     * 2026-10 第五次（照市場報告）：神諭石 2 → 1（跟大鑿星石一樣少；神聖比崇高少 80 倍）、恩澤石 25 → 8、星盤刻刀 160 → 60、符文 25 → 10。
     */
    @Test
    void currencyDropRatesFollowPoe2() {
        assertEquals(1500, currencies.get("glimmer_orb").dropWeight());
        assertEquals(1250, currencies.get("lustre_orb").dropWeight());
        assertEquals(750, currencies.get("whetstone").dropWeight());
        assertEquals(80, currencies.get("zenith_orb").dropWeight());
        assertEquals(300, currencies.get("bounty_orb").dropWeight());
        assertEquals(24, currencies.get("lesser_socket_orb").dropWeight());
        final List<String> commonToRare = List.of("glimmer_orb", "lustre_orb", "whetstone", "bounty_orb", "spark_orb",
                "zenith_orb", "turmoil_orb", "chance_orb", "annul_orb", "oracle_orb");
        int previous = Integer.MAX_VALUE;
        for (final String id : commonToRare) {
            final int weight = currencies.get(id).dropWeight();
            assertTrue(weight > 0 && weight < previous, id + " 的權重 " + weight + " 要比前一個（" + previous + "）小");
            previous = weight;
        }
        // 2026-10 第五次：神諭石跟大鑿星石一樣少（權重都是 1，最小的整數）
        assertEquals(currencies.get("oracle_orb").dropWeight(), currencies.get("greater_socket_orb").dropWeight());
        assertEquals(currencies.get("whetstone").dropWeight(), currencies.get("armour_scrap").dropWeight());
        final double exaltedPerDivine = (double) currencies.get("zenith_orb").dropWeight() / currencies.get("oracle_orb").dropWeight();
        assertTrue(exaltedPerDivine > 60 && exaltedPerDivine < 100, "崇高：神聖 " + exaltedPerDivine); // 2026-10 第五次：40 → 80
        // 掉落等級：蛻變、增幅 1；崇高、富豪、點金 5；混沌、機會 12；神聖、剝離 35
        for (final String id : List.of("glimmer_orb", "lustre_orb")) {
            assertEquals(1, currencies.get(id).dropLevel(), id);
        }
        for (final String id : List.of("zenith_orb", "bounty_orb", "spark_orb", "whetstone", "armour_scrap")) {
            assertEquals(5, currencies.get(id).dropLevel(), id);
        }
        for (final String id : List.of("turmoil_orb", "chance_orb")) {
            assertEquals(12, currencies.get(id).dropLevel(), id);
        }
        for (final String id : List.of("oracle_orb", "annul_orb")) {
            assertEquals(35, currencies.get(id).dropLevel(), id);
        }
    }

    @Test
    void dataFilesLoad() {
        // 2026-10 PoE2 改版：裝備換成照 PoE2 分階的基底，舊的（retired）留著讓舊物品照常能用；藥劑、護符的基底也算在裡面
        assertEquals(940, repository.baseCount(), "現役 168 + 退役 737 + 藥劑與護符 35");
        assertEquals(168, repository.bases().size(), "現役的裝備基底（含 14 張地圖、8 種珠寶）");
        assertEquals(14, repository.bases().stream().filter(ItemBase::isMap).count());
        assertEquals(8, repository.bases().stream().filter(ItemBase::isJewel).count(), "4 種珠寶 + 4 種半徑珠寶");
        assertEquals(940 - 168 - 35, repository.baseCount() - repository.bases().size() - repository.flaskLikeBases().size());
        assertTrue(repository.affixCount() >= 30, "詞綴數 " + repository.affixCount());
        assertEquals(33 + 13 + 10 + 6 + 4 + 21 + 3 + 4, currencies.size(),
                "12 種改造通貨 + 3 種鑿星石 + 星盤刻刀 + 天穹碎片 + 僥倖石 + 6 種符文 + 2 種開服活動素材 + 傳奇兌換券 + 劫盜 6 種（含深淵星核），"
                        + "加上地圖機制的 13 星魄（2026-10：＋能盾星魄）、10 催化劑、6 星籤、4 蒸餾情緒，"
                        + "還有未切割寶石（技能 1–21 級、輔助 1–3 階，2026-10 算通貨），巔峰的 4 種碎片");
        // 鑿星石：開技能的第 3、4、5 個輔助孔，不能用在裝備上，高階的要高等級的怪才掉
        int previousLevel = 0;
        for (final String id : java.util.List.of("lesser_socket_orb", "socket_orb", "greater_socket_orb")) {
            final CurrencyType orb = currencies.get(id);
            assertNotNull(orb, id);
            assertTrue(orb.isSkillCurrency(), id);
            assertTrue(orb.dropLevel() > previousLevel, id + " 的掉落等級要一個比一個高");
            previousLevel = orb.dropLevel();
        }
        assertEquals(3, currencies.get("lesser_socket_orb").sockets());
        assertEquals(5, currencies.get("greater_socket_orb").sockets());
        assertTrue(currencies.get("greater_socket_orb").dropLevel() > 60, "大鑿星石只在地圖掉（劇情世界最高 60 級）");
        assertFalse(CurrencyOperation.SKILL_SOCKET.canApply(generator.generate(repository.base("mace1_26"),
                tw.exilecore.item.Rarity.NORMAL, 1, new Random(1)), generator), "鑿星石不能用在裝備上");
        // 鑑定卷軸只跟鑑定師領，不會掉
        final CurrencyType scroll = currencies.firstWith(CurrencyOperation.IDENTIFY).orElseThrow();
        assertEquals("identify_scroll", scroll.id());
        assertEquals(0, scroll.dropWeight());
        assertNotNull(repository.base("short_sword"), "退役的基底定義還在（舊物品照常能用）");
        assertTrue(repository.retiredBase("short_sword"));
        assertNotNull(repository.affix("life_flat"));
        for (final CurrencyType type : currencies.all()) {
            assertFalse(type.flavor().isBlank(), type.id() + " 沒有世界觀描述（flavor）");
        }
    }

    @Test
    void generatedItemsRespectRarityLimitsGroupsAndItemLevel() {
        final Random random = new Random(7);
        final List<ItemBase> bases = List.copyOf(repository.bases());
        int rareCount = 0;
        for (int i = 0; i < 3000; i++) {
            final ItemBase base = bases.get(random.nextInt(bases.size()));
            final Rarity rarity = Rarity.values()[random.nextInt(3)];
            final int ilvl = 1 + random.nextInt(70);
            final ItemData data = generator.generate(base, rarity, ilvl, random);
            checkInvariants(data, base);
            if (rarity == Rarity.RARE) {
                rareCount++;
                assertTrue(data.affixes().size() >= (base.isJewel() ? 3 : 4) || generator.eligible(data, AffixType.PREFIX).isEmpty()
                        || generator.eligible(data, AffixType.SUFFIX).isEmpty(),
                        "稀有物品至少 4 條詞綴（珠寶 3 條）：" + base.id() + " " + data.affixes().size());
                if (base.isJewel()) {
                    assertTrue(data.affixes().size() <= 4, "稀有珠寶最多 4 條：" + data.affixes().size());
                    assertTrue(generator.countOf(data, AffixType.PREFIX) <= 2 && generator.countOf(data, AffixType.SUFFIX) <= 2, "珠寶前後綴各最多 2 條");
                }
                assertNotNull(data.name());
                assertTrue(data.identified(), "生成器做出來是已鑑定的；掉落表才會設成未鑑定");
            }
            if (rarity == Rarity.MAGIC) {
                assertTrue(data.affixes().size() >= 1 && data.affixes().size() <= 2);
                assertTrue(data.identified());
            }
            if (rarity == Rarity.NORMAL) {
                assertTrue(data.affixes().isEmpty());
                assertTrue(data.identified());
            }
        }
        assertTrue(rareCount > 800);
    }

    private void checkInvariants(final ItemData data, final ItemBase base) {
        final Set<String> groups = new HashSet<>();
        int prefixes = 0;
        int suffixes = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            assertNotNull(affix, "未知詞綴 " + rolled.id());
            assertTrue(affix.canSpawnOn(base), affix.id() + " 不該出現在 " + base.id());
            assertTrue(groups.add(affix.group()), "群組重複 " + affix.group());
            final AffixTier tier = affix.tier(rolled.tier());
            assertTrue(tier.ilvl() <= data.ilvl(), affix.id() + " T" + tier.tier() + " 需要 ilvl " + tier.ilvl() + " 但物品是 " + data.ilvl());
            for (final var entry : tier.vars().entrySet()) {
                final int value = rolled.vars().get(entry.getKey());
                assertTrue(value >= Math.min(entry.getValue()[0], entry.getValue()[1]) && value <= Math.max(entry.getValue()[0], entry.getValue()[1]));
            }
            if (affix.type() == AffixType.PREFIX) {
                prefixes++;
            } else {
                suffixes++;
            }
        }
        assertTrue(prefixes <= data.rarity().maxPrefixes(), "前綴超過上限");
        assertTrue(suffixes <= data.rarity().maxSuffixes(), "後綴超過上限");
        if (base.implicit() != null) {
            assertEquals(base.implicit().vars().keySet(), data.implicitVars().keySet());
        }
    }

    @Test
    void higherTiersOnlyAppearAtHighItemLevelAndAreRarer() {
        final Random random = new Random(11);
        final ItemBase base = repository.base("plate_body");
        int t1 = 0;
        int t6 = 0;
        for (int i = 0; i < 2000; i++) {
            final ItemData data = generator.generate(base, Rarity.RARE, 80, random);
            for (final RolledAffix rolled : data.affixes()) {
                if (rolled.id().equals("life_flat_body")) { // 2026-10 PoE2：胸甲的生命詞綴是 life_flat_body（13 階）
                    if (rolled.tier() == 1) {
                        t1++;
                    } else if (rolled.tier() == 6) {
                        t6++;
                    }
                }
            }
        }
        assertTrue(t1 > 0 && t6 > t1 * 2, "T1=" + t1 + " T6=" + t6);

        final ItemData lowLevel = generator.generate(base, Rarity.RARE, 5, random);
        for (final RolledAffix rolled : lowLevel.affixes()) {
            assertTrue(rolled.tier() >= repository.affix(rolled.id()).tiers().size() - 1 || repository.affix(rolled.id()).tier(rolled.tier()).ilvl() <= 5);
        }
    }

    @Test
    void currencyOperationsFollowTheRules() {
        final Random random = new Random(21);
        final ItemBase base = repository.base("short_sword");
        final ItemData data = generator.generate(base, Rarity.NORMAL, 40, random);

        assertTrue(CurrencyOperation.UPGRADE_TO_MAGIC.canApply(data, generator));
        assertFalse(CurrencyOperation.REMOVE_AND_ADD.canApply(data, generator));
        assertFalse(CurrencyOperation.REMOVE_AFFIX.canApply(data, generator));
        CurrencyOperation.UPGRADE_TO_MAGIC.apply(data, generator, random);
        assertEquals(Rarity.MAGIC, data.rarity());
        assertTrue(data.identified());
        assertEquals(1, data.affixes().size(), "微光石固定 1 條（PoE2）");

        // 補到 2 條後不能再加
        while (data.affixes().size() < 2) {
            assertTrue(CurrencyOperation.ADD_AFFIX_MAGIC.canApply(data, generator));
            CurrencyOperation.ADD_AFFIX_MAGIC.apply(data, generator, random);
        }
        assertFalse(CurrencyOperation.ADD_AFFIX_MAGIC.canApply(data, generator));

        CurrencyOperation.MAGIC_TO_RARE.apply(data, generator, random);
        assertEquals(Rarity.RARE, data.rarity());
        assertEquals(3, data.affixes().size());
        assertNotNull(data.name());

        int guard = 0;
        while (CurrencyOperation.ADD_AFFIX_RARE.canApply(data, generator) && guard++ < 10) {
            CurrencyOperation.ADD_AFFIX_RARE.apply(data, generator, random);
        }
        assertTrue(data.affixes().size() <= 6);
        checkInvariants(data, base);

        // 亂流石：移除一條再加一條，數量不變；湮滅石：少一條
        final int beforeChaos = data.affixes().size();
        assertTrue(CurrencyOperation.REMOVE_AND_ADD.canApply(data, generator));
        CurrencyOperation.REMOVE_AND_ADD.apply(data, generator, random);
        assertEquals(beforeChaos, data.affixes().size());
        checkInvariants(data, base);
        assertTrue(CurrencyOperation.REMOVE_AFFIX.canApply(data, generator));
        CurrencyOperation.REMOVE_AFFIX.apply(data, generator, random);
        assertEquals(beforeChaos - 1, data.affixes().size());
        assertEquals(Rarity.RARE, data.rarity(), "湮滅石不改稀有度");

        final List<RolledAffix> before = data.affixes().stream().map(RolledAffix::copy).toList();
        CurrencyOperation.REROLL_VALUES.apply(data, generator, random);
        assertEquals(before.size(), data.affixes().size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).id(), data.affixes().get(i).id());
            assertEquals(before.get(i).tier(), data.affixes().get(i).tier());
        }

        CurrencyOperation.SCOUR.apply(data, generator, random);
        assertEquals(Rarity.NORMAL, data.rarity());
        assertTrue(data.affixes().isEmpty());

        assertTrue(CurrencyOperation.QUALITY_WEAPON.canApply(data, generator));
        CurrencyOperation.QUALITY_WEAPON.apply(data, generator, random);
        assertEquals(5, data.quality());
        assertFalse(CurrencyOperation.QUALITY_ARMOUR.canApply(data, generator));

        // 星火石固定 4 條（PoE2 的煉金石）
        final ItemData alchemy = generator.generate(base, Rarity.NORMAL, 40, random);
        CurrencyOperation.UPGRADE_TO_RARE.apply(alchemy, generator, random);
        assertEquals(Rarity.RARE, alchemy.rarity());
        assertEquals(4, alchemy.affixes().size());
        // 品質不分稀有度每次 +5，上限 20
        for (int i = 0; i < 6; i++) {
            if (CurrencyOperation.QUALITY_WEAPON.canApply(alchemy, generator)) {
                CurrencyOperation.QUALITY_WEAPON.apply(alchemy, generator, random);
            }
        }
        assertEquals(20, alchemy.quality());
    }

    @Test
    void currencyTypesRestrictRarity() {
        final Random random = new Random(5);
        final ItemData normal = generator.generate(repository.base("hand_axe"), Rarity.NORMAL, 10, random);
        final CurrencyType turmoil = currencies.get("turmoil_orb");
        final CurrencyType glimmer = currencies.get("glimmer_orb");
        assertFalse(turmoil.canApply(normal, generator));
        assertTrue(glimmer.canApply(normal, generator));
    }

    @Test
    void modifiersAndNamesComeFromTheData() {
        final Random random = new Random(9);
        final ItemBase base = repository.base("short_sword");
        final ItemData data = generator.generate(base, Rarity.NORMAL, 1, random);
        final List<Modifier> mods = ItemStats.modifiers(repository, data);
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals(StatKeys.WEAPON_MIN) && m.value() == 6));
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals(StatKeys.WEAPON_APS) && m.value() == 1.55));
        assertEquals("短劍", ItemStats.displayName(repository, data));

        final ItemData high = generator.generate(base, Rarity.NORMAL, 60, random);
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, high);
        assertTrue(snapshot.damage().get(tw.exilecore.stats.DamageType.PHYSICAL)[1] > 40, "等級 60 的武器要明顯更強");
        assertEquals(54, snapshot.requirements().level());

        final ItemData magic = generator.generate(base, Rarity.MAGIC, 30, random);
        magic.setIdentified(true);
        final String name = ItemStats.displayName(repository, magic);
        assertTrue(name.contains("短劍") && name.length() > 2, name);
        assertFalse(ItemStats.affixLines(repository, magic).isEmpty());
        // 未鑑定時詞綴不生效
        magic.setIdentified(false);
        assertTrue(ItemStats.modifiers(repository, magic).stream().noneMatch(m -> m.source().contains("T")));
    }

    @Test
    void jsonRoundTrip() {
        final Random random = new Random(13);
        final ItemData data = generator.generate(repository.base("kite_shield"), Rarity.RARE, 45, random);
        data.setQuality(7);
        final Gson gson = new Gson();
        final ItemData back = gson.fromJson(gson.toJson(data), ItemData.class);
        assertEquals(data.uuid(), back.uuid());
        assertEquals(data.base(), back.base());
        assertEquals(data.rarity(), back.rarity());
        assertEquals(data.ilvl(), back.ilvl());
        assertEquals(7, back.quality());
        assertEquals(data.affixes().size(), back.affixes().size());
        assertEquals(data.affixes().get(0).vars(), back.affixes().get(0).vars());
        assertEquals(data.name(), back.name());
    }

    @Test
    void chanceOrbOnlyWorksOnBasesWithChanceableUniques() {
        final CurrencyType chance = currencies.get("chance_orb");
        assertEquals(CurrencyOperation.CHANCE, chance.operation());
        assertEquals(0.1, chance.success(), 1e-9);
        // 2026-10 PoE2 改版：潮珊指環、天穹之心退役；勁擊指環有赤紋之戒（60 級，僥倖石做得出來）
        final ItemData ring = generator.generate(repository.base("jring_16"), Rarity.NORMAL, 60, new Random(3));
        assertTrue(chance.canApply(ring, generator), "勁擊指環有能用僥倖石做出來的傳奇");
        final ItemData lowRing = generator.generate(repository.base("jring_16"), Rarity.NORMAL, 59, new Random(3));
        assertFalse(chance.canApply(lowRing, generator), "物品等級不夠（傳奇 60 級才有）");
        final ItemData amulet = generator.generate(repository.base("jamulet_09"), Rarity.NORMAL, 60, new Random(3));
        assertFalse(chance.canApply(amulet, generator), "天樞墜是終局 Boss 專屬，做不出來");
        final ItemData magic = generator.generate(repository.base("jring_16"), Rarity.MAGIC, 60, new Random(3));
        assertFalse(chance.canApply(magic, generator), "只能用在普通物品");
        final ItemData starter = generator.generate(repository.base("jring_16"), Rarity.NORMAL, 60, new Random(3));
        starter.markStarter();
        assertFalse(chance.canApply(starter, generator), "新手裝備不行");
        // 成功：還是同一件物品（UUID、物品等級不變），變成傳奇、已鑑定
        final String uuid = ring.uuid();
        generator.makeUnique(ring, CurrencyOperation.chanceUniques(ring, generator).getFirst(), new Random(4));
        assertEquals(Rarity.UNIQUE, ring.rarity());
        assertEquals(uuid, ring.uuid());
        assertEquals(60, ring.ilvl());
        assertNotNull(ring.unique());
        assertTrue(ring.identified());
        assertFalse(ring.affixes().isEmpty());
        assertTrue(ring.affixes().stream().allMatch(affix -> affix.id().startsWith(UniqueItem.AFFIX_PREFIX)));
    }
}
