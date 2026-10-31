# Contrato de addons do RuivoTV

## Escopo e segurança

O RuivoTV é o host: mantém navegação, catálogo, estado e player. O addon v1 é declarativo; fornece metadados e itens JSON que são mapeados pelo app. O RuivoTV **não executa JavaScript, HTML, DEX, APK, plugins Kodi, scripts ou código dentro do WebView**. Um pacote ZIP só é aceito como contêiner de `manifest.json`, `catalog.json` e, opcionalmente, `README.md`; nunca como plugin executável.

Nenhuma loja central é necessária. O usuário adiciona URLs HTTPS independentes de manifesto ou de índices de repositório JSON. HTTPS e SHA-256 não certificam a identidade nem a confiabilidade do desenvolvedor. Permissões declaradas são metadados de capacidades limitadas do host, não permissões Android.

O plugin `skyrisk/brazucaplay` é direcionado ao runtime Kodi. A variante pública Matrix revisada declara `xbmc.python.pluginsource`, depende de módulos Kodi/Python e não declara licença de software em `addon.xml`; o GitHub também não informa uma licença formal para o repositório. O Ruivo TV não executa nem instala esse ZIP e mantém a integração desativada até existir fonte/API externa documentada e autorização verificável. O resultado da inspeção estática e as referências estão em [`IMPLEMENTATION-REPORT.md`](IMPLEMENTATION-REPORT.md).

## Instalação por URL

Na aba **Instalar por URL**, informe uma URL HTTPS direta de manifesto JSON. O app baixa e valida os dados, apresenta nome, desenvolvedor, versão, compatibilidade, capacidades, permissões, hosts e aviso de confiança, e só persiste após a confirmação. O manifesto é baixado novamente na confirmação; se seus bytes mudarem, a instalação é recusada e a prévia deve ser refeita. Prévia expira em cinco minutos e tokens são de uso único.

Manifestos e catálogos remotos precisam responder diretamente sem redirecionamentos, autenticação ou credenciais na URL. São aceitos somente HTTPS na porta 443 e hosts DNS públicos (sem IP privado, loopback ou link-local). O catálogo remoto fica no mesmo host do manifesto e usa `catalog.path` iniciado por `/`. Timeouts: conexão 8 s; leitura JSON 12 s. Limites: manifesto 96 KiB e catálogo 512 KiB.

## Manifesto v1

Campos obrigatórios: `schemaVersion`, `apiVersion`, `id`, `name`, `description`, `version`, `developer`, `compatibility`, `capabilities` e `catalog`. `schemaVersion` e `apiVersion` são `1`; `id` segue `[a-z0-9][a-z0-9._-]{2,63}`. Versão do addon e compatibilidade usam `MAJOR.MINOR.PATCH`, componentes de até três dígitos. A versão mínima/máxima é comparada com a versão instalada do RuivoTV.

`categories` é uma lista opcional de até 16 rótulos. `capabilities` aceita `live`, `movies`, `series` e `search`. `permissions` aceita `catalog.read`, `stream.play` e `images.read`; as duas primeiras são exigidas para catálogo reproduzível. Se omitidas, as duas permissões base são adicionadas; para um manifesto legado com `iconUrl`, `images.read` é acrescentada como compatibilidade. Declarar `iconUrl` exige `images.read`. O app não carrega imagens do addon se essa permissão não estiver presente.

`allowedHosts` lista até 20 hostnames exatos, sem curingas; o host do manifesto é incluído automaticamente. URLs de ícones/streams devem ser HTTPS e estar na allowlist. `updateUrl` é opcional e, se ausente, usa a URL do manifesto. Updates por manifesto exigem mesmo host, mesmo ID, compatibilidade válida e versão não regressiva. `releaseNotes` pode descrever alterações.

`catalog.fields` mapeia caminhos simples separados por ponto. Campos obrigatórios: `id`, `name`, `kind`, `url`. São opcionais: `category`, `logo`, `number`, `series`, `season`, `episode`, `duration`, `description`, `year`, `rating`, `schedule`. O app aceita `live`, `movie`/`film` e `episode`/`series` como valores de `kind`; itens de episódio precisam informar o nome da série. IDs internos são `addon-id:source-id`, preservando favoritos e histórico por conteúdo quando IDs de origem forem estáveis.

Consulte [`addon-manifest.schema.json`](addon-manifest.schema.json). O schema e o validador Java são alinhados aos campos/limites essenciais, mas o app não executa um motor JSON Schema genérico em runtime.

## Exemplo de manifesto remoto

Este é somente um exemplo de formato. Os domínios `example.org` são reservados para documentação e não fornecem conteúdo:

```json
{
  "schemaVersion": 1,
  "apiVersion": 1,
  "id": "org.exemplo.catalogo",
  "name": "Catálogo de exemplo",
  "description": "Exemplo ilustrativo; substitua por uma descrição verdadeira.",
  "version": "1.0.0",
  "developer": "Desenvolvedor do serviço",
  "compatibility": { "minAppVersion": "1.0.0" },
  "allowedHosts": ["catalog.example.org", "media.example.org"],
  "capabilities": ["live", "movies", "series"],
  "categories": ["Demonstração"],
  "permissions": ["catalog.read", "stream.play"],
  "releaseNotes": "Primeira versão",
  "catalog": {
    "path": "/catalog.json",
    "itemsPath": "items",
    "fields": {
      "id": "id", "name": "title", "kind": "type", "url": "stream.url",
      "category": "category", "logo": "logo", "series": "series",
      "season": "season", "episode": "episode", "year": "year",
      "rating": "rating", "description": "description", "schedule": "schedule"
    }
  }
}
```

O catálogo deve responder a GET com `{ "items": [...] }` (ou lista JSON de topo quando `itemsPath` for `items`). Mapeamento é declarativo, sem transformações, autenticação, cabeçalhos arbitrários ou endpoints de busca independentes.

## Índices de repositório

A aba **Repositórios** aceita uma URL HTTPS para índice JSON, sem redirecionamento. O formato está em [`repository-index.schema.json`](repository-index.schema.json) e em [`examples/ruivo-repository.example.json`](examples/ruivo-repository.example.json). O índice usa `schemaVersion: 1`, nome e no máximo 100 entradas. Cada entrada tem ID/nome/versão e exatamente uma destas opções:

- `manifestUrl`: HTTPS público para manifesto declarativo;
- `packageUrl` e `sha256`: HTTPS público para ZIP limitado e seu SHA-256 hexadecimal de 64 caracteres.

O catálogo do repositório é baixado sob demanda; o app conserva o último índice válido quando uma sincronização falha e mostra o erro/horário. Adicionar um item abre a prévia de instalação; o app nunca instala automaticamente por apenas sincronizar. Repositórios são fontes não confiáveis e podem publicar conteúdo malicioso ou URLs indevidas; revise antes de confirmar.

## ZIP declarativo

ZIPs são aceitos somente como conteúdo declarativo e somente a partir de um item de repositório que publique o hash esperado. O limite comprimido é 5 MiB, o total descompactado 768 KiB, com no máximo três arquivos: `manifest.json` (até 96 KiB), `catalog.json` (até 512 KiB) e `README.md` (até 16 KiB). São rejeitados subdiretórios, caminhos absolutos, `..`, barras, nomes duplicados e qualquer arquivo extra. Nada é extraído para caminhos controlados pelo pacote; os bytes são lidos em memória e o catálogo é salvo no diretório privado isolado do app, por ID do addon.

O manifesto do pacote usa `catalog.path: "package:catalog.json"`. Não se aceitam scripts, HTML, binários, APK ou bibliotecas. O SHA-256 é comparado com o índice do repositório antes de mostrar a prévia. **Isso detecta divergência em relação ao índice, mas não autentica o publicador**, especialmente se o mesmo atacante puder trocar índice e pacote. Não há assinatura digital implementada.

## Atualizações, cache e remoção

A aba **Atualizações** compara a versão instalada com o manifesto de update ou com pacotes mais novos nos repositórios sincronizados. Updates são individuais. Em manifestos remotos, o app baixa e valida o novo manifesto e pré-carrega o catálogo novo antes de trocar o estado; rejeita downgrade e preserva `enabled` e o objeto `settings` existente. Uma falha durante a validação/gravação restaura manifesto e cache anteriores. Pacotes ZIP atualizados passam por nova prévia e confirmação.

O catálogo carregado com sucesso é armazenado em arquivo dentro da pasta privada do addon. Se a rede falhar, o RuivoTV usa esse cache, informa que é offline/desatualizado e mantém a origem do addon. Desativar remove os itens ativos da interface sem afetar outros addons; desinstalar também remove o cache daquele ID.

O rollback cobre falhas de rede/validação/commit durante a troca de versão. Não é uma sandbox ou rollback de código: addons não executam código de terceiros. Também não garante que todo stream permaneça disponível ou que todo item aceite pelo formato seja reproduzível no dispositivo.

## Addon de demonstração

A aba **Explorar** possui **Instalar addon demonstrativo**. Ele é instalado de um ZIP embutido no app, não precisa de rede para adquirir os dados e contém apenas títulos/sinopses/horários fictícios. URLs `example.org/ruivotv-demo/...` são intencionalmente inexistentes e não fornecem mídia. O card fica marcado como DEMONSTRAÇÃO. Use-o para exercitar instalação, ativação, catalogação e remoção, não para validar reprodução real.

## Ciclo de desenvolvimento e teste

1. Crie manifesto e catálogo seguindo o schema e use IDs estáveis.
2. Publique JSON e streams/imagens autorizados em HTTPS público. Hospede manifesto/update/catalog no mesmo host e liste hosts de mídia exatos.
3. Para repositório, publique um índice JSON. Se empacotar ZIP, mantenha só os arquivos da allowlist e calcule o hash do ZIP final com `sha256sum`.
4. Adicione o repositório, sincronize, abra a prévia e confirme conscientemente. Teste ativar, desativar, atualizar, remover e perder a rede com um catálogo previamente carregado.
5. Teste playback apenas com uma stream autorizada de fato e compatível com Android/WebView do aparelho.

## Compatibilidade e limites conhecidos

O app usa o player e o agregador existentes, sem executar plugin dentro do WebView principal. Mapeia canais, filmes, episódios/séries, busca global, metadados, logos autorizados e origem do addon. O gerenciamento exige o host Android; abrir apenas o HTML no navegador de desktop permite visualizar a interface, mas não realiza instalações nativas. Não há auth/OAuth/headers privados. Não declare a integração Brazuca concluída sem uma API compatível e testes reais.


## Inspeção e adaptadores Kodi

A aba **Compatibilidade** aceita uma URL HTTPS direta para inspeção estática de um ZIP Kodi. A análise lê `addon.xml`, dependências, pontos de extensão e referências estáticas a APIs Kodi em arquivos Python limitados. O ZIP não é extraído para o armazenamento do app; Python, JavaScript, `plugin://` e outros conteúdos executáveis nunca são carregados. URLs encontradas no texto-fonte são apenas contadas, não visitadas nem tratadas como streams.

O scanner impõe limites de 5 MiB comprimidos, 8 MiB descompactados, 256 entradas, um `addon.xml` de até 128 KiB e no máximo 100 arquivos Python/2 MiB de fonte inspecionada. Rejeita caminhos absolutos, barras invertidas, segmentos `.`/`..`, nomes repetidos, ZIP inválido, mais de um manifesto e DTD/entidades XML. O download usa a validação HTTPS pública, porta 443, DNS sem endereços privados e sem redirecionamentos já aplicada pelo host. SHA-256 é calculado para identificar o pacote analisado; não é assinatura nem prova de autoria.

`KodiAddonAdapter` classifica o pacote como **incompatível** por padrão. Só classifica como **adaptado** quando existe no código do host um `RuivoAddonProvider` compilado para aquele ID e com evidência registrada de fonte/API documentada e autorizada. O ZIP não pode registrar, baixar ou substituir providers. O rótulo **nativo** é reservado a addons que usam o contrato JSON do RuivoTV. As permissões exibidas para addons nativos continuam limitadas ao host e a análise Kodi não concede permissões Android.

`RuivoAddonProvider` transforma dados estruturados já obtidos pelo transporte controlado do host em `RuivoContentItem`. O modelo canônico inclui ID, nome, filme/série/episódio/canal, sinopse, imagem, capa, ano, gêneros, temporadas, episódios e, somente quando autorizado, URL HTTPS de reprodução. `toRuivoCatalogRows()` projeta itens para o formato do agregador/player existente; o adaptador não cria player alternativo. A allowlist de host e as validações do catálogo continuam obrigatórias antes de carregar imagens ou reproduzir.

No momento não há provider Kodi registrado, inclusive para Brazuca Play. A tela informa o motivo e desativa a integração. O scanner é uma triagem estática, não uma auditoria completa de segurança/licença, prova de autorização, nem teste de compatibilidade de cada stream. A integração só muda após revisão humana das fontes/termos e implementação de um provider específico no código confiável do app.
