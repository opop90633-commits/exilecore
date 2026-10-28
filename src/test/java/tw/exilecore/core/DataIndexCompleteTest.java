package tw.exilecore.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-10-28：data/ 底下伺服器要讀的檔案都要列在 data/index.txt，不然不會複製到 plugins/ExileCore/data/，
 * 伺服器上就等於沒有這個檔（這次之前 gem_quality.yml、affixes/essences.yml 沒列：寶石品質效果、星魄專屬詞綴在伺服器上都沒有作用）。
 * 直接從 jar 讀的（不是可調的設定檔）列在 FROM_JAR。
 */
class DataIndexCompleteTest {

    private static final Set<String> FROM_JAR = Set.of("data/passives/anoint_only.yml", "data/passives/timeless.json");

    @Test
    void everyDataFileIsInTheIndex() throws IOException {
        final Path resources = Path.of("src/main/resources");
        final List<String> index = Files.readAllLines(resources.resolve("data/index.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        final Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(resources.resolve("data"))) {
            files.filter(Files::isRegularFile)
                    .map(file -> resources.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> name.endsWith(".yml") || name.endsWith(".json"))
                    .filter(name -> !FROM_JAR.contains(name) && !index.contains(name))
                    .forEach(missing::add);
        }
        assertEquals(Set.of(), missing, "data/index.txt 少了這些檔（伺服器上不會有）");
    }
}
