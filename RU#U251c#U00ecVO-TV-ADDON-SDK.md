# RUÍVO TV ADDON SDK

**Contrato v1 implementado: addons declarativos JSON.** Esta documentação cobre o código do RuivoTV nesta pasta. Não existe runtime de plugins executáveis.

## Princípio do host

O RuivoTV preserva a interface, o catálogo unificado, o controle remoto e o player. Addons fornecem metadados, catálogos e URLs HTTPS autorizadas. O host não carrega JavaScript, HTML, DEX, APK, scripts nem plugins Kodi dentro do WebView. Um ZIP é apenas um contêiner seguro de manifesto/catálogo/README, nunca uma extensão de código.

O plugin `skyrisk/brazucaplay` foi feito para Kodi. Não há feed/API de terceiros documentado/implementado aqui, nem fontes ou credenciais Brazuca inventadas. A integração exige API/feed compatível documentado e autorizado pelo mantenedor, além de testes reais.

## Organização de um addon declarativo

Um desenvolvedor pode manter uma estrutura semelhante a esta e publicar seus arquivos em hospedagem HTTPS própria:

```text
meu-addon/
├── README.md
├── ruivo-addon.json
└── catalog.json
```

O app não clona nem instala a pasta de código-fonte. O usuário instala um manifesto HTTPS diretamente ou adiciona um índice de repositório HTTPS com referência ao manifesto ou ZIP declarativo.

## Manifesto

O exemplo completo de manifesto está em `app/src/main/assets/addons/demo/manifest.json`; a demonstração usa `catalog.path: "package:catalog.json"` porque os dois JSON estão empacotados localmente. Para serviço remoto, use caminho iniciando com `/` no mesmo host do manifesto.

Campos principais:

- `schemaVersion: 1`, `apiVersion: 1`, `id` estável, `name`, `description`, `version`, `developer`;
- `compatibility.minAppVersion` obrigatório e `maxAppVersion` opcional;
- `capabilities`: `live`, `movies`, `series`, `search`;
- `categories`: rótulos opcionais para organização;
- `permissions`: `catalog.read`, `stream.play` e, quando um ícone externo é declarado, `images.read`;
- `allowedHosts`: hosts exatos HTTPS para imagens e streams; o host do manifesto é incluído automaticamente;
- `updateUrl` e `releaseNotes` opcionais;
- `catalog.path`, `catalog.itemsPath` e caminhos declarativos em `catalog.fields`.

Não há funções implementáveis por addon para `initialize()`, `getCatalog()`, `search()` ou `getStream()`. Esses nomes representam conceitos de um host/adaptador confiável: no v1 o RuivoTV executa a sequência internamente com JSON e não carrega código de terceiros. A busca é local/global sobre itens já mapeados; não existe endpoint remoto `search()` configurável.

Consulte `addon-manifest.schema.json` e `ADDON_CONTRACT.md` para os limites e regras detalhadas. O validador Java aplica verificações equivalentes essenciais, sem executar um motor JSON Schema genérico.

## Catálogo e mapeamento

O catálogo remoto deve retornar JSON com a lista em `itemsPath` (padrão `items`). O mapeamento mais simples:

```json
{
  "items": [
    {
      "id": "canal-exemplo-01",
      "title": "Canal de exemplo",
      "type": "live",
      "stream": { "url": "https://media.example.org/live/canal.m3u8" },
      "category": "Exemplos"
    }
  ]
}
```

Nesse caso `fields` aponta `id`, `name: title`, `kind: type`, `url: stream.url` e `category: category`. `kind` aceita `live`, `movie`/`film` e `episode`/`series`. Episódios precisam mapear o nome da série. Campos extras aceitos incluem logo, número, duração, ano, nota, descrição e agenda `[ ["HH:mm", "Programa"] ]`.

Os IDs internos são compostos por `addonId:sourceId`. Mantenha IDs de origem estáveis para preservar favoritos e histórico. O catálogo ativo é agregado às telas existentes, filtros e busca. Desativar um addon retira seus itens sem tocar nos demais; remover limpa também o cache desse addon.

## Repositórios próprios

Publique um JSON conforme `repository-index.schema.json`, por exemplo `examples/ruivo-repository.example.json`:

```json
{
  "schemaVersion": 1,
  "name": "Meu repositório",
  "items": [
    {
      "id": "org.exemplo.addon",
      "name": "Addon de exemplo",
      "version": "1.0.0",
      "developer": "Equipe",
      "description": "Catálogo autorizado.",
      "category": "Filmes",
      "manifestUrl": "https://meu-dominio.example/ruivo-addon.json"
    }
  ]
}
```

Cada item usa **exatamente** `manifestUrl` ou `packageUrl` mais `sha256`. Índice e URL de artefatos devem responder diretamente por HTTPS público, sem redirecionamentos. O app aceita até 100 itens por índice e até 512 KiB por índice. Adicionar um item da lista abre a prévia; sincronizar não instala automaticamente.

Para publicar ZIP, hospede-o e informe `packageUrl`/`sha256`. Recalcule o hash após qualquer alteração (`sha256sum demo-addon.zip`). SHA-256 protege a comparação de bytes com o valor publicado, mas não autentica o dono do repositório; não há assinatura digital atualmente.

## ZIP declarativo compatível

Estrutura exata permitida:

```text
manifest.json
catalog.json
README.md   # opcional
```

`catalog.path` no manifesto deve ser `package:catalog.json`. O app só lê esses arquivos em memória, sob limites de 5 MiB comprimidos/768 KiB descompactados e tamanho por arquivo, recusa diretórios/caminhos, nomes duplicados, `..`, barras e arquivos extras. Não use executáveis, imagens incorporadas, subpastas ou código.

## Testar no RuivoTV

1. Gere manifesto/catalog.json com conteúdo e streams autorizados sob hosts HTTPS públicos.
2. Valide os JSON contra schemas e confira IDs, campos mapeados, domínio e compatibilidade.
3. Adicione o URL de repositório em **Addons → Repositórios**; sincronize e confira itens/categorias.
4. Abra prévia; revise desenvolvedor, versão, permissões, hosts, URL e aviso de confiança; confirme.
5. Ative addon, confira canais/filmes/séries/episódios e busca; teste catálogo offline depois de um carregamento bem-sucedido.
6. Publique versão superior, use **Atualizações** e confirme changelog/versão. Teste a versão anterior após falha de rede/validação.
7. Teste player com stream realmente autorizada num Android/WebView compatível. Os dados demo não reproduzem mídia.

## Limites e validação neste workspace

- Sem autenticação/OAuth, senha, headers privados ou execução de código externo.
- HTTPS, hash e checks de domínio não equivalem a uma identidade assinada.
- Integração Brazuca não existe; é necessário endpoint oficial/compatível e autorização.
- O pacote demo (`app/src/main/assets/addons/demo/`) contém nomes fictícios e URLs inexistentes `example.org`; serve apenas para testar instalação local, catálogo, filtros e remoção.
- Este workspace não tem dispositivo Android conectado. Compilação completa do APK, instalação e playback real devem ser confirmados em Android Studio/device antes de distribuição.
