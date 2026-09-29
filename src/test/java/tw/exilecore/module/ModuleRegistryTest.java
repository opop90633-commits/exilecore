package tw.exilecore.module;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleRegistryTest {

    /** 記錄自己被呼叫的順序，用來驗證註冊表的行為。 */
    private static final class RecordingModule implements Module {
        private final String id;
        private final boolean failOnEnable;
        private final List<String> events;

        RecordingModule(final String id, final boolean failOnEnable, final List<String> events) {
            this.id = id;
            this.failOnEnable = failOnEnable;
            this.events = events;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void enable() throws Exception {
            if (failOnEnable) {
                throw new IllegalStateException("故意失敗");
            }
            events.add("enable:" + id);
        }

        @Override
        public void disable() {
            events.add("disable:" + id);
        }

        @Override
        public void reload() {
            events.add("reload:" + id);
        }
    }

    private static ModuleRegistry registry() {
        return new ModuleRegistry(LoggerFactory.getLogger(ModuleRegistryTest.class));
    }

    @Test
    void enablesInRegistrationOrderAndDisablesInReverse() {
        final List<String> events = new ArrayList<>();
        final ModuleRegistry registry = registry()
                .register(new RecordingModule("core", false, events))
                .register(new RecordingModule("stats", false, events))
                .register(new RecordingModule("item", false, events));

        registry.enableAll();
        registry.disableAll();

        assertEquals(List.of(
                "enable:core", "enable:stats", "enable:item",
                "disable:item", "disable:stats", "disable:core"), events);
    }

    @Test
    void aFailingModuleIsSkippedButOthersStillRun() {
        final List<String> events = new ArrayList<>();
        final ModuleRegistry registry = registry()
                .register(new RecordingModule("core", false, events))
                .register(new RecordingModule("broken", true, events))
                .register(new RecordingModule("item", false, events));

        registry.enableAll();

        assertEquals(List.of("core", "item"), registry.enabledIds());

        registry.reloadAll();
        registry.disableAll();

        assertEquals(List.of(
                "enable:core", "enable:item",
                "reload:core", "reload:item",
                "disable:item", "disable:core"), events);
    }

    @Test
    void duplicateIdsAreRejected() {
        final List<String> events = new ArrayList<>();
        final ModuleRegistry registry = registry().register(new RecordingModule("core", false, events));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new RecordingModule("core", false, events)));
        assertEquals(1, registry.size());
    }
}
