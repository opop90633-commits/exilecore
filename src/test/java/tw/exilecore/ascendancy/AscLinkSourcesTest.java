package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 連線提示：接在好幾個天賦後面的天賦（儀祭者的展露之指）要把起點全部列出來並註明「任一」。 */
class AscLinkSourcesTest {
    @Test
    void ritualistFingerListsAllThreeNegatives() throws IOException {
        final AscendancyConfig config = new AscendancyConfig();
        config.load(Path.of("src/main/resources/data"));
        final AscendancyConfig.Ascendancy ritualist = config.ascendancy("ritualist");
        final AscendancyConfig.Node finger = ritualist.node("unfurled_finger");
        final String text = AscendancyLayout.sources(ritualist, ritualist.node("reduced_spirit"), finger);
        assertEquals("「" + ritualist.node("reduced_spirit").name() + "／" + ritualist.node("reduced_resistances").name() + "／"
                + ritualist.node("reduced_mana").name() + "」（任一）", text);
        final AscendancyConfig.Node attributes = ritualist.node("attributes");
        assertEquals("「" + finger.name() + "」", AscendancyLayout.sources(ritualist, finger, attributes));
    }
}
