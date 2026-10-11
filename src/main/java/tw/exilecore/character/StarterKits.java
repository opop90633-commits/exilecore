package tw.exilecore.character;

import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 新手裝備的規則（純邏輯，不依賴 Bukkit）：選完職業後能選哪些武器、送哪些防具與盾、起始技能跟著武器走。
 * <p>
 * 能選的武器 = 物品等級 1 就能掉、需求等級 1、職業的起始屬性（力量／敏捷／智慧）就夠用的所有武器。
 * character.yml 的 starter.classes.&lt;職業&gt;.recommended 照順序排在最前面並標「推薦」，其他照基底檔的順序。
 */
public final class StarterKits {

    /** 新手裝備的物品等級（全部是普通物品）。 */
    public static final int ITEM_LEVEL = 1;

    /** 選單上的一個選項。 */
    public record Option(ItemBase base, boolean recommended) {
    }

    private StarterKits() {
    }

    /** 這個職業能選的新手武器：推薦的在前（照設定的順序），其他照基底的順序。 */
    public static List<Option> weapons(final ItemRepository repository, final CharacterConfig config,
                                       final CharacterClass characterClass) {
        final CharacterConfig.StarterClass starter = config.starter(characterClass);
        final List<Option> options = new ArrayList<>();
        for (final String id : starter.recommended()) {
            final ItemBase base = repository.base(repository.canonicalBase(id)); // 2026-10：合併掉的分階換成合併後的
            if (base != null && isWeapon(base) && usable(repository, base, characterClass) && !contains(options, base)) {
                options.add(new Option(base, true));
            }
        }
        final java.util.Set<String> families = new java.util.HashSet<>();
        for (final Option option : options) {
            families.add(option.base().family());
        }
        for (final ItemBase base : repository.bases()) {
            // 每一類只列一把（2026-10：同一類有好幾種變化，新手選單只放第一種，其他的之後賭、撿）
            if (isWeapon(base) && usable(repository, base, characterClass) && !contains(options, base) && families.add(base.family())) {
                options.add(new Option(base, false));
            }
        }
        return options;
    }

    /** 拿在主手的武器（副手的東西就算有 weapon 標籤也不算）。 */
    private static boolean isWeapon(final ItemBase base) {
        return base.isWeapon() && base.slot() == ItemSlot.MAIN_HAND;
    }

    private static boolean contains(final List<Option> options, final ItemBase base) {
        for (final Option option : options) {
            if (option.base().id().equals(base.id())) {
                return true;
            }
        }
        return false;
    }

    /** 物品等級 1 的這件裝備，剛建立的角色（1 級、只有職業的起始屬性）穿得上嗎。 */
    public static boolean usable(final ItemRepository repository, final ItemBase base, final CharacterClass characterClass) {
        if (base.dropLevel() > ITEM_LEVEL || base.dropWeight() <= 0 || repository.retiredBase(base.id())) {
            return false; // drop_weight: 0 = 拿掉的武器種類（2026-10 PoE2 改版：劍、斧、匕首、爪）
        }
        final ItemBase.Requirements req = ItemStats.requirements(repository, base, ITEM_LEVEL);
        return req.level() <= 1 && req.str() <= characterClass.strength() && req.dex() <= characterClass.dexterity()
                && req.intelligence() <= characterClass.intelligence();
    }

    /**
     * 實際要送的武器：選的那把還能選就用它；不能（例如設定重載後不能選了、沒選）就用第一個選項。
     * 沒有任何武器能選（基底檔壞了）回傳 null：不送武器，起始技能用職業的。
     */
    public static ItemBase resolveWeapon(final ItemRepository repository, final CharacterConfig config,
                                         final CharacterClass characterClass, final String requested) {
        final List<Option> options = weapons(repository, config, characterClass);
        if (requested != null) {
            for (final Option option : options) {
                if (option.base().id().equals(requested)) {
                    return option.base();
                }
            }
        }
        return options.isEmpty() ? null : options.get(0).base();
    }

    /** 一起送的防具（找不到的基底、不是防具、穿不上的略過），照設定的順序。 */
    public static List<ItemBase> armour(final ItemRepository repository, final CharacterConfig config,
                                        final CharacterClass characterClass) {
        final List<ItemBase> list = new ArrayList<>();
        for (final String id : config.starter(characterClass).armour()) {
            final ItemBase base = repository.base(repository.canonicalBase(id)); // 2026-10：合併掉的分階換成合併後的
            if (base != null && base.isArmour() && !base.isShield() && base.slot().isVanilla()
                    && usable(repository, base, characterClass)) {
                list.add(base);
            }
        }
        return list;
    }

    /** 拿弓、弩時送的箭袋（2026-10）。 */
    public static final String STARTER_QUIVER = "quiver_01";

    /** 拿這把武器時送的副手：單手武器送盾，弓、弩送箭袋；沒設定、找不到或穿不上回傳 null。 */
    public static ItemBase shield(final ItemRepository repository, final CharacterConfig config,
                                  final CharacterClass characterClass, final ItemBase weapon) {
        if (weapon != null && weapon.hasTag("crossbow")) {
            return null; // 2026-10：弩不能裝箭袋（照 PoE2）
        }
        if (weapon != null && weapon.hasTag("bow")) {
            final ItemBase quiver = repository.base(STARTER_QUIVER);
            return quiver != null && quiver.isQuiver() && usable(repository, quiver, characterClass) ? quiver : null;
        }
        if (weapon == null || !weapon.hasTag("one_hand")) {
            return null;
        }
        final String id = config.starter(characterClass).shield();
        final ItemBase base = id == null ? null : repository.base(repository.canonicalBase(id));
        if (base == null || !base.isShield() || base.slot() != ItemSlot.OFF_HAND || !usable(repository, base, characterClass)) {
            return null;
        }
        return base;
    }

    /** 這個職業拿這把武器送的起始技能代號；沒有符合的規則回傳 null（改用職業的起始技能）。 */
    public static String skill(final CharacterConfig config, final CharacterClass characterClass, final ItemBase weapon) {
        return weapon == null ? null : config.starterSkill(characterClass, weapon.tags());
    }

    /** character.yml starter 裡對不到的基底（打錯字、職業拿不動…），啟用與重載時提醒。 */
    public static List<String> problems(final ItemRepository repository, final CharacterConfig config) {
        final List<String> problems = new ArrayList<>();
        for (final CharacterClass characterClass : CharacterClass.values()) {
            final CharacterConfig.StarterClass starter = config.starter(characterClass);
            final String where = "character.yml starter.classes." + characterClass.id();
            for (final String id : starter.recommended()) {
                final ItemBase base = repository.base(repository.canonicalBase(id)); // 2026-10：合併掉的分階換成合併後的
                if (base == null || !isWeapon(base)) {
                    problems.add(where + ".recommended：" + id + " 不是武器基底，略過");
                } else if (!usable(repository, base, characterClass)) {
                    problems.add(where + ".recommended：" + characterClass.displayName() + "的起始屬性拿不動 " + id + "，不會出現在選單");
                }
            }
            for (final String id : starter.armour()) {
                final ItemBase base = repository.base(repository.canonicalBase(id));
                if (base == null || !base.isArmour() || base.isShield() || !base.slot().isVanilla()) {
                    problems.add(where + ".armour：" + id + " 不是防具基底（頭、身體、腿、腳），略過");
                } else if (!usable(repository, base, characterClass)) {
                    problems.add(where + ".armour：" + characterClass.displayName() + "的起始屬性穿不上 " + id + "，不會送");
                }
            }
            if (starter.shield() != null) {
                final ItemBase base = repository.base(repository.canonicalBase(starter.shield()));
                if (base == null || !base.isShield() || base.slot() != ItemSlot.OFF_HAND) {
                    problems.add(where + ".shield：" + starter.shield() + " 不是盾的基底，不會送盾");
                } else if (!usable(repository, base, characterClass)) {
                    problems.add(where + ".shield：" + characterClass.displayName() + "的起始屬性拿不動 " + starter.shield() + "，不會送盾");
                }
            }
            if (weapons(repository, config, characterClass).isEmpty()) {
                problems.add(characterClass.displayName() + "沒有任何能選的新手武器（看 bases/weapons.yml 的需求），新角色不會拿到武器");
            }
        }
        return problems;
    }

    /** starter.skills 裡找不到（或是普攻）的技能代號，技能模組啟用與重載時提醒。 */
    public static List<String> skillProblems(final CharacterConfig config, final Predicate<String> usableSkill) {
        final List<String> problems = new ArrayList<>();
        for (final CharacterConfig.StarterSkillRule rule : config.starterSkills()) {
            if (!usableSkill.test(rule.skill())) {
                problems.add("character.yml starter.skills：武器標籤 " + rule.weaponTag() + " 的技能 " + rule.skill()
                        + " 不存在（skills.yml），拿這種武器的新角色改送職業的起始技能");
            }
        }
        return problems;
    }
}
