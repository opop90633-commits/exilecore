package tw.exilecore.flask;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * data/flasks.yml：藥劑欄、充能、補滿的規則與藥劑基底。純資料，不依賴 Bukkit。
 */
public final class FlaskConfig {

    public static final String FILE = "flasks.yml";

    /** 掉落時從最高的幾種基底裡挑（太低級的藥劑掉了也沒用）。 */
    static final int DROP_CHOICES = 3;

    private Map<String, FlaskBase> bases = Map.of();
    /** 藥劑欄：快捷欄的索引（0 = 數字鍵 1）；第一格是生命藥劑欄、第二格是魔力藥劑欄（照 PoE2）。 */
    private int[] slots = {0, 1};
    private final Map<MonsterRarity, Integer> chargesPerKill = new EnumMap<>(MonsterRarity.class);
    private int checkpointRefillSeconds = 60;
    private int useCooldownTicks = 8;
    private List<String> starter = List.of();

    public FlaskConfig() {
        chargesPerKill.put(MonsterRarity.NORMAL, 1);
        chargesPerKill.put(MonsterRarity.MAGIC, 2);
        chargesPerKill.put(MonsterRarity.RARE, 5);
        chargesPerKill.put(MonsterRarity.UNIQUE, 10);
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IOException(FILE + " 不存在");
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode settings = root.node("settings");
        final List<String> slotTexts = settings.strings("slots");
        final int[] parsedSlots = slotTexts.isEmpty() ? new int[] {1, 2} : new int[slotTexts.size()];
        for (int i = 0; i < slotTexts.size(); i++) {
            try {
                parsedSlots[i] = Integer.parseInt(slotTexts.get(i).trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(FILE + ".settings.slots：要寫快捷欄的數字 1 到 9，卻是「" + slotTexts.get(i) + "」");
            }
        }
        if (parsedSlots.length != 2 || parsedSlots[0] == parsedSlots[1]) {
            throw new IllegalArgumentException(FILE + ".settings.slots：要寫兩格不一樣的快捷欄，第一格放生命藥劑、第二格放魔力藥劑，"
                    + "例如 [1, 2]");
        }
        for (int i = 0; i < parsedSlots.length; i++) {
            if (parsedSlots[i] < 1 || parsedSlots[i] > 8) {
                throw new IllegalArgumentException(FILE + ".settings.slots：要在 1 到 8 之間（至少留一格放武器）");
            }
            parsedSlots[i]--;
        }
        final YamlNode perKill = settings.node("charges_per_kill");
        for (final String key : perKill.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(perKill.path() + "：不認得的怪物稀有度 " + key);
            }
            chargesPerKill.put(rarity, Math.max(0, perKill.integer(key, 0)));
        }
        checkpointRefillSeconds = Math.max(0, settings.integer("checkpoint_refill_seconds", checkpointRefillSeconds));
        useCooldownTicks = Math.max(0, settings.integer("use_cooldown_ticks", useCooldownTicks));

        final Map<String, FlaskBase> parsed = new LinkedHashMap<>();
        final YamlNode section = root.node("bases");
        for (final String id : section.keys()) {
            parsed.put(id, FlaskBase.parse(id, section.node(id)));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(FILE + "：至少要有一種藥劑基底");
        }
        final List<String> parsedStarter = settings.strings("starter");
        for (final String id : parsedStarter) {
            if (!parsed.containsKey(id)) {
                throw new IllegalArgumentException(FILE + ".settings.starter：沒有這種藥劑 " + id);
            }
        }
        this.slots = parsedSlots;
        this.bases = parsed;
        this.starter = List.copyOf(parsedStarter);
    }

    public FlaskBase base(final String id) {
        return id == null ? null : bases.get(id);
    }

    public Collection<FlaskBase> bases() {
        return bases.values();
    }

    /** 藥劑欄（快捷欄索引，0 = 數字鍵 1）。 */
    public int[] slots() {
        return slots.clone();
    }

    /** 這種藥劑放哪一格（快捷欄索引）。 */
    public int slotFor(final FlaskKind kind) {
        return kind == FlaskKind.LIFE ? slots[0] : slots[1];
    }

    /** 這一格是哪一種藥劑的藥劑欄（不是藥劑欄回傳 null）。 */
    public FlaskKind kindAt(final int hotbarIndex) {
        if (hotbarIndex == slots[0]) {
            return FlaskKind.LIFE;
        }
        return hotbarIndex == slots[1] ? FlaskKind.MANA : null;
    }

    public boolean isFlaskSlot(final int hotbarIndex) {
        for (final int slot : slots) {
            if (slot == hotbarIndex) {
                return true;
            }
        }
        return false;
    }

    /** 第一個不是藥劑欄的快捷欄格子（新角色的武器放這裡）。 */
    public int firstWeaponSlot() {
        for (int i = 0; i < 9; i++) {
            if (!isFlaskSlot(i)) {
                return i;
            }
        }
        return 8;
    }

    public int chargesPerKill(final MonsterRarity rarity) {
        return chargesPerKill.getOrDefault(rarity, 0);
    }

    public int checkpointRefillSeconds() {
        return checkpointRefillSeconds;
    }

    public int useCooldownTicks() {
        return useCooldownTicks;
    }

    /** 新角色拿到的藥劑（依序放進藥劑欄）。 */
    public List<String> starter() {
        return starter;
    }

    /**
     * 怪物掉落的藥劑：生命、魔力各半，從需求等級 ≤ 怪物等級的基底裡最高的幾種挑一種。
     * 沒有可以掉的回傳 null。
     */
    public FlaskBase rollDrop(final int monsterLevel, final RandomGenerator random) {
        final FlaskKind kind = random.nextBoolean() ? FlaskKind.LIFE : FlaskKind.MANA;
        final List<FlaskBase> eligible = new ArrayList<>();
        for (final FlaskBase base : bases.values()) {
            if (base.kind() == kind && base.level() <= monsterLevel) {
                eligible.add(base);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        eligible.sort((a, b) -> Integer.compare(b.level(), a.level()));
        return eligible.get(random.nextInt(Math.min(DROP_CHOICES, eligible.size())));
    }
}
