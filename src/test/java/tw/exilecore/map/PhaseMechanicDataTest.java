package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：出貨的 maps.yml 每隻 Boss 每個階段的鎖血機制都讀得到，而且每隻至少兩種。 */
class PhaseMechanicDataTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MapConfig config;
    static tw.exilecore.life.LifeConfig life;
    static tw.exilecore.heist.HeistConfig heist;

    @BeforeAll
    static void load() throws IOException {
        config = new MapConfig();
        config.load(DATA);
        life = new tw.exilecore.life.LifeConfig();
        life.load(DATA);
        heist = new tw.exilecore.heist.HeistConfig();
        heist.load(tw.exilecore.core.config.YamlNode.load(DATA.resolve(tw.exilecore.heist.HeistConfig.FILE)));
    }

    /** 一隻 Boss：名字、輪流的鍵、階段、要不要輪流、是不是極境巔峰。 */
    private record Entry(String label, String key, MapConfig.Boss boss, boolean auto, boolean heavy) {
    }

    private static List<Entry> endgame() {
        final List<Entry> list = new ArrayList<>();
        for (final MapConfig.MapDef map : config.finals()) {
            final boolean pinnacle = config.pinnacle(map.id()) != null;
            list.add(new Entry(map.id(), PhaseMechanic.key(map.id(), false), map.boss(), true, pinnacle));
            final MapConfig.Uber uber = config.uber(map.id());
            if (uber != null) {
                list.add(new Entry(map.id() + "（極境）", PhaseMechanic.key(map.id(), true), uber.merge(map.boss()), true, true));
            }
        }
        // 2026-11-02 照站長：金庫之主、星骸之海的淵鯨與渦齒鯊王也每 25% 鎖一次血（鍵跟 MapBosses、LifeWhale、LifeExtras 用的一樣）
        list.add(new Entry("金庫之主", "heist_finale", heist.finale().boss(), true, true));
        list.add(new Entry("淵鯨", "whale", lifeBoss(life.whale().name(), tw.exilecore.life.LifeConfig.whaleLockPhases(life.whale().phases())), true, true));
        list.add(new Entry("渦齒鯊王", "shark_king", lifeBoss(life.sharkKing().name(),
                tw.exilecore.life.LifeConfig.kingLockPhases(life.sharkKing().phases())), true, true));
        return list;
    }

    private static MapConfig.Boss lifeBoss(final String name, final List<MapConfig.Phase> phases) {
        return new MapConfig.Boss(name, "ZOMBIE", null, true, 1, 1, 1, List.of(), null, null, phases);
    }

    @Test
    void endgameBossesLockEveryQuarter() {
        for (final Entry entry : endgame()) {
            final List<Double> ats = entry.boss().phases().stream().map(MapConfig.Phase::at).toList();
            assertEquals(List.of(0.75, 0.5, 0.25), ats, entry.label() + "：每 25% 一個階段");
        }
    }

    @Test
    void everyEndgameBossPhaseHasAMechanic() {
        final List<Entry> bosses = endgame();
        assertTrue(bosses.size() >= 19, "終局 4、極境 4、萬界之巔、事件巔峰 7、金庫之主、淵鯨、鯊王：" + bosses.size());
        final StringBuilder table = new StringBuilder();
        int countdowns = 0;
        int zones = 0;
        int elements = 0;
        for (final Entry entry : bosses) {
            final List<MapConfig.Phase> phases = entry.boss().phases();
            final List<PhaseMechanic.Spec> specs = PhaseMechanic.resolve(entry.key(), phases, entry.auto(), entry.heavy());
            assertEquals(phases.size(), specs.size(), entry.label());
            final Set<PhaseMechanic.Kind> kinds = EnumSet.noneOf(PhaseMechanic.Kind.class);
            table.append(entry.label()).append("：");
            int locks = 0;
            for (int i = 0; i < specs.size(); i++) {
                final PhaseMechanic.Spec spec = specs.get(i);
                if (spec == null) {
                    table.append(i == 0 ? "" : "、").append(Math.round(phases.get(i).at() * 100)).append("% 不鎖");
                    continue; // 2026-11-04：一隻 Boss 只留一個鎖血，其他階段不鎖
                }
                locks++;
                kinds.add(spec.kind());
                // 數值跟結晶同級（限時 5～120 秒、回血 0～100%）
                assertTrue(spec.seconds() >= 3 && spec.seconds() <= 120, entry.label() + " 第 " + (i + 1) + " 階段限時 " + spec.seconds());
                assertTrue(spec.heal() >= 0 && spec.heal() <= 100);
                if (spec.kind() == PhaseMechanic.Kind.COUNTDOWN) {
                    countdowns++;
                    assertTrue(spec.countdown().damage() > 0 && spec.countdown().damage() < 1, "倒數大招不會滿血秒殺");
                }
                zones += spec.kind() == PhaseMechanic.Kind.ZONES ? 1 : 0;
                elements += spec.kind() == PhaseMechanic.Kind.ELEMENT ? 1 : 0;
                table.append(i == 0 ? "" : "、").append(Math.round(phases.get(i).at() * 100)).append("% ").append(spec.kind().label());
            }
            table.append('\n');
            // 2026-11-04 照站長：鎖血只剩破綻；2026-11-06 照站長「破綻改成每階段 1 個」：每個階段都有（淵鯨、鯊王也一起）；這次之前一隻 Boss 最多一個
            assertEquals(specs.size(), locks, entry.label() + " 每個階段一個破綻，實際 " + locks + "：" + kinds);
            assertTrue(kinds.contains(PhaseMechanic.Kind.OPENING), entry.label() + "：鎖血要是破綻：" + kinds);
            assertFalse(kinds.contains(PhaseMechanic.Kind.CRYSTAL), entry.label() + "：結晶退役");
            assertFalse(kinds.contains(PhaseMechanic.Kind.ZONES) || kinds.contains(PhaseMechanic.Kind.COUNTDOWN) || kinds.contains(PhaseMechanic.Kind.ELEMENT),
                    entry.label() + "：站位圈、倒數大招、元素弱點拿掉");
            for (final PhaseMechanic.Spec spec : specs) {
                if (spec != null && spec.kind() == PhaseMechanic.Kind.OPENING) {
                    assertEquals(15, spec.heal(), 1e-9, entry.label() + "：破綻沒剝完回 15%（2026-11-04 照站長；這次之前 30～45%），實際 " + spec.heal());
                }
            }
        }
        System.out.println(table);
        assertEquals(0, elements, "2026-11-02 照站長：元素弱點拿掉（不是每個人都有元素傷害）");
    }

    @Test
    void ordinaryMapBossesStayAsWritten() {
        for (final MapConfig.MapDef map : config.maps()) {
            if (map.boss() == null) {
                continue;
            }
            final List<PhaseMechanic.Spec> specs = PhaseMechanic.resolve(PhaseMechanic.key(map.id(), false), map.boss().phases(), false, false);
            for (int i = 0; i < specs.size(); i++) {
                final MapConfig.Phase phase = map.boss().phases().get(i);
                // 一般地圖不輪流：沒寫的不鎖，寫了的照寫的
                if (phase.mechanic() == null && phase.shield() == null) {
                    assertEquals(null, specs.get(i), map.id());
                } else {
                    assertNotNull(specs.get(i), map.id());
                }
            }
        }
        assertFalse(config.maps().isEmpty());
    }
}
