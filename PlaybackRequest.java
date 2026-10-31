package br.sintonia.tv.playback;

import java.net.URI;
import java.util.*;

/** Pedido imutável e validado. Headers sensíveis nunca aparecem em toString(). */
public final class PlaybackRequest {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "file", "content");
    private static final Set<String> ALLOWED_HEADERS = Set.of("user-agent", "referer", "accept", "accept-language", "range", "authorization", "cookie");
    public final String requestId, contentId, title, mimeType;
    public final URI uri;
    public final Map<String, String> headers;
    public final long startPositionMs;
    public final List<URI> subtitleUris;

    private PlaybackRequest(Builder b) {
        requestId = b.requestId; contentId = b.contentId; title = b.title; mimeType = b.mimeType;
        uri = b.uri; startPositionMs = b.startPositionMs;
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        subtitleUris = List.copyOf(b.subtitleUris);
    }

    @Override public String toString() {
        return "PlaybackRequest{id=" + requestId + ", host=" + uri.getHost() + ", scheme=" + uri.getScheme() + ", headers=" + headers.keySet() + "}";
    }

    public static Builder builder(String requestId, String uri) { return new Builder(requestId, uri); }

    public static final class Builder {
        private final String requestId; private final URI uri;
        private String contentId = "", title = "", mimeType = "";
        private long startPositionMs;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<URI> subtitleUris = new ArrayList<>();

        private Builder(String requestId, String uri) {
            if (requestId == null || requestId.isBlank() || requestId.length() > 128) throw new IllegalArgumentException("requestId inválido.");
            this.requestId = requestId; this.uri = checkUri(uri);
        }
        private static URI checkUri(String raw) {
            if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("URI ausente ou longa demais.");
            URI u;
            try { u = new URI(raw); } catch (Exception e) { throw new IllegalArgumentException("URI malformada."); }
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!ALLOWED_SCHEMES.contains(s)) throw new IllegalArgumentException("Esquema não permitido: " + s);
            if (u.getUserInfo() != null) throw new IllegalArgumentException("Credenciais embutidas na URI não são aceitas.");
            if (s.equals("https") && (u.getHost() == null || u.getHost().isEmpty())) throw new IllegalArgumentException("Host ausente.");
            return u;
        }
        public Builder contentId(String v) { contentId = v == null ? "" : v; return this; }
        public Builder title(String v) { title = v == null ? "" : v; return this; }
        public Builder mimeType(String v) { mimeType = v == null ? "" : v; return this; }
        public Builder startPositionMs(long v) { if (v < 0) throw new IllegalArgumentException("Posição negativa."); startPositionMs = v; return this; }
        public Builder header(String k, String v) {
            if (k == null || v == null || !ALLOWED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Cabeçalho não permitido.");
            if (k.indexOf('\r') >= 0 || k.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) throw new IllegalArgumentException("Cabeçalho com quebra de linha.");
            headers.put(k, v); return this;
        }
        public Builder subtitle(String raw) { subtitleUris.add(checkUri(raw)); return this; }
        public PlaybackRequest build() { return new PlaybackRequest(this); }
    }
}
