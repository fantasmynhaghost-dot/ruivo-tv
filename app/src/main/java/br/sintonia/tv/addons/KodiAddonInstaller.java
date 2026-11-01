package br.sintonia.tv.addons;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Instala um addon Kodi na pasta de addons do runtime Kodi embutido. NÃO executa nada: o addon só roda
 * dentro do Kodi. Sem dependências do Android (testável na JVM). Políticas:
 *  - addon empacotado no APK (bundledTrusted) é confiável; qualquer outro exige confirmação do usuário;
 *  - addon de terceiros precisa declarar licença, só pode usar pontos de extensão simples (plugin de vídeo,
 *    metadados, módulo Python), não pode usar eval/exec/importlib e suas dependências já devem estar instaladas.
 */
public final class KodiAddonInstaller {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._-]{3,100}$");
    private static final Set<String> THIRD_PARTY_POINTS = new HashSet<>(Arrays.asList(
            "xbmc.python.pluginsource", "xbmc.addon.metadata", "xbmc.python.module"));
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    public static final class Result {
        public final boolean ok; public final String addonId; public final String message; public final boolean replaced;
        Result(boolean ok, String addonId, String message, boolean replaced) { this.ok = ok; this.addonId = addonId; this.message = message; this.replaced = replaced; }
    }

    private KodiAddonInstaller() {}

    public static Result install(File addonsDir, byte[] zip, boolean bundledTrusted, boolean userConfirmed) {
        String id = "";
        try {
            KodiAddonAdapter.Analysis a = new KodiAddonAdapter().analyze(zip);
            id = a.addon.id;
            if (!ID.matcher(id).matches() || id.contains("..")) return fail(id, "ID de addon inválido.");
            if (!bundledTrusted) {
                if (!userConfirmed) return fail(id, "É preciso confirmar a instalação.");
                if (a.addon.declaredLicense.trim().isEmpty()) return fail(id, "O addon não declara licença; instalação bloqueada.");
                for (String p : a.addon.extensionPoints) if (!THIRD_PARTY_POINTS.contains(p)) return fail(id, "Ponto de extensão não permitido: " + p);
                if (a.dynamicCodeDetected) return fail(id, "O código usa execução dinâmica (eval/exec/importlib); bloqueado.");
            }
            for (String dep : a.addon.dependencies) {
                String depId = dep.split("\\s+")[0];
                boolean optional = dep.endsWith("(opcional)");
                if (depId.startsWith("xbmc.") || optional || new File(addonsDir, depId).isDirectory()) continue;
                return fail(id, "Dependência ausente: " + depId);
            }
            if (!addonsDir.isDirectory() && !addonsDir.mkdirs()) return fail(id, "Não foi possível criar a pasta de addons do Kodi.");
            File tmp = new File(addonsDir, "." + id + ".tmp");
            deleteRecursively(tmp);
            if (!tmp.mkdirs()) return fail(id, "Não foi possível criar a pasta temporária.");
            try {
                extract(zip, id, tmp);
                if (!new File(tmp, "addon.xml").isFile()) { deleteRecursively(tmp); return fail(id, "addon.xml ausente na raiz do addon."); }
                File dest = new File(addonsDir, id);
                boolean replaced = dest.exists();
                deleteRecursively(dest);
                if (!tmp.renameTo(dest)) { deleteRecursively(tmp); return fail(id, "Falha ao finalizar a instalação."); }
                return new Result(true, id, "Instalado.", replaced);
            } catch (RuntimeException | IOException e) {
                deleteRecursively(tmp);
                return fail(id, "Pacote inválido: " + e.getMessage());
            }
        } catch (Exception e) {
            return fail(id, e.getMessage() == null ? "Pacote inválido." : e.getMessage());
        }
    }

    private static void extract(byte[] zip, String id, File tmp) throws IOException {
        String prefix = id + "/";
        String root = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e; byte[] buf = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith(prefix)) throw new IOException("entrada fora da pasta do addon: " + name);
                if (e.isDirectory()) continue;
                File out = new File(tmp, name.substring(prefix.length()));
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("caminho inseguro");
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("sem permissão de escrita");
                int total = 0, n;
                try (OutputStream os = new FileOutputStream(out)) {
                    while ((n = in.read(buf)) != -1) { total += n; if (total > MAX_FILE_BYTES) throw new IOException("arquivo grande demais"); os.write(buf, 0, n); }
                }
            }
        }
    }

    public static boolean isInstalled(File addonsDir, String id) {
        return id != null && ID.matcher(id).matches() && !id.contains("..") && new File(new File(addonsDir, id), "addon.xml").isFile();
    }

    private static Result fail(String id, String msg) { return new Result(false, id, msg, false); }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }
}
