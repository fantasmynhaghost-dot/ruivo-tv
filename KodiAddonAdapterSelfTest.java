import br.sintonia.tv.addons.KodiAddonAdapter;
import br.sintonia.tv.addons.RuivoAddonProvider;
import br.sintonia.tv.addons.RuivoContentItem;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Portable assertions for the pure-JVM static analyzer; no Python addon is executed. */
public final class KodiAddonAdapterSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testStaticKodiAnalysisAndNoExecution();
        testOnlyAuthorizedCompiledProviderCanAdapt();
        testSeriesEpisodesProjectToExistingCatalog();
        testZipTraversalAndAmbiguousManifestRejected();
        testExternalEntitiesRejected();
        testCanonicalPlaybackRequiresAuthorization();
        System.out.println("KodiAddonAdapterSelfTest: " + checks + " checks passed");
    }

    private static void testStaticKodiAnalysisAndNoExecution() throws Exception {
        Path marker = Files.createTempDirectory("ruivo-kodi-test").resolve("must-not-exist.txt");
        String python = "import xbmc\nimport xbmcplugin\n" +
                "def fake():\n    open('" + marker.toString().replace("\\", "\\\\").replace("'", "") + "','w').write('ran')\n" +
                "    exec('pass')\n";
        byte[] archive = zip(entries(
                "plugin.video.sample/addon.xml", validManifest("plugin.video.sample"),
                "plugin.video.sample/default.py", python,
                "plugin.video.sample/resources/settings.xml", "<settings/>"));
        KodiAddonAdapter.Analysis result = new KodiAddonAdapter().analyze(archive);
        check("plugin.video.sample".equals(result.addon.id), "addon id extracted");
        check("1.2.3".equals(result.addon.version), "version extracted");
        check(result.addon.dependencies.size() == 1 && result.addon.dependencies.get(0).contains("script.module.requests"), "dependencies extracted");
        check(result.addon.extensionPoints.contains("xbmc.python.pluginsource"), "extension point extracted");
        check(result.kodiApis.contains("xbmc") && result.kodiApis.contains("xbmcplugin"), "Kodi API references detected");
        check(result.dynamicCodeDetected, "dynamic execution pattern flagged");
        check(KodiAddonAdapter.STATUS_INCOMPATIBLE.equals(result.status), "no registered adapter remains incompatible");
        check(!result.permissionsGranted, "analysis grants no permissions");
        check(!Files.exists(marker), "Python source was not executed");
    }

    private static void testOnlyAuthorizedCompiledProviderCanAdapt() throws Exception {
        RuivoAddonProvider provider = new RuivoAddonProvider() {
            public String getAdapterId() { return "ruivo.adapter.sample"; }
            public String getKodiAddonId() { return "plugin.video.sample"; }
            public String getAuthorizationEvidence() { return "https://example.org/published-api-and-permission"; }
            public SourceAuthorization getSourceAuthorization() { return SourceAuthorization.DOCUMENTED_AND_AUTHORIZED; }
            public List<RuivoContentItem> adaptCatalog(List<Map<String, Object>> sourceItems) {
                return Collections.singletonList(new RuivoContentItem(
                        "sample:film-1", "Filme autorizado", RuivoContentItem.Type.FILM,
                        "Sinopse", "https://images.example.org/poster.jpg", "https://images.example.org/cover.jpg",
                        2024, Collections.singletonList("Drama"), Collections.<Integer>emptyList(),
                        Collections.<RuivoContentItem.Episode>emptyList(), "", 0, 0,
                        "https://media.example.org/film.m3u8", true));
            }
        };
        KodiAddonAdapter adapter = new KodiAddonAdapter(Collections.singletonList(provider));
        KodiAddonAdapter.Analysis analysis = adapter.analyze(zip(entries(
                "plugin.video.sample/addon.xml", validManifest("plugin.video.sample"),
                "plugin.video.sample/default.py", "import xbmc\n")));
        check(KodiAddonAdapter.STATUS_ADAPTED.equals(analysis.status), "registered and authorized provider is adapted");
        check("ruivo.adapter.sample".equals(analysis.adapterId), "adapter id included");
        List<RuivoContentItem> items = adapter.adaptCatalog("plugin.video.sample", Collections.<Map<String, Object>>emptyList());
        check(items.size() == 1, "provider converts structured input");
        check("film".equals(items.get(0).toMap().get("type")), "canonical type is film");
        Map<String, Object> playerRow = items.get(0).toRuivoCatalogRows().get(0);
        check("movie".equals(playerRow.get("kind")), "canonical item projects to existing movie catalog kind");
        check("https://media.example.org/film.m3u8".equals(playerRow.get("url")), "authorized URL is forwarded to existing player pipeline");
    }

    private static void testZipTraversalAndAmbiguousManifestRejected() throws Exception {
        expectRejected(zip(entries("../addon.xml", validManifest("plugin.video.sample"))), "traversal path rejected");
        Map<String, String> duplicate = new HashMap<>();
        duplicate.put("one/addon.xml", validManifest("plugin.video.sample"));
        duplicate.put("two/addon.xml", validManifest("plugin.video.other"));
        expectRejected(zip(duplicate), "multiple addon.xml rejected");
    }

    private static void testSeriesEpisodesProjectToExistingCatalog() {
        RuivoContentItem.Episode episode = new RuivoContentItem.Episode(
                "episode-1", "Piloto", "Sinopse do episódio", "https://images.example.org/ep.jpg",
                1, 2, "https://media.example.org/episode.m3u8", true);
        RuivoContentItem series = new RuivoContentItem(
                "sample:series", "Série", RuivoContentItem.Type.SERIES, "Sinopse da série",
                "https://images.example.org/series.jpg", "https://images.example.org/cover.jpg", 2023,
                Collections.singletonList("Drama"), Collections.singletonList(1),
                Collections.singletonList(episode), "", 0, 0, null, false);
        Map<String, Object> row = series.toRuivoCatalogRows().get(0);
        check("episode".equals(row.get("kind")), "series is projected as a player episode");
        check("Série".equals(row.get("series")) && Integer.valueOf(1).equals(row.get("season")), "series and season retained");
        check(Integer.valueOf(2).equals(row.get("episode")), "episode number retained");
        check("https://media.example.org/episode.m3u8".equals(row.get("url")), "episode uses the existing authorized stream field");
        check(((List<?>) series.toMap().get("episodes")).size() == 1, "canonical model retains nested episodes");
    }

    private static void testExternalEntitiesRejected() throws Exception {
        String xml = "<!DOCTYPE addon [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" +
                "<addon id='plugin.video.sample' name='&x;' version='1.0.0' provider-name='x'/>";
        expectRejected(zip(entries("addon.xml", xml)), "DOCTYPE/external entity rejected");
    }

    private static void testCanonicalPlaybackRequiresAuthorization() {
        boolean rejected = false;
        try {
            new RuivoContentItem("sample:1", "Sem autorização", RuivoContentItem.Type.FILM,
                    "", null, null, 0, Collections.<String>emptyList(), Collections.<Integer>emptyList(),
                    Collections.<RuivoContentItem.Episode>emptyList(), "", 0, 0,
                    "https://media.example.org/video.m3u8", false);
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "unverified playback URL rejected by canonical model");
    }

    private static String validManifest(String id) {
        return "<addon id='" + id + "' name='Sample' version='1.2.3' provider-name='Test'>" +
                "<requires><import addon='script.module.requests' version='2.0'/></requires>" +
                "<extension point='xbmc.python.pluginsource' library='default.py'><provides>video</provides></extension>" +
                "<extension point='xbmc.addon.metadata'><license>MIT</license></extension></addon>";
    }

    private static Map<String, String> entries(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) pairs.add(new String[]{entry.getKey(), entry.getValue()});
        return zipEntries(pairs.toArray(new String[0][]));
    }

    private static byte[] zipEntries(String[][] files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void expectRejected(byte[] archive, String message) throws Exception {
        boolean rejected = false;
        try { new KodiAddonAdapter().analyze(archive); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
