package br.sintonia.tv.addons;

import android.app.Activity;
import android.net.Uri;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Gerenciador de addons declarativos. Recebe somente manifestos e catálogos JSON;
 * nunca carrega nem executa código fornecido por um addon.
 */
public final class AddonManager {
    private static final String PREFS = "ruivo.addons";
    private static final String KEY_INSTALLED = "installed";
    private static final String KEY_REPOSITORIES = "repositories";
    private static final int MAX_MANIFEST_BYTES = 96 * 1024;
    private static final int MAX_CATALOG_BYTES = 512 * 1024;
    private static final int MAX_REPOSITORY_BYTES = 512 * 1024;
    private static final int MAX_PACKAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_UNPACKED_PACKAGE_BYTES = 768 * 1024;
    private static final int API_VERSION = 1;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");

    private final Activity activity;
    private final WebView web;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final KodiAddonAdapter kodiAddonAdapter = new KodiAddonAdapter();
    private final Map<String, PendingPreview> pendingPreviews = new ConcurrentHashMap<>();
    private final Object repositoryLock = new Object();
    private volatile boolean closed;

    private static final class PendingPreview {
        final String url;
        final JSONObject manifest;
        final String fingerprint;
        final JSONObject packageCatalog;
        final byte[] packageBytes;
        final String operation;
        final String expectedId;
        final long createdAt;
        PendingPreview(String url, JSONObject manifest, String fingerprint, JSONObject packageCatalog, byte[] packageBytes, String operation, String expectedId) {
            this.url = url; this.manifest = manifest; this.fingerprint = fingerprint; this.packageCatalog = packageCatalog; this.packageBytes = packageBytes; this.operation = operation; this.expectedId = expectedId; this.createdAt = System.currentTimeMillis();
        }
    }

    public AddonManager(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    public String listInstalled() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(KEY_INSTALLED, "[]");
    }

    public void previewManifest(String manifestUrl, String requestId) {
        io.execute(() -> {
            try {
                String raw = downloadText(manifestUrl, MAX_MANIFEST_BYTES);
                JSONObject manifest = objectFromText(raw, "O manifesto precisa ser um objeto JSON.");
                validateManifest(manifest, manifestUrl);
                if (indexOf(installedArray(), manifest.getString("id")) >= 0) {
                    throw new IllegalArgumentException("Este ID já está instalado. Use Atualizações para atualizar o addon.");
                }
                String token = UUID.randomUUID().toString();
                putPendingPreview(token, new PendingPreview(manifestUrl, manifest, sha256(raw.getBytes(StandardCharsets.UTF_8)), null, null, "install", null));
                JSONObject preview = new JSONObject(manifest.toString());
                preview.put("previewToken", token); preview.put("operation", "install");
                preview.put("trustWarning", "HTTPS protege o transporte, mas não certifica a confiança no desenvolvedor.");
                result(requestId, "preview", true, preview);
            } catch (Exception e) { result(requestId, "preview", false, error(e)); }
        });
    }

    public void confirmPreview(String token, String requestId) {
        PendingPreview pending = pendingPreviews.remove(token == null ? "" : token);
        if (pending == null || System.currentTimeMillis() - pending.createdAt > 5 * 60 * 1000) { result(requestId, "install", false, error("Prévia expirada. Valide o manifesto novamente.")); return; }
        io.execute(() -> {
            try {
                if (pending.packageBytes != null) {
                    if (!pending.fingerprint.equals(sha256(pending.packageBytes))) throw new IllegalArgumentException("O pacote em prévia foi alterado; valide-o novamente.");
                    installManifestObject(pending.manifest, pending.url, requestId, pending.operation, pending.expectedId, pending.packageCatalog);
                    return;
                }
                String raw = downloadText(pending.url, MAX_MANIFEST_BYTES);
                if (!pending.fingerprint.equals(sha256(raw.getBytes(StandardCharsets.UTF_8)))) {
                    throw new IllegalArgumentException("O manifesto mudou depois da prévia. Valide novamente antes de instalar.");
                }
                JSONObject manifest = objectFromText(raw, "O manifesto precisa ser um objeto JSON.");
                validateManifest(manifest, pending.url);
                installManifestObject(manifest, pending.url, requestId, "install", null, null);
            } catch (Exception e) { result(requestId, pending.operation, false, error(e)); }
        });
    }

    public void previewPackage(String packageUrl, String expectedSha256, String expectedVersion, String requestId, String operation, String expectedId) {
        io.execute(() -> {
            try {
                if (expectedSha256 == null || !expectedSha256.matches("(?i)[a-f0-9]{64}")) throw new IllegalArgumentException("O repositório não forneceu um SHA-256 válido para o pacote.");
                byte[] bytes = downloadBinary(packageUrl, MAX_PACKAGE_BYTES);
                String hash = sha256(bytes);
                if (!hash.equalsIgnoreCase(expectedSha256)) throw new IllegalArgumentException("SHA-256 do ZIP não corresponde ao valor publicado no repositório.");
                Map<String, byte[]> files = unpackDeclarativeZip(bytes);
                JSONObject manifest = objectFromText(new String(files.get("manifest.json"), StandardCharsets.UTF_8), "O ZIP precisa conter manifest.json válido.");
                validateManifest(manifest, packageUrl, true);
                if (expectedVersion != null && !expectedVersion.equals(manifest.optString("version"))) throw new IllegalArgumentException("A versão do manifesto não corresponde à versão declarada no repositório.");
                JSONObject catalog = jsonObjectFromBytes(files.get("catalog.json"));
                validateCatalogShape(manifest, catalog);
                JSONArray installed = installedArray(); int oldIndex = indexOf(installed, manifest.getString("id"));
                if ("update".equals(operation)) {
                    if (oldIndex < 0 || !manifest.getString("id").equals(expectedId)) throw new IllegalArgumentException("O pacote de atualização não corresponde ao addon instalado.");
                    if (compareVersions(manifest.getString("version"), installed.getJSONObject(oldIndex).optString("version")) <= 0) throw new IllegalArgumentException("O pacote não contém uma versão mais recente.");
                } else if (oldIndex >= 0) throw new IllegalArgumentException("Este ID já está instalado; use Atualizações para substituir uma versão.");
                manifest.put("_package", true); manifest.put("_packageSha256", hash);
                String token = UUID.randomUUID().toString();
                putPendingPreview(token, new PendingPreview(packageUrl, manifest, hash, catalog, bytes, operation, expectedId));
                JSONObject preview = new JSONObject(manifest.toString());
                preview.put("previewToken", token); preview.put("packageSha256", hash);
                preview.put("operation", operation);
                preview.put("trustWarning", "ZIP declarativo: checksum verificado contra o repositório. Isso não comprova a identidade do desenvolvedor; revise a fonte.");
                result(requestId, "preview", true, preview);
            } catch (Exception e) { result(requestId, "preview", false, error(e)); }
        });
    }

    /** Análise offline do pacote Kodi: faz download HTTPS limitado e nunca executa/extrai os scripts. */
    public void analyzeKodiPackage(String packageUrl, String requestId) {
        io.execute(() -> {
            try {
                byte[] bytes = downloadBinary(packageUrl, MAX_PACKAGE_BYTES);
                KodiAddonAdapter.Analysis analysis = kodiAddonAdapter.analyze(bytes);
                JSONObject payload = new JSONObject();
                payload.put("id", analysis.addon.id);
                payload.put("name", analysis.addon.name);
                payload.put("version", analysis.addon.version);
                payload.put("provider", analysis.addon.provider);
                payload.put("status", analysis.status);
                payload.put("reason", analysis.reason);
                payload.put("adapterId", analysis.adapterId);
                payload.put("dependencies", new JSONArray(analysis.addon.dependencies));
                payload.put("extensionPoints", new JSONArray(analysis.addon.extensionPoints));
                payload.put("kodiApis", new JSONArray(analysis.kodiApis));
                payload.put("pythonFileCount", analysis.pythonFileCount);
                payload.put("pythonFilesWithKodiApi", analysis.pythonFilesWithKodiApi);
                payload.put("archiveEntryCount", analysis.archiveEntryCount);
                payload.put("candidateJsonFiles", analysis.candidateJsonFiles);
                payload.put("candidateUrlLiteralCount", analysis.candidateUrlLiteralCount);
                payload.put("scanTruncated", analysis.scanTruncated);
                payload.put("dynamicCodeDetected", analysis.dynamicCodeDetected);
                payload.put("declaredLicense", analysis.addon.declaredLicense);
                payload.put("declaredSource", analysis.addon.declaredSource);
                payload.put("permissionsGranted", analysis.permissionsGranted);
                payload.put("permissionNote", "A análise estática não concede permissões Android nem autoriza fontes ou reprodução.");
                payload.put("compressedBytes", analysis.compressedBytes);
                payload.put("unpackedBytes", analysis.unpackedBytes);
                payload.put("sha256", sha256(bytes));
                payload.put("sourceHost", new URI(packageUrl).getHost());
                payload.put("staticAnalysisOnly", true);
                result(requestId, "kodi-analysis", true, payload);
            } catch (Exception e) {
                result(requestId, "kodi-analysis", false, error(e));
            }
        });
    }

    public void installDemo(String requestId) {
        io.execute(() -> {
            try {
                byte[] bytes = readAssetBytes("addons/demo/demo-addon.zip", MAX_PACKAGE_BYTES);
                Map<String, byte[]> files = unpackDeclarativeZip(bytes);
                String sourceUrl = "https://example.org/ruivotv-demo-addon.zip";
                JSONObject manifest = objectFromText(new String(files.get("manifest.json"), StandardCharsets.UTF_8), "Pacote de demonstração inválido.");
                validateManifest(manifest, sourceUrl, true, true);
                JSONObject catalog = jsonObjectFromBytes(files.get("catalog.json")); validateCatalogShape(manifest, catalog);
                manifest.put("_package", true); manifest.put("_demo", true); manifest.put("_packageSha256", sha256(bytes));
                manifest.put("_originHost", "RuivoTV demo local");
                installManifestObject(manifest, sourceUrl, requestId, "demo", null, catalog);
            } catch (Exception e) { result(requestId, "demo", false, error(e)); }
        });
    }

    public void installFromManifest(String manifestUrl, String requestId) {
        installFromManifest(manifestUrl, requestId, "install", null);
    }

    private void installFromManifest(String manifestUrl, String requestId, String operation, String expectedId) {
        io.execute(() -> {
            try {
                String raw = downloadText(manifestUrl, MAX_MANIFEST_BYTES);
                JSONObject manifest = objectFromText(raw, "O manifesto precisa ser um objeto JSON.");
                validateManifest(manifest, manifestUrl);
                installManifestObject(manifest, manifestUrl, requestId, operation, expectedId, null);
            } catch (Exception e) {
                result(requestId, operation, false, error(e));
            }
        });
    }

    private synchronized void installManifestObject(JSONObject manifest, String manifestUrl, String requestId, String operation, String expectedId, JSONObject packageCatalog) throws Exception {
        String id = manifest.getString("id");
        if (expectedId != null && !expectedId.equals(id)) throw new IllegalArgumentException("O ID do manifesto não corresponde ao addon que está sendo atualizado.");
        JSONArray installed = installedArray();
        int oldIndex = indexOf(installed, id);
        boolean isUpdate = "update".equals(operation);
        if (oldIndex >= 0 && !isUpdate) throw new IllegalArgumentException("Este ID já está instalado; não será substituído sem uma atualização explícita.");
        if (isUpdate && oldIndex < 0) throw new IllegalArgumentException("Addon não encontrado para atualização.");
        JSONObject old = oldIndex >= 0 ? installed.getJSONObject(oldIndex) : null;
        boolean enabled = old != null && old.optBoolean("enabled", false);
        if (old != null && compareVersions(manifest.getString("version"), old.optString("version", "0.0.0")) < 0)
            throw new IllegalArgumentException("A atualização é mais antiga que a versão instalada.");
        manifest.put("_manifestUrl", manifestUrl);
        manifest.put("_updateUrl", manifest.optString("updateUrl", manifestUrl));
        JSONObject candidateCatalog = packageCatalog != null ? packageCatalog : isUpdate ? downloadAddonCatalog(manifest, false) : null;
        if (old != null && old.has("settings")) manifest.put("settings", old.optJSONObject("settings") == null ? old.opt("settings") : old.optJSONObject("settings"));
        manifest.put("enabled", enabled);
        if (oldIndex >= 0) installed.remove(oldIndex);
        installed.put(manifest);
        String oldState = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_INSTALLED, "[]");
        byte[] oldCache = isUpdate ? readBytes(catalogFile(id)) : null;
        if (!saveInstalled(installed)) throw new IllegalStateException("Não foi possível gravar a instalação.");
        try {
            if (candidateCatalog != null) writeCatalogCache(id, candidateCatalog);
            else if (!isUpdate) deleteAddonFolder(id);
        } catch (Exception e) {
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY_INSTALLED, oldState).commit();
            if (oldCache != null) writeBytes(catalogFile(id), oldCache); else deleteAddonFolder(id);
            throw new IllegalStateException("A atualização falhou ao gravar o catálogo; a versão anterior foi restaurada.", e);
        }
        result(requestId, operation, true, manifest);
    }

    public void updateAddon(String addonId, String requestId) {
        JSONObject addon = findAddon(addonId);
        if (addon == null) {
            result(requestId, "update", false, error("Addon não encontrado."));
            return;
        }
        if (addon.optBoolean("_package", false)) {
            JSONObject candidate = findRepositoryPackage(addonId);
            if (candidate == null) { result(requestId, "update", false, error("Nenhum pacote atualizado com SHA-256 foi encontrado nos repositórios sincronizados.")); return; }
            previewPackage(candidate.optString("packageUrl"), candidate.optString("sha256"), candidate.optString("version"), requestId, "update", addonId);
            return;
        }
        installFromManifest(addon.optString("_updateUrl", addon.optString("_manifestUrl", "")), requestId, "update", addonId);
    }

    private JSONObject findRepositoryPackage(String id) {
        JSONArray items = repositoryItems(repositoriesArray()); JSONObject installed = findAddon(id);
        if (installed == null) return null;
        JSONObject best = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id")) && item.has("packageUrl") && compareVersions(item.optString("version", "0.0.0"), installed.optString("version", "0.0.0")) > 0 && (best == null || compareVersions(item.optString("version"), best.optString("version")) > 0)) best = item;
        }
        return best;
    }

    public synchronized boolean setEnabled(String addonId, boolean enabled) {
        try {
            JSONArray installed = installedArray();
            int i = indexOf(installed, addonId);
            if (i < 0) return false;
            JSONObject item = installed.getJSONObject(i);
            item.put("enabled", enabled);
            installed.put(i, item);
            return saveInstalled(installed);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean removeAddon(String addonId) {
        try {
            JSONArray installed = installedArray();
            int i = indexOf(installed, addonId);
            if (i < 0) return false;
            installed.remove(i);
            if (!saveInstalled(installed)) return false;
            deleteAddonFolder(addonId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void listRepositories(String requestId) {
        JSONObject payload = new JSONObject();
        try { payload.put("repositories", repositoriesArray()); payload.put("items", repositoryItems(repositoriesArray())); }
        catch (Exception e) { result(requestId, "repositories", false, error(e)); return; }
        result(requestId, "repositories", true, payload);
    }

    public void addRepository(String url, String requestId) {
        io.execute(() -> {
            try {
                JSONObject repository = downloadObject(url, MAX_REPOSITORY_BYTES);
                validateRepository(repository, url);
                repository.put("url", url);
                repository.put("lastSync", System.currentTimeMillis());
                repository.put("lastError", "");
                JSONArray repos;
                synchronized (repositoryLock) {
                    repos = repositoriesArray(); int index = repositoryIndex(repos, url);
                    if (index >= 0) repos.put(index, repository); else repos.put(repository);
                    if (!saveRepositories(repos)) throw new IllegalStateException("Não foi possível salvar o repositório.");
                }
                JSONObject payload = new JSONObject();
                payload.put("repositories", repos); payload.put("items", repositoryItems(repos));
                result(requestId, "repositories", true, payload);
            } catch (Exception e) { result(requestId, "repositories", false, error(e)); }
        });
    }

    public void syncRepositories(String requestId) {
        io.execute(() -> {
            JSONArray repos = repositoriesArray();
            for (int i = 0; i < repos.length(); i++) {
                JSONObject old = repos.optJSONObject(i);
                if (old == null) continue;
                try {
                    String url = old.getString("url");
                    JSONObject fresh = downloadObject(url, MAX_REPOSITORY_BYTES);
                    validateRepository(fresh, url);
                    fresh.put("url", url); fresh.put("lastSync", System.currentTimeMillis()); fresh.put("lastError", "");
                    repos.put(i, fresh);
                } catch (Exception e) {
                    try { old.put("lastError", e.getMessage() == null ? "Falha de sincronização" : e.getMessage()); repos.put(i, old); }
                    catch (JSONException ignored) {}
                }
            }
            JSONObject payload = new JSONObject();
            try {
                synchronized (repositoryLock) {
                    JSONArray latest = repositoriesArray();
                    for (int i = 0; i < repos.length(); i++) {
                        JSONObject candidate = repos.optJSONObject(i); if (candidate == null) continue;
                        int index = repositoryIndex(latest, candidate.optString("url"));
                        if (index >= 0) latest.put(index, candidate);
                    }
                    if (!saveRepositories(latest)) throw new IllegalStateException("Não foi possível salvar os repositórios sincronizados.");
                    repos = latest;
                }
                payload.put("repositories", repos); payload.put("items", repositoryItems(repos));
            }
            catch (JSONException ignored) {}
            catch (Exception e) { result(requestId, "repositories", false, error(e)); return; }
            result(requestId, "repositories", true, payload);
        });
    }

    public boolean removeRepository(String url) {
        synchronized (repositoryLock) {
            JSONArray repos = repositoriesArray(); int index = repositoryIndex(repos, url);
            if (index < 0) return false;
            repos.remove(index); return saveRepositories(repos);
        }
    }

    public void checkUpdates(String requestId) {
        io.execute(() -> {
            JSONArray updates = new JSONArray(), installed = installedArray();
            for (int i = 0; i < installed.length(); i++) {
                JSONObject current = installed.optJSONObject(i); if (current == null) continue;
                JSONObject row = new JSONObject();
                try {
                    row.put("id", current.getString("id")); row.put("currentVersion", current.optString("version"));
                    if (current.optBoolean("_package", false)) {
                        JSONObject packageCandidate = findRepositoryPackage(current.getString("id"));
                        row.put("available", packageCandidate != null);
                        if (packageCandidate != null) { row.put("newVersion", packageCandidate.optString("version")); row.put("releaseNotes", packageCandidate.optString("releaseNotes", "")); }
                        else row.put("newVersion", current.optString("version"));
                        updates.put(row); continue;
                    }
                    String url = current.optString("_updateUrl", current.optString("_manifestUrl", ""));
                    JSONObject candidate = downloadObject(url, MAX_MANIFEST_BYTES); validateManifest(candidate, url);
                    if (!current.getString("id").equals(candidate.getString("id"))) throw new IllegalArgumentException("O ID do manifesto mudou.");
                    row.put("newVersion", candidate.optString("version"));
                    row.put("available", compareVersions(candidate.optString("version"), current.optString("version")) > 0);
                    row.put("releaseNotes", candidate.optString("releaseNotes", ""));
                } catch (Exception e) { try { row.put("available", false); row.put("error", e.getMessage()); } catch (JSONException ignored) {} }
                updates.put(row);
            }
            JSONObject payload = new JSONObject(); try { payload.put("items", updates); } catch (JSONException ignored) {}
            result(requestId, "updates", true, payload);
        });
    }

    public void loadCatalog(String addonId, String requestId) {
        io.execute(() -> {
            try {
                JSONObject manifest = findAddon(addonId);
                if (manifest == null) throw new IllegalArgumentException("Addon não encontrado.");
                if (!manifest.optBoolean("enabled", false)) throw new IllegalArgumentException("Ative o addon antes de carregar o catálogo.");
                JSONObject response; boolean cached = false; String networkError = "";
                try { response = downloadAddonCatalog(manifest, false); writeCatalogCache(addonId, response); }
                catch (Exception network) {
                    response = readCatalogCache(addonId);
                    if (response == null) throw network;
                    cached = true; networkError = network.getMessage() == null ? "Sem conexão; usando cache." : network.getMessage();
                }
                JSONObject payload = new JSONObject();
                payload.put("manifest", manifest);
                payload.put("catalog", response);
                payload.put("cached", cached);
                if (cached) payload.put("statusMessage", networkError);
                result(requestId, "catalog", true, payload);
            } catch (Exception e) {
                JSONObject payload = error(e);
                try { if (addonId != null) payload.put("id", addonId); } catch (JSONException ignored) {}
                result(requestId, "catalog", false, payload);
            }
        });
    }

    public void close() {
        closed = true;
        io.shutdownNow();
    }

    /**
     * Ações chegam por um esquema interno interceptado somente em navegações do frame principal.
     * Isso evita expor uma @JavascriptInterface a iframes de conteúdo externo.
     */
    public void handleUri(Uri uri) {
        if (uri == null || !"ruivo-addon".equals(uri.getScheme())) return;
        String action = uri.getHost();
        String requestId = uri.getQueryParameter("requestId");
        if (requestId == null || requestId.length() > 80) return;
        String id = uri.getQueryParameter("id");
        switch (action == null ? "" : action) {
            case "list": {
                JSONObject payload = new JSONObject();
                try { payload.put("items", new JSONArray(listInstalled())); } catch (JSONException ignored) {}
                result(requestId, "list", true, payload);
                break;
            }
            case "install":
                confirmPreview(uri.getQueryParameter("token"), requestId);
                break;
            case "preview":
                previewManifest(uri.getQueryParameter("url"), requestId);
                break;
            case "package-preview":
                previewPackage(uri.getQueryParameter("url"), uri.getQueryParameter("sha256"), uri.getQueryParameter("version"), requestId, "install", null);
                break;
            case "kodi-analyze":
                analyzeKodiPackage(uri.getQueryParameter("url"), requestId);
                break;
            case "demo":
                installDemo(requestId);
                break;
            case "catalog":
                loadCatalog(id, requestId);
                break;
            case "update":
                updateAddon(id, requestId);
                break;
            case "updates":
                checkUpdates(requestId);
                break;
            case "repo-list":
                listRepositories(requestId);
                break;
            case "repo-add":
                addRepository(uri.getQueryParameter("url"), requestId);
                break;
            case "repo-sync":
                syncRepositories(requestId);
                break;
            case "repo-remove": {
                String url = uri.getQueryParameter("url");
                boolean ok = removeRepository(url);
                JSONObject payload = new JSONObject();
                try { payload.put("url", url); payload.put("repositories", repositoriesArray()); payload.put("items", repositoryItems(repositoriesArray())); if (!ok) payload.put("error", "Repositório não encontrado."); }
                catch (JSONException ignored) {}
                result(requestId, "repositories", ok, payload);
                break;
            }
            case "enable": {
                boolean enabled = "1".equals(uri.getQueryParameter("value"));
                boolean ok = setEnabled(id, enabled);
                JSONObject payload = ok ? new JSONObject() : error("Não foi possível alterar o status do addon.");
                try { if (ok) { payload.put("id", id); payload.put("enabled", enabled); } } catch (JSONException ignored) {}
                result(requestId, "enable", ok, payload);
                break;
            }
            case "remove": {
                boolean ok = removeAddon(id);
                JSONObject payload = ok ? new JSONObject() : error("Addon não encontrado.");
                try { if (ok) payload.put("id", id); } catch (JSONException ignored) {}
                result(requestId, "remove", ok, payload);
                break;
            }
            default:
                result(requestId, "error", false, error("Ação de addon não reconhecida."));
        }
    }

    private JSONObject downloadObject(String url, int maxBytes) throws Exception {
        Object object = new JSONTokener(downloadText(url, maxBytes)).nextValue();
        if (!(object instanceof JSONObject)) throw new IllegalArgumentException("O manifesto precisa ser um objeto JSON.");
        return (JSONObject) object;
    }

    private JSONObject downloadJson(String url, int maxBytes) throws Exception {
        Object object = new JSONTokener(downloadText(url, maxBytes)).nextValue();
        if (object instanceof JSONObject) return (JSONObject) object;
        if (object instanceof JSONArray) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("items", object);
            return wrapper;
        }
        throw new IllegalArgumentException("O catálogo precisa ser um objeto ou uma lista JSON.");
    }

    private JSONObject objectFromText(String text, String message) throws Exception {
        Object object = new JSONTokener(text).nextValue();
        if (!(object instanceof JSONObject)) throw new IllegalArgumentException(message);
        return (JSONObject) object;
    }

    private void validateRepository(JSONObject repo, String repoUrl) throws Exception {
        Iterator<String> repoKeys = repo.keys();
        while (repoKeys.hasNext()) { String key = repoKeys.next(); if (!java.util.Arrays.asList("schemaVersion", "name", "description", "items").contains(key)) throw new IllegalArgumentException("Campo não suportado no repositório: " + key); }
        if (repo.optInt("schemaVersion", -1) != 1) throw new IllegalArgumentException("Versão de repositório incompatível.");
        requiredString(repo, "name", 100);
        if (repo.has("description") && repo.optString("description").length() > 500) throw new IllegalArgumentException("Descrição longa demais no repositório.");
        JSONArray items = repo.optJSONArray("items");
        if (items == null || items.length() > 100) throw new IllegalArgumentException("O repositório precisa conter até 100 itens em items.");
        validateNetworkUrl(repoUrl);
        Map<String, Boolean> ids = new HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) throw new IllegalArgumentException("Entrada inválida no repositório.");
            Iterator<String> itemKeys = item.keys();
            while (itemKeys.hasNext()) {
                String key = itemKeys.next();
                if (!java.util.Arrays.asList("id", "name", "version", "developer", "description", "category", "manifestUrl", "packageUrl", "sha256", "releaseNotes").contains(key)) throw new IllegalArgumentException("Campo não suportado na entrada do repositório: " + key);
            }
            String id = requiredString(item, "id", 64);
            if (!ID_PATTERN.matcher(id).matches() || ids.put(id, true) != null) throw new IllegalArgumentException("ID inválido ou duplicado no repositório: " + id);
            requiredString(item, "name", 80); String version = requiredString(item, "version", 32);
            if (!VERSION_PATTERN.matcher(version).matches()) throw new IllegalArgumentException("Versão inválida no repositório.");
            if (item.has("developer")) requiredString(item, "developer", 80);
            if (item.has("description") && item.optString("description").length() > 500) throw new IllegalArgumentException("Descrição longa demais no repositório.");
            if (item.has("releaseNotes") && item.optString("releaseNotes").length() > 2000) throw new IllegalArgumentException("Notas longas demais no repositório.");
            String manifestUrl = item.optString("manifestUrl", "").trim();
            String packageUrl = item.optString("packageUrl", "").trim();
            if (manifestUrl.isEmpty() == packageUrl.isEmpty()) throw new IllegalArgumentException("Cada item precisa ter somente manifestUrl ou packageUrl.");
            validateNetworkUrl(manifestUrl.isEmpty() ? packageUrl : manifestUrl);
            if (!packageUrl.isEmpty() && !item.optString("sha256", "").matches("(?i)[a-f0-9]{64}"))
                throw new IllegalArgumentException("Pacotes ZIP do repositório precisam informar SHA-256.");
            if (!manifestUrl.isEmpty() && item.has("sha256")) throw new IllegalArgumentException("sha256 só é permitido junto de packageUrl.");
            if (item.has("category") && item.optString("category").length() > 48) throw new IllegalArgumentException("Categoria longa demais no repositório.");
        }
    }

    private JSONArray repositoryItems(JSONArray repos) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < repos.length(); i++) {
            JSONObject repo = repos.optJSONObject(i); JSONArray items = repo == null ? null : repo.optJSONArray("items");
            if (items == null) continue;
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j); if (item == null) continue;
                try {
                    JSONObject copy = new JSONObject(item.toString());
                    copy.put("repositoryName", repo.optString("name"));
                    copy.put("repositoryUrl", repo.optString("url"));
                    copy.put("lastSync", repo.optLong("lastSync", 0));
                    out.put(copy);
                } catch (JSONException ignored) {}
            }
        }
        return out;
    }

    private JSONArray repositoriesArray() {
        try { return new JSONArray(activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_REPOSITORIES, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private boolean saveRepositories(JSONArray repos) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY_REPOSITORIES, repos.toString()).commit();
    }

    private int repositoryIndex(JSONArray repos, String url) {
        for (int i = 0; i < repos.length(); i++) { JSONObject r = repos.optJSONObject(i); if (r != null && url != null && url.equals(r.optString("url"))) return i; }
        return -1;
    }

    private JSONObject downloadAddonCatalog(JSONObject manifest, boolean packageMode) throws Exception {
        JSONObject cfg = manifest.getJSONObject("catalog");
        String path = cfg.getString("path");
        if (packageMode || path.equals("package:catalog.json")) {
            JSONObject local = readCatalogCache(manifest.optString("id"));
            if (local == null) throw new IllegalArgumentException("Pacote declarativo sem catálogo local instalado.");
            return local;
        }
        String manifestUrl = manifest.getString("_manifestUrl");
        String host = new URL(manifestUrl).getHost();
        JSONObject response = downloadJson(new URL("https", host, path).toString(), MAX_CATALOG_BYTES);
        validateCatalogShape(manifest, response);
        return response;
    }

    private void validateCatalogShape(JSONObject manifest, JSONObject response) throws Exception {
        JSONObject cfg = manifest.getJSONObject("catalog");
        JSONArray items = arrayAtPath(response, cfg.optString("itemsPath", "items"));
        if (items == null || items.length() > 5000) throw new IllegalArgumentException("O catálogo não contém a lista declarada em itemsPath ou excede 5000 itens.");
    }

    private JSONArray arrayAtPath(JSONObject root, String path) {
        Object value = root; if (path == null || path.isEmpty()) return value instanceof JSONArray ? (JSONArray) value : null;
        for (String part : path.split("\\.")) {
            if (part.isEmpty() || part.equals("__proto__") || part.equals("constructor")) return null;
            if (value instanceof JSONObject) value = ((JSONObject) value).opt(part); else return null;
        }
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    private File addonDirectory(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("ID inválido para armazenamento local.");
        return new File(new File(activity.getFilesDir(), "ruivo-addons"), id);
    }

    private File catalogFile(String id) { return new File(addonDirectory(id), "catalog.json"); }

    private void writeCatalogCache(String id, JSONObject catalog) throws Exception {
        validateSize(catalog.toString().getBytes(StandardCharsets.UTF_8).length, MAX_CATALOG_BYTES, "Catálogo excede o cache permitido.");
        JSONObject envelope = new JSONObject(); envelope.put("savedAt", System.currentTimeMillis()); envelope.put("catalog", catalog);
        writeBytes(catalogFile(id), envelope.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject readCatalogCache(String id) {
        try {
            byte[] data = readBytes(catalogFile(id)); if (data == null || data.length > MAX_CATALOG_BYTES + 1024) return null;
            JSONObject envelope = objectFromText(new String(data, StandardCharsets.UTF_8), "Cache inválido.");
            return envelope.optJSONObject("catalog");
        } catch (Exception e) { return null; }
    }

    private byte[] readBytes(File file) {
        try {
            if (!file.isFile() || file.length() > MAX_PACKAGE_BYTES) return null;
            try (InputStream in = new java.io.FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
            }
        } catch (Exception e) { return null; }
    }

    private void writeBytes(File target, byte[] bytes) throws Exception {
        File dir = target.getParentFile(); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Não foi possível criar a pasta isolada do addon.");
        File temp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
        if (target.exists() && !target.delete()) { temp.delete(); throw new IllegalStateException("Não foi possível substituir o cache anterior."); }
        if (!temp.renameTo(target)) { temp.delete(); throw new IllegalStateException("Não foi possível gravar o cache do addon."); }
    }

    private void deleteAddonFolder(String id) {
        try {
            File dir = addonDirectory(id); File[] files = dir.listFiles();
            if (files != null) for (File file : files) if (file.isFile()) file.delete();
            dir.delete();
            File root = dir.getParentFile(); if (root != null) root.delete();
        } catch (Exception ignored) {}
    }

    private void validateSize(int size, int max, String message) { if (size < 0 || size > max) throw new IllegalArgumentException(message); }

    private byte[] readAssetBytes(String assetPath, int maxBytes) throws Exception {
        try (InputStream in = activity.getAssets().open(assetPath); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, n;
            while ((n = in.read(buffer)) != -1) { total += n; if (total > maxBytes) throw new IllegalArgumentException("Asset de demonstração excede o limite permitido."); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }

    private String downloadText(String rawUrl, int maxBytes) throws Exception {
        URL url = validateNetworkUrl(rawUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RuivoTV-AddonHost/1");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalArgumentException("Servidor respondeu HTTP " + code + ". Redirecionamentos não são aceitos.");
            String contentType = connection.getContentType();
            if (contentType != null && !(contentType.toLowerCase(Locale.ROOT).contains("json") || contentType.toLowerCase(Locale.ROOT).startsWith("text/plain"))) {
                throw new IllegalArgumentException("A resposta do servidor não é JSON.");
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0, n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IllegalArgumentException("Resposta JSON excede o limite permitido.");
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private byte[] downloadBinary(String rawUrl, int maxBytes) throws Exception {
        URL url = validateNetworkUrl(rawUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET"); connection.setConnectTimeout(8000); connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept", "application/zip, application/octet-stream");
            connection.setRequestProperty("User-Agent", "RuivoTV-AddonHost/1");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalArgumentException("Servidor respondeu HTTP " + code + "; redirecionamentos não são aceitos.");
            String type = connection.getContentType();
            if (type != null && !(type.toLowerCase(Locale.ROOT).contains("zip") || type.toLowerCase(Locale.ROOT).contains("octet-stream"))) throw new IllegalArgumentException("A resposta não é um ZIP.");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int total = 0, n;
                while ((n = in.read(buffer)) != -1) { total += n; if (total > maxBytes) throw new IllegalArgumentException("Pacote excede o limite de 5 MB."); out.write(buffer, 0, n); }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    private Map<String, byte[]> unpackDeclarativeZip(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("ZIP vazio ou acima do limite.");
        Map<String, byte[]> files = new HashMap<>(); int total = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                String name = entry.getName();
                if (count > 3 || entry.isDirectory() || name == null || name.length() > 32 || name.startsWith("/") || name.contains("/") || name.contains("\\") || name.contains("..") || name.indexOf('\u0000') >= 0)
                    throw new IllegalArgumentException("O ZIP contém um caminho, diretório ou quantidade de arquivos não permitidos.");
                if (!(name.equals("manifest.json") || name.equals("catalog.json") || name.equals("README.md"))) throw new IllegalArgumentException("Arquivo não permitido no pacote declarativo: " + name);
                if (files.containsKey(name)) throw new IllegalArgumentException("Arquivo duplicado no ZIP: " + name);
                int perFile = name.equals("manifest.json") ? MAX_MANIFEST_BYTES : name.equals("catalog.json") ? MAX_CATALOG_BYTES : 16 * 1024;
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n, size = 0;
                while ((n = zip.read(buf)) != -1) { size += n; total += n; if (size > perFile || total > MAX_UNPACKED_PACKAGE_BYTES) throw new IllegalArgumentException("Conteúdo descompactado excede os limites."); out.write(buf, 0, n); }
                files.put(name, out.toByteArray()); zip.closeEntry();
            }
        }
        if (!files.containsKey("manifest.json") || !files.containsKey("catalog.json")) throw new IllegalArgumentException("O ZIP precisa conter manifest.json e catalog.json.");
        return files;
    }

    private JSONObject jsonObjectFromBytes(byte[] bytes) throws Exception {
        Object object = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)).nextValue();
        if (object instanceof JSONObject) return (JSONObject) object;
        if (object instanceof JSONArray) { JSONObject wrapped = new JSONObject(); wrapped.put("items", object); return wrapped; }
        throw new IllegalArgumentException("catalog.json precisa ser um objeto ou uma lista JSON.");
    }

    private URL validateNetworkUrl(String raw) throws Exception {
        if (raw == null || raw.length() > 2048) throw new IllegalArgumentException("URL inválida.");
        URI uri = new URI(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use uma URL HTTPS pública, sem usuário, senha ou fragmento.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new IllegalArgumentException("Somente HTTPS na porta padrão é aceito.");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!DOMAIN_PATTERN.matcher(host).matches() || host.contains("..") || host.equals("localhost") || host.endsWith(".local") || host.matches("\\d+(?:\\.\\d+){3}")) {
            throw new IllegalArgumentException("O host da URL não é permitido.");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new IllegalArgumentException("Não foi possível resolver o domínio.");
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Endereços de rede privada não são permitidos.");
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) throw new IllegalArgumentException("Endereços IPv6 privados não são permitidos.");
        }
        return uri.toURL();
    }

    private void validateManifest(JSONObject m, String manifestUrl) throws Exception { validateManifest(m, manifestUrl, false, false); }

    private void validateManifest(JSONObject m, String manifestUrl, boolean packageMode) throws Exception { validateManifest(m, manifestUrl, packageMode, false); }

    private void validateManifest(JSONObject m, String manifestUrl, boolean packageMode, boolean localTrusted) throws Exception {
        HashSet<String> allowedKeys = new HashSet<>(java.util.Arrays.asList("schemaVersion", "apiVersion", "id", "name", "description", "version", "developer", "compatibility", "updateUrl", "iconUrl", "allowedHosts", "capabilities", "catalog", "categories", "permissions", "releaseNotes"));
        Iterator<String> keys = m.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!allowedKeys.contains(key)) throw new IllegalArgumentException("Campo não suportado no manifesto: " + key); }
        if (m.optInt("schemaVersion", -1) != 1 || m.optInt("apiVersion", -1) != API_VERSION) throw new IllegalArgumentException("Versão de manifesto ou API incompatível.");
        String id = requiredString(m, "id", 64);
        if (!ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("ID inválido. Use letras minúsculas, números, ponto, hífen ou sublinhado.");
        requiredString(m, "name", 80);
        requiredString(m, "description", 500);
        requiredString(m, "developer", 80);
        String version = requiredString(m, "version", 32);
        if (!VERSION_PATTERN.matcher(version).matches()) throw new IllegalArgumentException("A versão deve seguir o formato MAJOR.MINOR.PATCH.");

        JSONObject compatibility = m.optJSONObject("compatibility");
        if (compatibility == null) throw new IllegalArgumentException("Declare compatibility.minAppVersion no manifesto.");
        String minAppVersion = requiredString(compatibility, "minAppVersion", 32);
        if (!VERSION_PATTERN.matcher(minAppVersion).matches()) throw new IllegalArgumentException("compatibility.minAppVersion deve seguir MAJOR.MINOR.PATCH.");
        String appVersion = "1.0.0";
        try {
            String installedVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            if (installedVersion != null && VERSION_PATTERN.matcher(installedVersion).matches()) appVersion = installedVersion;
        } catch (Exception ignored) {}
        if (compareVersions(appVersion, minAppVersion) < 0) throw new IllegalArgumentException("Este addon exige RuivoTV " + minAppVersion + " ou superior.");
        String maxAppVersion = compatibility.optString("maxAppVersion", "").trim();
        if (!maxAppVersion.isEmpty()) {
            if (!VERSION_PATTERN.matcher(maxAppVersion).matches()) throw new IllegalArgumentException("compatibility.maxAppVersion inválida.");
            if (compareVersions(appVersion, maxAppVersion) > 0) throw new IllegalArgumentException("Este addon não é compatível com esta versão do RuivoTV.");
        }

        URL manifest = localTrusted ? new URL(manifestUrl) : validateNetworkUrl(manifestUrl);
        String updateUrl = m.optString("updateUrl", manifestUrl).trim();
        URL update = localTrusted ? new URL(updateUrl) : validateNetworkUrl(updateUrl);
        if (!manifest.getHost().equalsIgnoreCase(update.getHost())) throw new IllegalArgumentException("updateUrl deve permanecer no domínio do manifesto.");
        m.put("updateUrl", updateUrl);
        JSONObject catalog = m.optJSONObject("catalog");
        if (catalog == null) throw new IllegalArgumentException("Manifesto sem configuração de catálogo.");
        String path = requiredString(catalog, "path", 512);
        if (packageMode) {
            if (!path.equals("package:catalog.json")) throw new IllegalArgumentException("Pacotes ZIP devem usar catalog.path = package:catalog.json.");
        } else if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("\\") || path.contains("#")) {
            throw new IllegalArgumentException("O caminho do catálogo deve ser relativo à origem e não pode sair dela.");
        }
        JSONArray caps = m.optJSONArray("capabilities");
        if (caps == null || caps.length() == 0 || caps.length() > 8) throw new IllegalArgumentException("Declare ao menos uma capacidade suportada.");
        HashSet<String> capSet = new HashSet<>();
        for (int i = 0; i < caps.length(); i++) {
            String cap = caps.optString(i, "");
            if (!(cap.equals("live") || cap.equals("movies") || cap.equals("series") || cap.equals("search")) || !capSet.add(cap)) throw new IllegalArgumentException("Capacidade não suportada ou repetida: " + cap);
        }
        JSONArray permissions = m.optJSONArray("permissions");
        if (permissions == null) { permissions = new JSONArray(); permissions.put("catalog.read"); permissions.put("stream.play"); if (m.has("iconUrl")) permissions.put("images.read"); m.put("permissions", permissions); }
        if (permissions.length() < 2 || permissions.length() > 3) throw new IllegalArgumentException("Declare somente as permissões conhecidas do host.");
        HashSet<String> permissionSet = new HashSet<>();
        for (int i = 0; i < permissions.length(); i++) {
            String permission = permissions.optString(i, "");
            if (!(permission.equals("catalog.read") || permission.equals("stream.play") || permission.equals("images.read")) || !permissionSet.add(permission)) throw new IllegalArgumentException("Permissão inválida ou repetida: " + permission);
        }
        if (!permissionSet.contains("catalog.read") || !permissionSet.contains("stream.play")) throw new IllegalArgumentException("Permissões catalog.read e stream.play são necessárias para addons de catálogo.");
        if (m.has("iconUrl") && !permissionSet.contains("images.read")) throw new IllegalArgumentException("iconUrl exige a permissão images.read.");
        JSONArray categories = m.optJSONArray("categories");
        if (categories == null) { categories = new JSONArray(); m.put("categories", categories); }
        if (categories.length() > 16) throw new IllegalArgumentException("Limite de categorias declarado excedido.");
        HashSet<String> categorySet = new HashSet<>();
        for (int i = 0; i < categories.length(); i++) {
            String category = categories.optString(i, "").trim();
            if (category.isEmpty() || category.length() > 48 || !categorySet.add(category)) throw new IllegalArgumentException("Categoria inválida ou repetida no manifesto.");
        }
        if (m.has("releaseNotes") && m.optString("releaseNotes").length() > 2000) throw new IllegalArgumentException("releaseNotes está muito longo.");
        String itemsPath = catalog.optString("itemsPath", "items");
        if (itemsPath.length() > 256 || itemsPath.startsWith(".") || itemsPath.contains("..") || unsafePath(itemsPath)) throw new IllegalArgumentException("itemsPath inválido.");
        JSONObject fields = catalog.optJSONObject("fields");
        if (fields == null) throw new IllegalArgumentException("Declare os mapeamentos catalog.fields.");
        for (String key : new String[]{"id", "name", "kind", "url"}) {
            String value = requiredString(fields, key, 128);
            if (!value.matches("[A-Za-z0-9_.-]+") || unsafePath(value)) throw new IllegalArgumentException("Mapeamento inválido: " + key);
        }
        for (String key : new String[]{"category", "logo", "number", "series", "season", "episode", "duration", "description", "year", "rating", "schedule"}) {
            if (fields.has(key) && (!fields.optString(key, "").matches("[A-Za-z0-9_.-]+") || unsafePath(fields.optString(key, "")))) throw new IllegalArgumentException("Mapeamento inválido: " + key);
        }
        JSONArray hosts = m.optJSONArray("allowedHosts");
        if (hosts != null && hosts.length() > 20) throw new IllegalArgumentException("Limite de domínios permitidos excedido.");
        String manifestHost = manifest.getHost().toLowerCase(Locale.ROOT);
        if (hosts == null) {
            hosts = new JSONArray();
            hosts.put(manifestHost);
            m.put("allowedHosts", hosts);
        } else {
            boolean includesOrigin = false;
            HashSet<String> hostSet = new HashSet<>();
            for (int i = 0; i < hosts.length(); i++) {
                String h = hosts.optString(i, "").toLowerCase(Locale.ROOT);
                if (!DOMAIN_PATTERN.matcher(h).matches() || h.contains("..") || h.equals("localhost") || h.matches("\\d+(?:\\.\\d+){3}")) throw new IllegalArgumentException("Domínio permitido inválido.");
                if (!hostSet.add(h)) throw new IllegalArgumentException("Domínio duplicado em allowedHosts.");
                if (!localTrusted) validateNetworkUrl("https://" + h + "/");
                if (h.equals(manifestHost)) includesOrigin = true;
            }
            if (!includesOrigin) hosts.put(manifestHost);
            m.put("allowedHosts", hosts);
        }
        String iconUrl = m.optString("iconUrl", "").trim();
        if (!iconUrl.isEmpty()) {
            URL icon = validateNetworkUrl(iconUrl);
            boolean iconHostAllowed = false;
            for (int i = 0; i < hosts.length(); i++) {
                if (icon.getHost().equalsIgnoreCase(hosts.optString(i, ""))) iconHostAllowed = true;
            }
            if (!iconHostAllowed) throw new IllegalArgumentException("O host do ícone não está em allowedHosts.");
            m.put("iconUrl", iconUrl);
        }
        m.put("_originHost", manifestHost);
    }

    private boolean unsafePath(String path) {
        if (path == null) return true;
        for (String part : path.split("\\.")) if (part.equals("__proto__") || part.equals("constructor")) return true;
        return false;
    }

    private String requiredString(JSONObject object, String key, int max) {
        String value = object.optString(key, "").trim();
        if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("Campo obrigatório ausente ou muito longo: " + key);
        return value;
    }

    private JSONArray installedArray() {
        try {
            return new JSONArray(activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_INSTALLED, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private boolean saveInstalled(JSONArray installed) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY_INSTALLED, installed.toString()).commit();
    }

    private JSONObject findAddon(String id) {
        JSONArray installed = installedArray();
        int i = indexOf(installed, id);
        return i < 0 ? null : installed.optJSONObject(i);
    }

    private int indexOf(JSONArray array, String id) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return i;
        }
        return -1;
    }

    private int compareVersions(String a, String b) {
        String[] aa = a.split("\\."), bb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = Integer.parseInt(aa[i]), y = Integer.parseInt(bb[i]);
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private synchronized void putPendingPreview(String token, PendingPreview preview) {
        long now = System.currentTimeMillis();
        for (String key : new java.util.ArrayList<>(pendingPreviews.keySet())) {
            PendingPreview old = pendingPreviews.get(key);
            if (old == null || now - old.createdAt > 5 * 60 * 1000) pendingPreviews.remove(key);
        }
        while (pendingPreviews.size() >= 2) {
            String first = pendingPreviews.keySet().stream().findFirst().orElse(null);
            if (first == null) break;
            pendingPreviews.remove(first);
        }
        pendingPreviews.put(token, preview);
    }

    private JSONObject error(Exception e) {
        return error(e.getMessage() == null ? "Falha ao processar addon." : e.getMessage());
    }

    private JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try { out.put("error", message == null ? "Falha ao processar addon." : message); }
        catch (JSONException ignored) {}
        return out;
    }

    private void result(String requestId, String operation, boolean success, JSONObject payload) {
        if (closed || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (closed) return;
            String script = "window.__addonBridgeResult&&window.__addonBridgeResult(" + JSONObject.quote(requestId) + "," + JSONObject.quote(operation) + "," + success + "," + payload.toString() + ")";
            web.evaluateJavascript(script, null);
        });
    }
}
