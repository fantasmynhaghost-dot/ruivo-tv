package br.sintonia.tv.addons;

import java.util.List;
import java.util.Map;

/**
 * Contrato para adaptadores/provedores mantidos e compilados no próprio host.
 * Implementações não podem ser carregadas de ZIP, URL, WebView ou repositório.
 * O transporte da fonte externa é responsabilidade do host e deve aplicar as
 * validações HTTPS/hosts/permissões do contrato Ruivo.
 */
public interface RuivoAddonProvider {
    enum SourceAuthorization {
        /** Fonte, API ou autorização do titular ainda não foi verificada. */
        NOT_VERIFIED,
        /** API/feed publicado e uso autorizado foram revisados pelo projeto. */
        DOCUMENTED_AND_AUTHORIZED
    }

    /** ID estável do adaptador, por exemplo "ruivo.adapter.example". */
    String getAdapterId();

    /** ID exato do addon Kodi para o qual este adaptador foi revisado. */
    String getKodiAddonId();

    /** Evidência humana/referência pública registrada no código de integração. */
    String getAuthorizationEvidence();

    /** Só DOCUMENTED_AND_AUTHORIZED habilita a classificação "adaptado". */
    SourceAuthorization getSourceAuthorization();

    /**
     * Converte itens já obtidos pelo transporte controlado do host para o
     * modelo canônico Ruivo. Este método não deve fazer rede nem executar o
     * código do addon Kodi; recebe apenas dados estruturados de uma fonte
     * revisada pelo projeto.
     */
    List<RuivoContentItem> adaptCatalog(List<Map<String, Object>> sourceItems) throws Exception;
}
