package br.sintonia.tv.addons;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modelo canônico de conteúdo produzido por um adaptador confiável do host.
 * Não contém código executável. URLs de reprodução só são representadas quando
 * o adaptador declara uma fonte autorizada; a allowlist HTTPS do host ainda deve
 * ser aplicada antes de qualquer requisição ou reprodução.
 */
public final class RuivoContentItem {
    public enum Type { FILM, SERIES, EPISODE, CHANNEL }

    public static final class Episode {
        public final String id;
        public final String name;
        public final String synopsis;
        public final String imageUrl;
        public final int season;
        public final int number;
        public final String authorizedPlaybackUrl;
        public final boolean sourceAuthorized;

        public Episode(String id, String name, String synopsis, String imageUrl,
                       int season, int number, String authorizedPlaybackUrl, boolean sourceAuthorized) {
            this.id = required(id, "id", 180);
            this.name = required(name, "name", 200);
            this.synopsis = optional(synopsis, 2000);
            this.imageUrl = httpsOrNull(imageUrl, "imageUrl");
            if (season < 0 || number < 0) throw new IllegalArgumentException("Temporada e episódio não podem ser negativos.");
            this.season = season;
            this.number = number;
            String playback = httpsOrNull(authorizedPlaybackUrl, "authorizedPlaybackUrl");
            if (playback != null && !sourceAuthorized) throw new IllegalArgumentException("A reprodução do episódio precisa de fonte autorizada.");
            this.authorizedPlaybackUrl = playback;
            this.sourceAuthorized = sourceAuthorized && playback != null;
        }
    }

    public final String id;
    public final String name;
    public final Type type;
    public final String synopsis;
    public final String imageUrl;
    public final String coverUrl;
    public final int year;
    public final List<String> genres;
    public final List<Integer> seasons;
    public final List<Episode> episodes;
    public final String seriesName;
    public final int season;
    public final int episodeNumber;
    public final String authorizedPlaybackUrl;
    public final boolean sourceAuthorized;

    public RuivoContentItem(String id, String name, Type type, String synopsis,
                            String imageUrl, String coverUrl, int year,
                            List<String> genres, List<Integer> seasons,
                            List<Episode> episodes, String seriesName,
                            int season, int episodeNumber,
                            String authorizedPlaybackUrl, boolean sourceAuthorized) {
        this.id = required(id, "id", 180);
        this.name = required(name, "name", 200);
        if (type == null) throw new IllegalArgumentException("type é obrigatório.");
        this.type = type;
        this.synopsis = optional(synopsis, 2000);
        this.imageUrl = httpsOrNull(imageUrl, "imageUrl");
        this.coverUrl = httpsOrNull(coverUrl, "coverUrl");
        if (year < 0 || year > 3000) throw new IllegalArgumentException("Ano fora do intervalo permitido.");
        this.year = year;
        this.genres = immutableStrings(genres, 24, 80);
        this.seasons = immutableIntegers(seasons, 100);
        this.episodes = immutableEpisodes(episodes, 2000);
        this.seriesName = optional(seriesName, 200);
        if (season < 0 || episodeNumber < 0) throw new IllegalArgumentException("Temporada e episódio não podem ser negativos.");
        this.season = season;
        this.episodeNumber = episodeNumber;
        String playback = httpsOrNull(authorizedPlaybackUrl, "authorizedPlaybackUrl");
        if (playback != null && !sourceAuthorized) {
            throw new IllegalArgumentException("Uma URL de reprodução só pode ser incluída para uma fonte autorizada.");
        }
        this.authorizedPlaybackUrl = playback;
        this.sourceAuthorized = sourceAuthorized && playback != null;
        if (type == Type.EPISODE && this.seriesName.isEmpty()) {
            throw new IllegalArgumentException("Um episódio precisa identificar a série.");
        }
        if (type == Type.SERIES && this.episodes.isEmpty()) {
            throw new IllegalArgumentException("Uma série precisa fornecer episódios estruturados.");
        }
    }

    /** Serializa o contrato canônico, não executável, para revisão/integração pelo host. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("type", type.name().toLowerCase(java.util.Locale.ROOT));
        out.put("synopsis", synopsis);
        out.put("image", imageUrl);
        out.put("cover", coverUrl);
        out.put("year", year);
        out.put("genres", genres);
        out.put("seasons", seasons);
        List<Map<String, Object>> episodeMaps = new ArrayList<>();
        for (Episode item : episodes) {
            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("id", item.id);
            ep.put("name", item.name);
            ep.put("synopsis", item.synopsis);
            ep.put("image", item.imageUrl);
            ep.put("season", item.season);
            ep.put("episode", item.number);
            ep.put("playbackUrl", item.sourceAuthorized ? item.authorizedPlaybackUrl : null);
            ep.put("sourceAuthorized", item.sourceAuthorized);
            episodeMaps.add(Collections.unmodifiableMap(ep));
        }
        out.put("episodes", Collections.unmodifiableList(episodeMaps));
        if (!seriesName.isEmpty()) out.put("series", seriesName);
        if (type == Type.EPISODE) {
            out.put("season", season);
            out.put("episode", episodeNumber);
        }
        out.put("playbackUrl", sourceAuthorized ? authorizedPlaybackUrl : null);
        out.put("sourceAuthorized", sourceAuthorized);
        return Collections.unmodifiableMap(out);
    }

    /**
     * Projeta o modelo canônico no formato que o normalizador atual do Ruivo
     * entende. A URL continua sujeita à allowlist do host e ao player padrão.
     * Séries são expandidas em itens de episódio, como já faz o catálogo interno.
     */
    public List<Map<String, Object>> toRuivoCatalogRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (type == Type.SERIES) {
            for (Episode item : episodes) {
                rows.add(playerRow(id + ":s" + item.season + "e" + item.number, item.name, "episode",
                        item.synopsis.isEmpty() ? synopsis : item.synopsis,
                        item.imageUrl != null ? item.imageUrl : (coverUrl != null ? coverUrl : imageUrl),
                        item.authorizedPlaybackUrl, item.sourceAuthorized, name, item.season, item.number,
                        year, genres));
            }
        } else {
            String kind = type == Type.CHANNEL ? "live" : type == Type.FILM ? "movie" : "episode";
            rows.add(playerRow(id, name, kind, synopsis,
                    coverUrl != null ? coverUrl : imageUrl,
                    authorizedPlaybackUrl, sourceAuthorized, seriesName, season, episodeNumber,
                    year, genres));
        }
        return Collections.unmodifiableList(rows);
    }

    private static Map<String, Object> playerRow(String id, String name, String kind,
                                                  String description, String logo, String playbackUrl,
                                                  boolean authorized, String series, int season, int episode,
                                                  int year, List<String> genres) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("kind", kind);
        row.put("description", description == null ? "" : description);
        row.put("logo", logo);
        row.put("url", authorized ? playbackUrl : "");
        row.put("series", series == null ? "" : series);
        row.put("season", season);
        row.put("episode", episode);
        row.put("year", year);
        row.put("category", joinGenres(genres));
        return Collections.unmodifiableMap(row);
    }

    private static String joinGenres(List<String> genres) {
        if (genres == null || genres.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String genre : genres) {
            if (genre == null || genre.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(genre.trim());
        }
        return out.toString();
    }

    private static String required(String value, String field, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException(field + " ausente ou acima do limite.");
        return v;
    }

    private static String optional(String value, int max) {
        String v = value == null ? "" : value.trim();
        if (v.length() > max) v = v.substring(0, max);
        return v;
    }

    private static String httpsOrNull(String value, String field) {
        if (value == null || value.trim().isEmpty()) return null;
        String raw = value.trim();
        if (raw.length() > 2048) throw new IllegalArgumentException(field + " acima do limite.");
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(field + " deve ser uma URL HTTPS válida.");
            }
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(field + " inválida.");
        }
    }

    private static List<String> immutableStrings(List<String> source, int maxItems, int maxLength) {
        List<String> out = new ArrayList<>();
        if (source != null) {
            for (String value : source) {
                if (out.size() >= maxItems) break;
                String item = optional(value, maxLength);
                if (!item.isEmpty() && !out.contains(item)) out.add(item);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Integer> immutableIntegers(List<Integer> source, int maxItems) {
        List<Integer> out = new ArrayList<>();
        if (source != null) {
            for (Integer value : source) {
                if (out.size() >= maxItems) break;
                if (value != null && value >= 0 && !out.contains(value)) out.add(value);
            }
        }
        Collections.sort(out);
        return Collections.unmodifiableList(out);
    }

    private static List<Episode> immutableEpisodes(List<Episode> source, int maxItems) {
        List<Episode> out = new ArrayList<>();
        if (source != null) {
            for (Episode value : source) {
                if (out.size() >= maxItems) break;
                if (value != null) out.add(value);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
