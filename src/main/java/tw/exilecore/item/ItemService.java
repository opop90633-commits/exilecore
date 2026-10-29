package tw.exilecore.item;

import org.bukkit.inventory.ItemStack;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 其他模組操作裝備的唯一入口：建立、讀取、改完資料後寫回並重繪。
 */
public final class ItemService {

    private final ItemRepository repository;
    private final ItemGenerator generator;
    private final ItemCodec codec;
    private final ItemRenderer renderer;
    private final RandomGenerator random;
    private ItemComparer comparer;

    public ItemService(final ItemRepository repository, final ItemGenerator generator, final ItemCodec codec,
                       final ItemRenderer renderer, final RandomGenerator random) {
        this.repository = repository;
        this.generator = generator;
        this.codec = codec;
        this.renderer = renderer;
        this.random = random;
    }

    public ItemRepository repository() {
        return repository;
    }

    public ItemGenerator generator() {
        return generator;
    }

    public RandomGenerator random() {
        return random;
    }

    /** 生成並做成物品。 */
    public ItemStack create(final ItemBase base, final Rarity rarity, final int itemLevel) {
        return toStack(generator.generate(base, rarity, itemLevel, random));
    }

    /** 新手裝備：普通、指定物品等級，標成新手裝備（拆解沒有東西）。 */
    public ItemStack createStarter(final ItemBase base, final int itemLevel) {
        final ItemData data = generator.generate(base, Rarity.NORMAL, itemLevel, random);
        data.markStarter();
        return toStack(data);
    }

    /** 把資料做成新的 ItemStack。 */
    public ItemStack toStack(final ItemData data) {
        final ItemStack stack = renderer.create(data);
        write(stack, data);
        return stack;
    }

    /** 寫進 PDC；傳奇順便記下目前的定義指紋（剛做出來的就是新版，不用再檢查）。 */
    private void write(final ItemStack stack, final ItemData data) {
        // 2026-10-26：寫進去之前照目前的詞綴資料換算、記下每條的範圍（之後資料改版才換得回同一個位置），蓋上詞綴資料的指紋
        try {
            AffixRescale.apply(repository, data);
        } catch (final RuntimeException ignored) {
            // 換算不了就原樣寫
        }
        codec.write(stack, data);
        codec.stampAffixRevision(stack, repository.affixRevision());
        if (data.rarity() == Rarity.UNIQUE) {
            codec.stampUniqueRevision(stack, repository.uniqueRevision());
        }
    }

    public boolean isEquipment(final ItemStack stack) {
        return codec.isEquipment(stack);
    }

    /**
     * 讀物品資料；舊版的傳奇（uniques.yml 改過）照目前的定義補正，詞綴後來多出來的變數補上（{@link AffixUpgrade}）
     * （只改讀出來的資料，{@link #refresh} 才寫回物品）。
     */
    public Optional<ItemData> read(final ItemStack stack) {
        // 2026-10-26：詞綴資料改版過（指紋不一樣）的，詞綴照比例換到新範圍、拿掉的換成同類的（AffixRescale）
        final int revision = repository.affixRevision();
        final boolean rescale = !Integer.valueOf(revision).equals(codec.affixRevision(stack));
        if (!rescale) {
            return upgrade(codec.read(stack), false);
        }
        // 2026-10-27 效能：還沒 refresh 寫回的舊物品每次讀都要重新換算；同一份資料＋同一個指紋換算結果一樣（種子固定），記起來
        final String json = codec.rawJson(stack);
        if (json == null) {
            return Optional.empty();
        }
        final String key = revision + "\n" + repository.uniqueRevision() + "\n" + json;
        final String cached;
        synchronized (rescaled) {
            cached = rescaled.get(key);
        }
        if (cached != null) {
            return codec.parse(cached);
        }
        final Optional<ItemData> data = upgrade(codec.parse(json), true);
        data.ifPresent(value -> {
            synchronized (rescaled) {
                rescaled.put(key, codec.toJson(value));
            }
        });
        return data;
    }

    /** 2026-10-27：換算過的舊物品資料（原本的 JSON＋指紋 → 換算後的 JSON），最多記 512 件，最舊的先丟。 */
    private final java.util.Map<String, String> rescaled = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final java.util.Map.Entry<String, String> eldest) {
            return size() > 512;
        }
    };

    private Optional<ItemData> upgrade(final Optional<ItemData> data, final boolean rescale) {
        data.ifPresent(value -> {
            BaseUpgrade.apply(repository, value); // 2026-10-29：舊的長杖、法杖、權杖換成照 PoE2 的施法基底（最高階）
            UniqueUpgrade.apply(repository, value);
            if (rescale) {
                try {
                    AffixRescale.apply(repository, value);
                } catch (final RuntimeException ignored) {
                    // 換算不了就照原本的
                }
            }
            AffixUpgrade.apply(repository, value);
        });
        return data;
    }

    /**
     * 2026-10-27 效能：這件裝備的基底（只讀 PDC 的基底代號，不解整份 JSON、不做詞綴換算）；舊物品沒記基底的才整份讀。
     * 天賦樹條件（拿斧頭、拿盾、雙持…）每次判斷都要看，這次之前每次都整份讀。
     */
    public ItemBase base(final ItemStack stack) {
        final String id = codec.baseId(stack);
        if (id != null) {
            return repository.base(id);
        }
        return read(stack).map(data -> repository.base(data.base())).orElse(null);
    }

    /** 2026-10-27 效能：PDC 裡原樣的資料 JSON（不解析；拿來算指紋）；不是裝備回傳 null。 */
    public String rawJson(final ItemStack stack) {
        return codec.rawJson(stack);
    }

    /** 資料改了以後：寫回 PDC 並重繪外觀。 */
    public void update(final ItemStack stack, final ItemData data) {
        write(stack, data);
        renderer.render(stack, data);
    }

    /** 物品的畫法（通貨模組接上星籤的名稱）。 */
    public ItemRenderer renderer() {
        return renderer;
    }

    /** 只重畫外觀（資料不動）：拿掉比較那幾行之類的。 */
    public void rerender(final ItemStack stack, final ItemData data) {
        renderer.render(stack, data);
    }

    /** 裝備比較（背包裡的裝備跟身上的比）；還沒接上時 null。 */
    public ItemComparer comparer() {
        return comparer;
    }

    /** 傳奇兌換券的通貨代號（currencies.yml；2026-10 PoE2 改版：退役的傳奇換成這個）。 */
    public static final String VOUCHER_ID = "unique_voucher";
    private java.util.function.Supplier<ItemStack> voucherFactory;

    void setVoucherFactory(final java.util.function.Supplier<ItemStack> factory) {
        this.voucherFactory = factory;
    }

    /** 一張傳奇兌換券（通貨模組沒啟用、currencies.yml 沒有這種通貨回傳 null）。 */
    public ItemStack voucher() {
        return voucherFactory == null ? null : voucherFactory.get();
    }

    /** 這件是退役的傳奇（2026-10 PoE2 改版）嗎。 */
    public boolean isRetiredUnique(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !codec.isEquipment(stack) || !codec.isUnique(stack)) {
            return false;
        }
        final Optional<ItemData> data = codec.read(stack);
        return data.isPresent() && repository.retiredUnique(data.get().unique());
    }

    /** 退役的傳奇換成的傳奇兌換券；不是退役傳奇（或兌換券做不出來）回傳 null。 */
    public ItemStack retiredReplacement(final ItemStack stack) {
        if (!isRetiredUnique(stack)) {
            return null;
        }
        // 2026-10-28 照站長：刪除的傳奇直接拿掉（換成空格），不給兌換券
        final Optional<ItemData> data = codec.read(stack);
        return data.isPresent() && repository.deletedUnique(data.get().unique()) ? ItemStack.empty() : voucher();
    }

    /** 陣列裡的退役傳奇原地換成傳奇兌換券（倉庫、隨身背包、離線資料）；回傳換了幾件。 */
    public int replaceRetired(final ItemStack[] stacks) {
        int count = 0;
        for (int i = 0; i < stacks.length; i++) {
            final ItemStack voucher = retiredReplacement(stacks[i]);
            if (voucher != null) {
                stacks[i] = voucher;
                count++;
            }
        }
        return count;
    }

    void setComparer(final ItemComparer comparer) {
        this.comparer = comparer;
    }

    /**
     * 外觀是舊版畫的（{@link ItemRenderer#RENDER_VERSION}）就照目前的資料重畫；舊版的傳奇（uniques.yml 改過）照目前的定義補正、
     * 寫回並重畫（{@link UniqueUpgrade}）。回傳有沒有改。不是我們的裝備、資料讀不出來、基底不見了都不動。
     */
    public boolean refresh(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !codec.isEquipment(stack) || isRetiredUnique(stack)) {
            return false;
        }
        final Integer version = stack.getPersistentDataContainer().get(ItemRenderer.RENDER_KEY, org.bukkit.persistence.PersistentDataType.INTEGER);
        final boolean oldRender = version == null || version < ItemRenderer.RENDER_VERSION;
        // 傳奇：uniques.yml 改過（指紋不一樣）才解開來檢查
        final boolean checkUnique = codec.isUnique(stack) && !Integer.valueOf(repository.uniqueRevision()).equals(codec.uniqueRevision(stack));
        // 2026-10-26：詞綴資料改過（指紋不一樣，或這次之前做的舊物品）才解開來換算（AffixRescale），換完寫回、記下指紋
        final boolean checkAffixes = !Integer.valueOf(repository.affixRevision()).equals(codec.affixRevision(stack));
        final Optional<ItemData> data = codec.read(stack);
        if (data.isEmpty() || repository.base(data.get().base()) == null) {
            return false;
        }
        // 2026-10-17：舊 bug 揭露出兩條同一種的：第二條換回隱匿詞綴（重新揭露）
        try {
            if (Veiled.dedupe(data.get(), repository, random())) {
                codec.write(stack, data.get());
                renderer.render(stack, data.get());
                return true;
            }
        } catch (final RuntimeException ignored) {
            // 畫不出來就原樣不動
        }
        // 2026-10-29：舊的長杖、法杖、權杖（合併到固定等級的施法基底）換成新基底，寫回、照新基底重畫
        try {
            if (BaseUpgrade.apply(repository, data.get())) {
                UniqueUpgrade.apply(repository, data.get());
                AffixUpgrade.apply(repository, data.get());
                codec.write(stack, data.get());
                renderer.render(stack, data.get());
                return true;
            }
        } catch (final RuntimeException ignored) {
            // 畫不出來就原樣不動
        }
        if (!oldRender && !checkUnique && !checkAffixes) {
            // 2026-10-16：外觀是新版畫的，但說明跟資料對不上（沒跟著更新）也重畫；登入、打開倉庫時都會檢查。
            // 先補詞綴後來多出來的變數；還是畫不出來就原樣不動（不能讓整個倉庫、市集因為一件裝備起不來）
            try {
                final boolean upgraded = AffixUpgrade.apply(repository, data.get());
                if (!upgraded && !renderer.outdated(stack, data.get())) {
                    return false;
                }
                renderer.render(stack, data.get());
                if (upgraded) {
                    codec.write(stack, data.get());
                }
                return true;
            } catch (final RuntimeException e) {
                return false;
            }
        }
        // 2026-10：詞綴後來多出來的變數也補上（不補的話重畫時丟「缺少變數」，整個倉庫被當成資料損毀）
        boolean upgraded = UniqueUpgrade.apply(repository, data.get()); // 傳奇先照 uniques.yml 對好（換了順序的照 legacy 找回舊的那條）
        if (checkAffixes) {
            try {
                upgraded |= AffixRescale.apply(repository, data.get());
            } catch (final RuntimeException e) {
                // 換算不了就照原本的（下次資料改版再試）
            }
        }
        upgraded |= AffixUpgrade.apply(repository, data.get());
        if (upgraded) {
            codec.write(stack, data.get());
        }
        if (checkUnique) {
            codec.stampUniqueRevision(stack, repository.uniqueRevision());
        }
        if (checkAffixes) {
            codec.stampAffixRevision(stack, repository.affixRevision());
        }
        if (oldRender || upgraded) {
            renderer.render(stack, data.get());
        }
        return true;
    }

    /** 這件物品給屬性表的修飾器（未鑑定只有基底數值）。 */
    public List<Modifier> modifiers(final ItemStack stack) {
        return read(stack).map(data -> ItemStats.modifiers(repository, data)).orElse(List.of());
    }

    public String toJson(final ItemData data) {
        return codec.toJson(data);
    }
}
