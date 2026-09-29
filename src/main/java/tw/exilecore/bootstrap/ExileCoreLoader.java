package tw.exilecore.bootstrap;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jspecify.annotations.NullMarked;

/**
 * 在插件載入前，把執行期需要的第三方函式庫下載到伺服器的 libraries/ 資料夾。
 * <p>
 * 這是 Paper 建議的做法：jar 不用打包（shade）任何東西，
 * 版本只要改這裡，重啟伺服器就會自動下載新版。
 */
@NullMarked
public final class ExileCoreLoader implements PluginLoader {

    /** 資料庫連線池。 */
    private static final String HIKARI = "com.zaxxer:HikariCP:7.1.0";
    /** 開發期用的單機檔案資料庫。 */
    private static final String H2 = "com.h2database:h2:2.4.240";
    /** 正式環境的 MariaDB 驅動（也能連 MySQL）。 */
    private static final String MARIADB = "org.mariadb.jdbc:mariadb-java-client:3.5.10";

    @Override
    public void classloader(final PluginClasspathBuilder classpathBuilder) {
        final MavenLibraryResolver resolver = new MavenLibraryResolver();

        // 直接用 Maven Central 會被 Paper 警告（違反其服務條款、容易被限流），
        // 所以用 Paper 提供的鏡像常數；可用環境變數 PAPER_DEFAULT_CENTRAL_REPOSITORY 覆寫。
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        resolver.addDependency(new Dependency(new DefaultArtifact(HIKARI), null));
        resolver.addDependency(new Dependency(new DefaultArtifact(H2), null));
        resolver.addDependency(new Dependency(new DefaultArtifact(MARIADB), null));

        classpathBuilder.addLibrary(resolver);
    }
}
