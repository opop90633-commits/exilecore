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

    /** 重骰稀有物品全部詞綴。 */
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
    };

    /** 這個操作現在能不能用在這件物品上（規則面，不含通貨自己的 applies_to）。 */
    public abstract boolean canApply(ItemData data, ItemGenerator generator);

    /** 執行。呼叫前必須先確認 {@link #canApply}。 */
    public abstract void apply(ItemData data, ItemGenerator generator, RandomGenerator random);

    /** 與 PoE 相同：普通物品每次 +5、魔法 +2、稀有與傳奇 +1。 */
    static int qualityStep(final ItemData data) {
        return switch (data.rarity()) {
            case NORMAL -> 5;
            case MAGIC -> 2;
            default -> 1;
        };
    }

    /** 偶爾也需要知道某個型態還有沒有空位（給 GUI 提示用）。 */
    public static boolean hasRoom(final ItemData data, final ItemGenerator generator, final AffixType type) {
        return generator.canAddAffix(data, type);
    }
}
