package br.sintonia.tv.addons;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Inspeciona estaticamente pacotes de addons Kodi. Nenhum arquivo é extraído a
 * disco; XML/Python são tratados como dados e nunca são importados ou executados.
 * Esta classe não resolve plugin://, não busca endpoints encontrados no código
 * e não transforma o índice ZIP do Kodi em catálogo de mídia.
 */
public final class KodiAddonAdapter {
    public static final String STATUS_NATIVE = "native";
    public static final String STATUS_ADAPTED = "adapted";
    public static final String STATUS_INCOMPATIBLE = "incompatible";

    private static final int MAX_ARCHIVE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_UNPACKED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_ENTRY_CAPTURE_BYTES = 512 * 1024;
    private static final int MAX_PYTHON_CAPTURE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PYTHON_FILES = 100;
    private static final int MAX_ADDON_XML_BYTES = 128 * 1024;

    private static final Pattern KODI_IMPORT = Pattern.compile(
            "(?im)^\\s*(?:from|import)\\s+(xbmcaddon|xbmcgui|xbmcplugin|xbmcvfs|xbmc|inputstreamhelper)(?:\\b|\\.)");
    private static final Pattern KODI_REFERENCE = Pattern.compile(
            "(?i)\\b(xbmcaddon|xbmcgui|xbmcplugin|xbmcvfs|xbmc)\\s*\\.");
    private static final Pattern DYNAMIC_CODE = Pattern.compile(
            "(?i)\\b(?:eval|exec|execfile|__import__)\\s*\\(|\\bimportlib\\s*\\.");
    private static final Pattern URL_LITERAL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");

    private final Map<String, RuivoAddonProvider> providersByKodiId;

    public KodiAddonAdapter() {
        this(Collections.<RuivoAddonProvider>emptyList());
    }

    /** Providers are trusted, compiled host code; this is never populated from a downloaded package. */
    public KodiAddonAdapter(List<RuivoAddonProvider> providers) {
        Map<String, RuivoAddonProvider> map = new HashMap<>();
        if (providers != null) {
            for (RuivoAddonProvider provider : providers) {
                if (provider == null || provider.getKodiAddonId() == null || provider.getKodiAddonId().trim().isEmpty()) continue;
                String key = provider.getKodiAddonId().toLowerCase(Locale.ROOT);
                if (map.containsKey(key)) throw new IllegalArgumentException("Há mais de um adaptador registrado para o mesmo addon Kodi.");
                map.put(key, provider);
            }
        }
        providersByKodiId = Collections.unmodifiableMap(map);
    }

    /** Analisa um ZIP em memória e retorna somente metadados, evidências e classificação. */
    public Analysis analyze(byte[] zipBytes) throws Exception {
        if (zipBytes == null || zipBytes.length == 0 || zipBytes.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("ZIP Kodi vazio ou acima do limite de 5 MiB.");
        }
        Set<String> seenPaths = new HashSet<>();
        List<String> pythonSources = new ArrayList<>();
        List<String> xmlPaths = new ArrayList<>();
        byte[][] addonXml = new byte[1][];
        int entryCount = 0;
        int unpackedBytes = 0;
        int capturedPythonBytes = 0;
        int pythonFileCount = 0;
        int candidateJsonFiles = 0;
        int candidateUrlCount = 0;
        boolean scanTruncated = false;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) throw new IllegalArgumentException("O ZIP excede o limite de 256 entradas.");
                String path = safeArchivePath(entry.getName());
                if (!seenPaths.add(path)) throw new IllegalArgumentException("O ZIP contém caminhos duplicados.");
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                String lower = path.toLowerCase(Locale.ROOT);
                boolean isAddonXml = basename(lower).equals("addon.xml");
                boolean isPython = lower.endsWith(".py");
                if (isAddonXml) xmlPaths.add(path);
                if (isPython) pythonFileCount++;
                if (lower.endsWith(".json")) candidateJsonFiles++;

                boolean captureXml = isAddonXml;
                boolean capturePython = isPython && pythonFileCount <= MAX_PYTHON_FILES && capturedPythonBytes < MAX_PYTHON_CAPTURE_BYTES;
                ByteArrayOutputStream capture = (captureXml || capturePython) ? new ByteArrayOutputStream() : null;
                int entryBytes = 0;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    entryBytes += n;
                    unpackedBytes += n;
                    if (unpackedBytes > MAX_UNPACKED_BYTES) throw new IllegalArgumentException("O ZIP expande acima do limite de inspeção de 8 MiB.");
                    if (entryBytes > MAX_UNPACKED_BYTES) throw new IllegalArgumentException("Uma entrada do ZIP excede o limite de inspeção.");
                    if (capture != null) {
                        int maxCapture = captureXml ? MAX_ADDON_XML_BYTES : MAX_ENTRY_CAPTURE_BYTES;
                        if (entryBytes > maxCapture || (capturePython && capturedPythonBytes + entryBytes > MAX_PYTHON_CAPTURE_BYTES)) {
                            capture = null;
                            if (captureXml) throw new IllegalArgumentException("addon.xml excede o limite de 128 KiB.");
                            scanTruncated = true;
                        } else {
                            capture.write(buffer, 0, n);
                        }
                    }
                }
                if (capture != null) {
                    byte[] content = capture.toByteArray();
                    String text = new String(content, StandardCharsets.UTF_8);
                    candidateUrlCount += countMatches(URL_LITERAL, text);
                    if (captureXml) addonXml[0] = content;
                    if (capturePython) {
                        capturedPythonBytes += content.length;
                        pythonSources.add(text);
                    }
                }
                if (isPython && (!capturePython || entryBytes > MAX_ENTRY_CAPTURE_BYTES)) scanTruncated = true;
                zip.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível ler o ZIP Kodi com segurança.", e);
        }

        if (xmlPaths.size() != 1 || addonXml[0] == null) {
            throw new IllegalArgumentException("O ZIP precisa conter exatamente um addon.xml legível.");
        }
        AddonMetadata metadata = parseAddonXml(addonXml[0]);
        Set<String> kodiApis = new TreeSet<>();
        int pythonFilesWithKodiApi = 0;
        boolean dynamicCodeDetected = false;
        for (String source : pythonSources) {
            Set<String> fileApis = new HashSet<>();
            Matcher imports = KODI_IMPORT.matcher(source);
            while (imports.find()) fileApis.add(imports.group(1).toLowerCase(Locale.ROOT));
            Matcher references = KODI_REFERENCE.matcher(source);
            while (references.find()) fileApis.add(references.group(1).toLowerCase(Locale.ROOT));
            if (!fileApis.isEmpty()) pythonFilesWithKodiApi++;
            kodiApis.addAll(fileApis);
            dynamicCodeDetected |= DYNAMIC_CODE.matcher(source).find();
        }

        RuivoAddonProvider provider = providersByKodiId.get(metadata.id.toLowerCase(Locale.ROOT));
        boolean authorizedProvider = provider != null
                && provider.getSourceAuthorization() == RuivoAddonProvider.SourceAuthorization.DOCUMENTED_AND_AUTHORIZED
                && provider.getAuthorizationEvidence() != null
                && !provider.getAuthorizationEvidence().trim().isEmpty();
        String status = authorizedProvider ? STATUS_ADAPTED : STATUS_INCOMPATIBLE;
        String reason;
        if (authorizedProvider) {
            reason = "Há um adaptador compilado no Ruivo TV com evidência de fonte/API autorizada. O código Kodi continua sem ser executado.";
        } else if (scanTruncated) {
            reason = "A análise estática foi limitada pelo tamanho/quantidade de arquivos; sem um adaptador de fonte autorizado, o pacote permanece incompatível.";
        } else {
            reason = "Não há adaptador do host com fonte/API autorizada registrada para este ID. As APIs Kodi encontradas não são executadas.";
        }

        return new Analysis(metadata, status, reason, provider == null ? "" : provider.getAdapterId(),
                new ArrayList<>(kodiApis), pythonFileCount, pythonFilesWithKodiApi,
                entryCount, candidateJsonFiles, candidateUrlCount, scanTruncated,
                dynamicCodeDetected, unpackedBytes, zipBytes.length);
    }

    /** Invoca somente uma implementação do host registrada e aprovada; nunca um módulo vindo do ZIP. */
    public List<RuivoContentItem> adaptCatalog(String kodiAddonId, List<Map<String, Object>> sourceItems) throws Exception {
        if (kodiAddonId == null || kodiAddonId.trim().isEmpty()) throw new IllegalArgumentException("ID de addon Kodi ausente.");
        RuivoAddonProvider provider = providersByKodiId.get(kodiAddonId.toLowerCase(Locale.ROOT));
        if (provider == null
                || provider.getSourceAuthorization() != RuivoAddonProvider.SourceAuthorization.DOCUMENTED_AND_AUTHORIZED
                || provider.getAuthorizationEvidence() == null
                || provider.getAuthorizationEvidence().trim().isEmpty()) {
            throw new IllegalArgumentException("Não há provider do host com fonte/API autorizada para este addon.");
        }
        if (sourceItems == null || sourceItems.size() > 5000) throw new IllegalArgumentException("O catálogo excede 5.000 itens ou está ausente.");
        List<RuivoContentItem> adapted = provider.adaptCatalog(Collections.unmodifiableList(new ArrayList<>(sourceItems)));
        if (adapted == null || adapted.size() > 5000) throw new IllegalArgumentException("O adaptador retornou um catálogo inválido ou acima do limite.");
        Set<String> ids = new HashSet<>();
        List<RuivoContentItem> checked = new ArrayList<>();
        for (RuivoContentItem item : adapted) {
            if (item == null || !ids.add(item.id)) throw new IllegalArgumentException("O adaptador retornou um item nulo ou ID duplicado.");
            checked.add(item);
        }
        return Collections.unmodifiableList(checked);
    }

    private static AddonMetadata parseAddonXml(byte[] bytes) throws Exception {
        String xmlText = new String(bytes, StandardCharsets.UTF_8);
        if (Pattern.compile("(?is)<!\\s*(?:DOCTYPE|ENTITY)\\b").matcher(xmlText).find()) {
            throw new IllegalArgumentException("addon.xml com DTD/entidade não é aceito.");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) { }
        factory.setExpandEntityReferences(false);
        try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); } catch (Exception ignored) { }
        try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", ""); } catch (Exception ignored) { }
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = builder.parse(new InputSource(new ByteArrayInputStream(bytes)));
        Element root = document.getDocumentElement();
        if (root == null || !"addon".equals(root.getTagName())) throw new IllegalArgumentException("A raiz de addon.xml precisa ser <addon>.");
        String id = requiredAttribute(root, "id", 200);
        String name = requiredAttribute(root, "name", 240);
        String version = requiredAttribute(root, "version", 80);
        String provider = optionalAttribute(root, "provider-name", 240);
        List<String> dependencies = new ArrayList<>();
        NodeList imports = root.getElementsByTagName("import");
        for (int i = 0; i < imports.getLength() && i < 128; i++) {
            Element item = (Element) imports.item(i);
            String addon = optionalAttribute(item, "addon", 200);
            if (addon.isEmpty()) continue;
            String versionNeeded = optionalAttribute(item, "version", 80);
            String optional = optionalAttribute(item, "optional", 12);
            dependencies.add(addon + (versionNeeded.isEmpty() ? "" : " >= " + versionNeeded) + ("true".equalsIgnoreCase(optional) ? " (opcional)" : ""));
        }
        List<String> extensions = new ArrayList<>();
        NodeList extensionNodes = root.getElementsByTagName("extension");
        for (int i = 0; i < extensionNodes.getLength() && i < 128; i++) {
            Element item = (Element) extensionNodes.item(i);
            String point = optionalAttribute(item, "point", 200);
            if (!point.isEmpty() && !extensions.contains(point)) extensions.add(point);
        }
        String license = firstTagText(root, "license", 240);
        String source = firstTagText(root, "source", 512);
        return new AddonMetadata(id, name, version, provider, dependencies, extensions, license, source);
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) { }
    }

    private static String safeArchivePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_PATH_LENGTH || raw.startsWith("/")
                || raw.startsWith("\\") || raw.contains("\\") || raw.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("O ZIP contém um caminho inválido.");
        }
        String check = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        String[] parts = check.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) throw new IllegalArgumentException("O ZIP contém um caminho relativo inseguro.");
        }
        return raw;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && count < 10000) count++;
        return count;
    }

    private static String requiredAttribute(Element element, String name, int max) {
        String value = optionalAttribute(element, name, max);
        if (value.isEmpty()) throw new IllegalArgumentException("addon.xml não contém " + name + ".");
        return value;
    }

    private static String optionalAttribute(Element element, String name, int max) {
        String value = element.getAttribute(name);
        if (value == null) return "";
        value = value.trim();
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String firstTagText(Element root, String tag, int max) {
        NodeList nodes = root.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        String value = nodes.item(0).getTextContent();
        if (value == null) return "";
        value = value.trim();
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static final class AddonMetadata {
        public final String id;
        public final String name;
        public final String version;
        public final String provider;
        public final List<String> dependencies;
        public final List<String> extensionPoints;
        public final String declaredLicense;
        public final String declaredSource;

        private AddonMetadata(String id, String name, String version, String provider,
                              List<String> dependencies, List<String> extensionPoints,
                              String declaredLicense, String declaredSource) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.provider = provider;
            this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
            this.extensionPoints = Collections.unmodifiableList(new ArrayList<>(extensionPoints));
            this.declaredLicense = declaredLicense;
            this.declaredSource = declaredSource;
        }
    }

    public static final class Analysis {
        public final AddonMetadata addon;
        public final String status;
        public final String reason;
        public final String adapterId;
        public final List<String> kodiApis;
        public final int pythonFileCount;
        public final int pythonFilesWithKodiApi;
        public final int archiveEntryCount;
        public final int candidateJsonFiles;
        public final int candidateUrlLiteralCount;
        public final boolean scanTruncated;
        public final boolean dynamicCodeDetected;
        public final int unpackedBytes;
        public final int compressedBytes;
        public final boolean permissionsGranted;

        private Analysis(AddonMetadata addon, String status, String reason, String adapterId,
                         List<String> kodiApis, int pythonFileCount, int pythonFilesWithKodiApi,
                         int archiveEntryCount, int candidateJsonFiles, int candidateUrlLiteralCount,
                         boolean scanTruncated, boolean dynamicCodeDetected,
                         int unpackedBytes, int compressedBytes) {
            this.addon = addon;
            this.status = status;
            this.reason = reason;
            this.adapterId = adapterId;
            this.kodiApis = Collections.unmodifiableList(new ArrayList<>(kodiApis));
            this.pythonFileCount = pythonFileCount;
            this.pythonFilesWithKodiApi = pythonFilesWithKodiApi;
            this.archiveEntryCount = archiveEntryCount;
            this.candidateJsonFiles = candidateJsonFiles;
            this.candidateUrlLiteralCount = candidateUrlLiteralCount;
            this.scanTruncated = scanTruncated;
            this.dynamicCodeDetected = dynamicCodeDetected;
            this.unpackedBytes = unpackedBytes;
            this.compressedBytes = compressedBytes;
            this.permissionsGranted = false;
        }
    }
}
