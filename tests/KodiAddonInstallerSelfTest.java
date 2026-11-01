import br.sintonia.tv.addons.KodiAddonInstaller;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class KodiAddonInstallerSelfTest {
    static int ok, bad;
    static void check(String n, boolean c) { if (c) ok++; else { bad++; System.out.println("FALHOU: " + n); } }
    static byte[] zip(String[][] files) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) { for (String[] f : files) { z.putNextEntry(new ZipEntry(f[0])); z.write(f[1].getBytes("UTF-8")); z.closeEntry(); } }
        return b.toByteArray();
    }
    static String xml(String id, String license, String point, String imp) {
        return "<addon id=\"" + id + "\" name=\"T\" version=\"1.0.0\" provider-name=\"x\"><requires><import addon=\"xbmc.python\" version=\"3.0.0\"/>" + imp + "</requires>"
             + "<extension point=\"" + point + "\" library=\"default.py\"><provides>video</provides></extension>"
             + "<extension point=\"xbmc.addon.metadata\"><summary>s</summary><license>" + license + "</license></extension></addon>";
    }
    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("kodi-addons").toFile();
        String id = "plugin.video.teste";
        String py = "import xbmc\nprint('ok')\n";
        byte[] good = zip(new String[][]{{id + "/addon.xml", xml(id, "MIT", "xbmc.python.pluginsource", "")}, {id + "/default.py", py}});
        check("sem confirmação", !KodiAddonInstaller.install(dir, good, false, false).ok);
        KodiAddonInstaller.Result r = KodiAddonInstaller.install(dir, good, false, true);
        check("terceiro com licença e confirmação", r.ok && KodiAddonInstaller.isInstalled(dir, id));
        check("reinstalação marca replaced", KodiAddonInstaller.install(dir, good, false, true).replaced);
        check("sem licença bloqueado", !KodiAddonInstaller.install(dir, zip(new String[][]{{"plugin.video.sl/addon.xml", xml("plugin.video.sl", "", "xbmc.python.pluginsource", "")}, {"plugin.video.sl/default.py", py}}), false, true).ok);
        check("repositório bloqueado", !KodiAddonInstaller.install(dir, zip(new String[][]{{"repo.x/addon.xml", xml("repo.x", "MIT", "xbmc.addon.repository", "")}}), false, true).ok);
        check("service bloqueado p/ terceiro", !KodiAddonInstaller.install(dir, zip(new String[][]{{"service.x/addon.xml", xml("service.x", "MIT", "xbmc.service", "")}, {"service.x/default.py", py}}), false, true).ok);
        check("service ok se empacotado", KodiAddonInstaller.install(dir, zip(new String[][]{{"service.ok/addon.xml", xml("service.ok", "MIT", "xbmc.service", "")}, {"service.ok/default.py", py}}), true, false).ok);
        check("eval bloqueado", !KodiAddonInstaller.install(dir, zip(new String[][]{{"plugin.video.ev/addon.xml", xml("plugin.video.ev", "MIT", "xbmc.python.pluginsource", "")}, {"plugin.video.ev/default.py", "eval('1')\n"}}), false, true).ok);
        check("dependência ausente", !KodiAddonInstaller.install(dir, zip(new String[][]{{"plugin.video.dp/addon.xml", xml("plugin.video.dp", "MIT", "xbmc.python.pluginsource", "<import addon=\"script.module.nao.existe\"/>")}, {"plugin.video.dp/default.py", py}}), false, true).ok);
        check("zip-slip", !KodiAddonInstaller.install(dir, zip(new String[][]{{id + "/addon.xml", xml(id, "MIT", "xbmc.python.pluginsource", "")}, {id + "/../../evil.py", "x"}}), false, true).ok);
        check("zip-slip não escreveu fora", !new File(dir.getParentFile(), "evil.py").exists() && !new File(dir, "evil.py").exists());
        check("entrada fora da pasta do addon", !KodiAddonInstaller.install(dir, zip(new String[][]{{id + "/addon.xml", xml(id, "MIT", "xbmc.python.pluginsource", "")}, {"outro/arquivo.py", "x"}}), false, true).ok);
        check("id inválido", !KodiAddonInstaller.install(dir, zip(new String[][]{{"../x/addon.xml", xml("../x", "MIT", "xbmc.python.pluginsource", "")}}), true, true).ok);
        check("sem sobras .tmp", dir.list((d, n) -> n.endsWith(".tmp")).length == 0);
        if (a.length > 0) {
            byte[] demo = Files.readAllBytes(Paths.get(a[0]));
            File d2 = Files.createTempDirectory("kodi-addons2").toFile();
            check("addon de exemplo empacotado", KodiAddonInstaller.install(d2, demo, true, false).ok && KodiAddonInstaller.isInstalled(d2, "plugin.video.ruivo.demo") && new File(d2, "plugin.video.ruivo.demo/default.py").isFile());
            check("addon de exemplo como terceiro (tem licença)", KodiAddonInstaller.install(d2, demo, false, true).ok);
        }
        System.out.println("kodi-installer: " + ok + " ok, " + bad + " falhas");
        System.exit(bad == 0 ? 0 : 1);
    }
}
