package tw.exilecore.currency;

import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;

import java.util.random.RandomGenerator;

/**
 * 通貨對物品做的操作。每個操作只做「讀物品資料 → 改資料」，容易單元測試；
 * 新通貨通常只是設定檔裡指到既有的操作。
 */
public enum CurrencyOperation {

    /** 鑑定。 */
    IDENTIFY {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return !data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setIdentified(true);
        }
    },

    /** 普通 → 魔法。 */
    UPGRADE_TO_MAGIC {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.NORMAL;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.upgradeToMagic(data, random);
            data.setIdentified(true);
        }
    },

    /** 魔法物品加一條詞綴。 */
    ADD_AFFIX_MAGIC {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.MAGIC && data.identified() && generator.canAddAffix(data, null);
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.addAffix(data, null, random);
        }
    },

    /** 重骰魔法物品的詞綴。 */
    REROLL_MAGIC {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.MAGIC && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.upgradeToMagic(data, random);
        }
    },

    /** 魔法 → 稀有，保留詞綴再加一條。 */
    MAGIC_TO_RARE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.MAGIC && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.magicToRare(data, random);
        }
    },

    /** 普通 → 稀有。 */
    UPGRADE_TO_RARE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.NORMAL;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.upgradeToRare(data, random);
            data.setIdentified(true);
        }
    },

    /** 亂流石（PoE2 的混沌石）：稀有物品隨機移除一條詞綴，再加一條新的。 */
    REMOVE_AND_ADD {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.RARE && data.identified() && !data.affixes().isEmpty();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.removeRandomAffix(data, random);
            generator.addAffix(data, null, random);
        }
    },

    /** 湮滅石：魔法或稀有物品隨機移除一條詞綴，稀有度不變。 */
    REMOVE_AFFIX {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE) && data.identified() && !data.affixes().isEmpty();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.removeRandomAffix(data, random);
        }
    },

    /** 重骰稀有物品全部詞綴（PoE1 的混沌石；預設資料沒用到，留給想改回來的人）。 */
    REROLL_RARE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.RARE && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setName(null);
            generator.upgradeToRare(data, random);
        }
    },

    /** 稀有物品加一條詞綴。 */
    ADD_AFFIX_RARE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.RARE && data.identified() && generator.canAddAffix(data, null);
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.addAffix(data, null, random);
        }
    },

    /** 移除所有詞綴，變回普通。 */
    SCOUR {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE) && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.scour(data);
        }
    },

    /** 重骰所有詞綴的數值，階級不變。 */
    REROLL_VALUES {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.identified() && !data.affixes().isEmpty();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.rerollValues(data, random);
        }
    },

    /** 重骰固定詞綴的數值。 */
    REROLL_IMPLICIT {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && base.implicit() != null && !base.implicit().vars().isEmpty();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            generator.rerollImplicit(data, random);
        }
    },

    /**
     * 腐化（2026-10，蝕變石）：物品變成已腐化（之後不能再改造），另外隨機一種結果：
     * 40% 沒有變化、30% 重骰所有詞綴的數值、30% 品質變成 21～25（武器、防具；超過一般的上限 20）。
     */
    CORRUPT {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && !base.isMap() && data.identified() && !data.corrupted() && !data.starter();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setCorrupted(true);
            final double roll = random.nextDouble();
            final var base = generator.repository().base(data.base());
            if (roll < 0.4) {
                return;
            }
            if (roll < 0.7 || base == null || !base.isWearable()) {
                if (!data.affixes().isEmpty()) {
                    generator.rerollValues(data, random);
                }
                return;
            }
            data.setQuality(Math.max(data.quality(), 20) + 1 + random.nextInt(5));
        }
    },

    /** 武器品質 +1（上限 20）。 */
    QUALITY_WEAPON {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && base.isWeapon() && data.quality() < 20;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setQuality(data.quality() + qualityStep(data));
        }
    },

    /** 防具品質 +1（上限 20）。 */
    QUALITY_ARMOUR {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && base.isArmour() && data.quality() < 20;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setQuality(data.quality() + qualityStep(data));
        }
    },

    /** 星盤刻刀：地圖品質 +5（上限 20）；地圖每 1% 品質多 1% 物品數量。 */
    QUALITY_MAP {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && base.isMap() && data.quality() < 20;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setQuality(data.quality() + qualityStep(data));
        }
    },

    /**
     * 僥倖石（PoE2）：普通物品有機會變成這個基底的傳奇，不然就碎掉。只能用在「有能用僥倖石做出來的傳奇」的基底上
     * （uniques.yml 的 sources 寫 chance，物品等級也要夠），新手裝備不行。
     * 追逐級傳奇的基底（物品等級 75 以上）也可以：每次有 maps.yml 的 drops.chase_mob_chance 的機率變成追逐級（跟小怪掉的機率一樣）。
     * 結果（變傳奇或碎掉）由 CurrencyService 處理，這裡只判斷能不能用。
     */
    CHANCE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.NORMAL && !data.starter()
                    && (!chanceUniques(data, generator).isEmpty() || !generator.chaseUniques(data.base(), data.ilvl()).isEmpty());
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("僥倖石的結果由 CurrencyService 處理（可能會碎掉）");
        }
    },

    /**
     * 星魄（PoE2）：普通物品變魔法、魔法物品變稀有，而且一定帶這顆星魄的那條詞綴（currencies.yml 的 affix；階級照物品等級，不會是最差的）。
     * 細節在 {@link Crafting}；這裡只擋不是普通／魔法的。
     */
    ESSENCE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return (data.rarity() == Rarity.NORMAL || data.rarity() == Rarity.MAGIC) && data.identified() && !data.starter();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("星魄要知道是哪一顆（Crafting.applyEssence）");
        }
    },

    /** 催化劑（PoE2）：飾品的品質 +5（上限 20），品質變成某一類詞綴的加成（currencies.yml 的 catalyst）。細節在 {@link Crafting}。 */
    CATALYST {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && base.isJewellery() && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("催化劑要知道是哪一種（Crafting.applyCatalyst）");
        }
    },

    /** 符文（2026-10 PoE2）：嵌進裝備一個空的符文孔（currencies.yml 的 rune 指到 runes.yml）。細節在 {@link Crafting}。 */
    RUNE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && data.identified() && data.runes().size() < tw.exilecore.item.Runes.slots(generator.repository(), base, data);
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("符文要知道是哪一種（Crafting.applyRune）");
        }
    },

    /** 星籤（PoE2）：掛在一件魔法／稀有物品上，下一顆對應的通貨照星籤的規則用（currencies.yml 的 omen）。細節在 {@link Crafting}。 */
    OMEN {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && !base.isMap() && (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE) && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("星籤要知道是哪一顆（Crafting.attachOmen）");
        }
    },

    /**
     * 蒸餾情緒（PoE2 的 Delirium 獎勵）：拿著它點一條項鍊會打開「注入」的選單，選一個天賦樹的核心，花 3 顆同一種情緒把那個核心的效果注入項鍊
     * （一條項鍊只能注入一個，再注入就換掉）。哪一種情緒看核心離天賦樹中心多遠（{@link Crafting#instilTier}）。細節在 {@link Crafting}。
     */
    INSTIL {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && base.slot() == tw.exilecore.item.ItemSlot.AMULET && data.identified();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("注入要選核心（Crafting.instil）");
        }
    },

    /** 沒有操作：碎片、鑰匙這類收集品（例如天穹碎片，拿去地圖裝置開終局）。 */
    /**
     * 揭露石（2026-10，只在劫盜掉）：在有空位的魔法／稀有物品上加一條隱匿詞綴，之後找盜賊首領揭露（三選一）；
     * 一件只能有一條隱匿或揭露詞綴。見 {@link tw.exilecore.item.Veiled}。
     */
    UNVEIL {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return tw.exilecore.item.Veiled.canUnveil(data, generator);
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            tw.exilecore.item.Veiled.unveilRandom(data, generator, random);
        }
    },

    NONE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return false;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("這個通貨不能用在物品上");
        }
    },

    /**
     * 珠寶匠寶珠（PoE2）：幫技能面板上的技能開一個輔助孔。不是用在裝備上，
     * 拿在游標上點技能面板（/skills）裡的技能才有用（skill 模組處理）。
     */
    SKILL_SOCKET {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return false;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("開輔助孔的通貨要用在技能面板上");
        }
    };

    /** 這個操作現在能不能用在這件物品上（規則面，不含通貨自己的 applies_to）。 */
    public abstract boolean canApply(ItemData data, ItemGenerator generator);

    /** 執行。呼叫前必須先確認 {@link #canApply}。 */
    public abstract void apply(ItemData data, ItemGenerator generator, RandomGenerator random);

    /** 傳奇的掉落來源：可以用僥倖石做出來（uniques.yml 的 sources 寫 chance）。 */
    public static final String CHANCE_SOURCE = "chance";

    /** 這件物品用僥倖石可能變成的傳奇（同一個基底、物品等級夠、sources 有 chance）。 */
    public static java.util.List<tw.exilecore.item.UniqueItem> chanceUniques(final ItemData data, final ItemGenerator generator) {
        final java.util.List<tw.exilecore.item.UniqueItem> list = new java.util.ArrayList<>();
        for (final tw.exilecore.item.UniqueItem unique : generator.repository().uniques()) {
            if (unique.base().equals(data.base()) && unique.droppedBy(CHANCE_SOURCE) && unique.dropLevel() <= data.ilvl()
                    && unique.weight() > 0) {
                list.add(unique);
            }
        }
        return list;
    }

    /** 與 PoE2 相同：不分稀有度每次 +5，上限 20。 */
    static int qualityStep(final ItemData data) {
        return Math.min(5, 20 - data.quality());
    }

    /** 偶爾也需要知道某個型態還有沒有空位（給 GUI 提示用）。 */
    public static boolean hasRoom(final ItemData data, final ItemGenerator generator, final AffixType type) {
        return generator.canAddAffix(data, type);
    }
}
