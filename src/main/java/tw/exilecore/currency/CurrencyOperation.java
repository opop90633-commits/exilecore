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
            return data.rarity() == Rarity.RARE && data.identified() && hasRemovable(data); // 2026-10-21：只剩破裂的詞綴不能用
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
            return (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE) && data.identified() && hasRemovable(data); // 2026-10-21
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
            return data.rarity() == Rarity.RARE && data.identified() && data.fractured() == null; // 2026-10-21：破裂的物品不能整個重骰
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
            // 2026-10-21：破裂的物品不能洗掉（PoE 的規則：破裂的詞綴永遠留著）
            return (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE) && data.identified() && data.fractured() == null;
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
     * 腐化（蝕變石，2026-10-21 照 PoE2 的瓦爾寶珠）：物品變成已腐化（之後只能鑑定、把符文嵌進空的孔），另外各 25% 一種結果：
     * 沒有變化、加一條腐化詞綴（affixes/corrupted.yml，照 PoE2 的腐化附魔；已經有就換一條）、多一個符文孔（可以超過上限；
     * 飾品這種沒有孔的改成加腐化詞綴）、重骰所有詞綴的數值（破裂的不變）。
     * 珠寶照 PoE2：沒有變化、重骰數值、多一條詞綴（可以超過上限）、少一條詞綴，各 25%。藥劑、護符、地圖不能用。
     */
    CORRUPT {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && !base.isMap() && !base.isFlaskLike() && data.identified() && !data.corrupted() && !data.starter()
                    && !data.mirrored();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            data.setCorrupted(true);
            final int outcome = random.nextInt(4);
            final var base = generator.repository().base(data.base());
            if (base != null && base.isJewel()) {
                switch (outcome) {
                    case 0 -> {
                        // 沒有變化
                    }
                    case 1 -> generator.rerollValues(data, random);
                    case 2 -> {
                        if (data.rarity() == Rarity.NORMAL) {
                            data.setRarity(Rarity.MAGIC);
                        }
                        generator.addAffix(data, null, random, true);
                    }
                    default -> generator.removeRandomAffix(data, random);
                }
                return;
            }
            switch (outcome) {
                case 0 -> {
                    // 沒有變化
                }
                case 1 -> generator.addCorruptionEnchant(data, random);
                case 2 -> {
                    if (base != null && tw.exilecore.item.Runes.slots(generator.repository(), base, data) > 0) {
                        data.setCorruptSockets(data.corruptSockets() + 1);
                    } else {
                        generator.addCorruptionEnchant(data, random);
                    }
                }
                default -> generator.rerollValues(data, random);
            }
        }
    },

    /**
     * 定星石（2026-10-21，PoE2 的破裂石）：有 4 條以上詞綴的稀有物品，隨機一條詞綴變成「破裂」：
     * 之後亂流石、湮滅石、神聖石、星籤、星魄、揭露石、蝕變石都動不到它（它會一直留著），物品還是可以繼續改造。一件只能有一條。
     * 隱匿詞綴不會被選到。
     */
    FRACTURE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return data.rarity() == Rarity.RARE && data.identified() && !data.corrupted() && !data.mirrored() && !data.starter()
                    && data.fractured() == null && !fracturable(data).isEmpty() && data.affixes().size() >= MIN_FRACTURE_AFFIXES;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            final java.util.List<tw.exilecore.item.RolledAffix> candidates = fracturable(data);
            data.setFractured(candidates.get(random.nextInt(candidates.size())).id());
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

    /**
     * 映星鏡（2026-10；PoE2 的卡蘭德的魔鏡）：複製一件裝備，原本那件不變，多出來的那件是「鏡像複製品」——
     * 不能再改造（通貨、符文、注入、揭露都不行）、也不能再被複製。傳奇、地圖、藥劑與護符、新手裝備、已腐化、未鑑定的都不能複製。
     * 會多出一件物品，所以不走 {@link #apply}：{@link CurrencyService#mirror} 做出複製品、{@link CurrencyListener} 確認後交給玩家。
     */
    MIRROR {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            final var base = generator.repository().base(data.base());
            return base != null && !base.isMap() && !base.isFlaskLike() && data.identified() && data.rarity() != Rarity.UNIQUE
                    && data.unique() == null && !data.starter() && !data.corrupted() && !data.mirrored();
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("映星鏡會多出一件物品（CurrencyService.mirror）");
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
    },

    /**
     * 共鳴石（2026-10-18）：用在一顆「專屬輔助寶石」（source != null、不能切的那種）上，那顆寶石就「共鳴」——
     * 不佔一個技能只能掛一顆專屬輔助的名額。不是用在裝備上：對背包裡的寶石物品用（CurrencyListener），
     * 或拿在游標上點技能面板（/skills）裡已經掛了專屬輔助的孔（SkillMenu）。
     */
    RESONANCE {
        @Override
        public boolean canApply(final ItemData data, final ItemGenerator generator) {
            return false;
        }

        @Override
        public void apply(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
            throw new UnsupportedOperationException("共鳴石要用在專屬輔助寶石上");
        }
    };

    /** 這個操作現在能不能用在這件物品上（規則面，不含通貨自己的 applies_to）。 */
    public abstract boolean canApply(ItemData data, ItemGenerator generator);

    /** 執行。呼叫前必須先確認 {@link #canApply}。 */
    public abstract void apply(ItemData data, ItemGenerator generator, RandomGenerator random);

    /** 定星石至少要幾條詞綴（PoE2：4 條）。 */
    public static final int MIN_FRACTURE_AFFIXES = 4;

    /** 有沒有通貨拿得掉的詞綴（破裂的不算；2026-10-21）。 */
    static boolean hasRemovable(final ItemData data) {
        for (final tw.exilecore.item.RolledAffix rolled : data.affixes()) {
            if (!data.isFractured(rolled)) {
                return true;
            }
        }
        return false;
    }

    /** 可以被定星石選到的詞綴（隱匿詞綴不算）。 */
    static java.util.List<tw.exilecore.item.RolledAffix> fracturable(final ItemData data) {
        final java.util.List<tw.exilecore.item.RolledAffix> list = new java.util.ArrayList<>();
        for (final tw.exilecore.item.RolledAffix rolled : data.affixes()) {
            if (!tw.exilecore.item.Veiled.isVeiled(rolled.id())) {
                list.add(rolled);
            }
        }
        return list;
    }

    /** 已腐化的物品還能用的（2026-10-21：鑑定、把符文嵌進空的符文孔——PoE2 的腐化物品也能嵌符文）。 */
    public boolean allowedOnCorrupted() {
        return this == IDENTIFY || this == RUNE;
    }

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
