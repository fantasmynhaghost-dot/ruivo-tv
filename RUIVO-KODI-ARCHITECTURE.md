# Ruivo TV — Arquitetura de integração Kodi

**Decisão:** Interface 100% Ruivo TV; Kodi é runtime multimídia interno, sem aplicativo Kodi separado instalado.
**Status:** arquitetura definida; integração nativa ainda não implementada nem validada em APK.

## 1. Objetivo e limites

- Uma instalação Ruivo TV por dispositivo Android.
- Interface de navegação, catálogo, configurações e identidade visual controladas pelo Ruivo TV.
- Kodi compilado a partir do código-fonte como runtime nativo incorporado ao mesmo pacote.
- Reprodução de mídia executada pelo runtime Kodi.
- Addons declarativos Ruivo permanecem separados dos addons Kodi.
- Addons Kodi só são executados pelo runtime Python/API real do Kodi e após revisão de licença, origem, dependências e segurança.

"Um APK" significa um pacote Ruivo TV que contém o código e bibliotecas necessários para a ABI do dispositivo. Para distribuição eficiente, serão produzidos APKs por ABI (ou splits de AAB); um APK universal poderá ser oferecido separadamente se o tamanho for aceitável. Não existe suporte a APK Android em Tizen/webOS.

## 2. Decisão de arquitetura

### Opção escolhida: Ruivo como shell + Kodi como atividade nativa interna

O app atual mantém a `MainActivity` Ruivo como launcher e interface principal. O código Kodi é incorporado ao mesmo pacote Android, com uma atividade nativa interna de reprodução. A transição para reprodução ocorre dentro do mesmo app/pacote; não é um `Intent` para o Kodi instalado separadamente.

O caminho inicial de integração é um fork controlado do empacotamento Android do Kodi, não tentar importar o Kodi como AAR ou presumir que existe uma API oficial de embedding. A documentação oficial descreve a compilação do Kodi Android como aplicativo nativo, com toolchain NDK e empacotamento próprios; não documenta um SDK/AAR oficial para hospedar o player numa Activity arbitrária.

### Limite visual importante

A interface de navegação será a interface Ruivo existente. A tela de reprodução/OSD do Kodi precisa de uma skin customizada Ruivo TV para manter a mesma identidade. Até existir uma API de player embeddable suportada e testada, não se deve prometer que o renderer/OSD Kodi pode ser substituído diretamente por uma View da WebView. O objetivo visual A é alcançado com shell Ruivo + skin de reprodução Ruivo, sem menus ou branding Kodi expostos ao usuário.

## 3. Diagrama lógico

```text
RuivoTvApplication
  |
  +-- MainActivity (launcher; UI Ruivo HTML/CSS/JS existente)
  |     |
  |     +-- RuivoBridge (interface tipada; sem acesso arbitrário à WebView)
  |     +-- AddonManager (addons declarativos Ruivo)
  |     +-- CatalogRepository / AccountRepository / SettingsRepository
  |     +-- PlaybackCoordinator
  |             |
  |             +-- PlaybackRequest (URI, headers permitidos, mime, posição)
  |             +-- KodiRuntimeController
  |                       |
  |                       +-- Kodi Native Activity (mesmo applicationId/APK)
  |                               +-- Kodi C/C++ core + FFmpeg
  |                               +-- Python runtime + Kodi addon APIs
  |                               +-- Kodi skin Ruivo (player/OSD)
  |
  +-- Android platform: audio focus, surfaces, storage, network, lifecycle
```

## 4. Módulos e responsabilidades

| Módulo | Responsabilidade | Regra |
|---|---|---|
| `app-shell` | launcher, navegação, ciclo de vida | não contém lógica de playback |
| `ruivo-ui` | interface de marca e DPAD/touch | mantém o visual atual |
| `ruivo-domain` | modelos, catálogo, contas, favoritos | independente do Kodi |
| `ruivo-addon-host` | contrato de addons declarativos | nunca executa scripts de terceiros |
| `playback-contract` | pedido/estado/erro de reprodução | API pequena e versionada |
| `kodi-android-fork` | atividade, JNI, runtime e dependências Kodi | código upstream pinado |
| `ruivo-kodi-skin` | OSD, player e telas Kodi com marca Ruivo | sem branding Kodi na experiência |
| `native-packaging` | ABIs, assets, bibliotecas e assinatura | artefatos reprodutíveis |

## 5. Contrato de reprodução

A UI não chama funções JNI nem Kodi JSON-RPC diretamente. Ela usa `PlaybackCoordinator`.

### Pedido mínimo

- `requestId`: identificador único.
- `uri`: URI de mídia obtida de fonte autorizada.
- `mimeType`: quando conhecido.
- `headers`: somente cabeçalhos necessários e permitidos; nunca registrar tokens.
- `startPositionMs`: opcional.
- `subtitleUris`: lista opcional de legendas autorizadas.
- `contentId` e metadados de exibição.

### Estados

`IDLE -> PREPARING -> PLAYING <-> PAUSED -> STOPPING -> STOPPED`

Erros incluem `UNSUPPORTED_FORMAT`, `NETWORK`, `AUTH`, `DECODER`, `ADDON`, `RUNTIME` e `UNKNOWN`.

O canal de controle inicial será um bridge interno autenticado/restrito ao processo e ao pacote, ou um mecanismo de mensagens Android explícito entre Activities. JSON-RPC local só poderá ser habilitado em loopback, sem bind em interfaces de rede, sem credenciais embutidas e com ciclo de vida controlado. Não expor o endpoint de controle na LAN.

## 6. Addons: dois ecossistemas separados

### Addons Ruivo (existentes)
- Continuam usando o contrato declarativo v1 (`manifest.json`, `catalog.json`, `README.md`).
- Não executam Python, JavaScript remoto, APK ou binários baixados.
- Providers executáveis são código confiável compilado no app, com origem/API autorizada documentada.

### Addons Kodi
- Só podem executar no runtime Kodi real.
- O scanner `KodiAddonAdapter` continua sendo ferramenta de inspeção estática; não é executor.
- Antes de instalar/executar: validar licença, origem, hash/assinatura quando disponível, dependências, versão Kodi/API, ABI de addons binários e permissões/rede.
- Não instalar automaticamente addons arbitrários de terceiros.
- Código Python de addon executa com as permissões do APK hospedeiro; não há sandbox Android isolada por addon. Portanto, execução exige confiança e revisão.
- Addons que dependem de APIs privadas, binários ausentes, resolvers não licenciados ou fontes sem autorização ficam incompatíveis/desativados.

## 7. Estratégia de ABI e distribuição

Prioridade de build:
1. `arm64-v8a` — alvo primário.
2. `armeabi-v7a` — legado, se dependências e versão Kodi selecionada suportarem.
3. `x86_64` — experimental; testar em emuladores/Chromebooks compatíveis.
4. `x86` — legado e baixa prioridade.

O guia atual do Kodi recomenda Android NDK r28c (28.2.13676358), Android SDK e JDK 17+; seguir a matriz exata da tag/commit fixada. O guia alerta que x86/x86_64 não são mantidos com a mesma garantia. Não prometer suporte universal até os testes em hardware.

Estratégia de distribuição recomendada:
- CI produz APKs por ABI, ou Android App Bundle com splits.
- Um APK universal é opcional e pode ficar muito grande por incluir todas as bibliotecas.
- Mesmo package name, assinatura consistente e versionCode coordenado.
- Nunca reutilizar chave de assinatura de debug para release.

## 8. Plano de implementação em checkpoints

### Checkpoint 0 — baseline reproduzível
- Criar branch e tag do estado atual.
- Fixar tag/commit Kodi (não compilar `master` flutuante).
- Criar inventário de licenças/SBOM e registrar patches locais.
- Provisionar runner Linux x86_64 com SDK, NDK r28c, CMake, JDK 17+ e espaço em disco.

**Aceite:** build do Kodi Android upstream sem alterações gera APK instalável no runner.

### Checkpoint 1 — Ruivo shell + Kodi no mesmo pacote
- Incorporar o empacotamento Android e as bibliotecas Kodi no projeto/fork Ruivo.
- Unificar `applicationId`, recursos, manifests, Activities, providers e permissões sem colisões.
- Main launcher permanece Ruivo; Activity Kodi é interna/não exportada.
- Testar start/stop, back, pause/resume, rotação, áudio e superfície nativa.

**Aceite:** instalar apenas Ruivo TV; abrir a Home Ruivo; iniciar reprodução autorizada; retornar à Home; nenhum Kodi separado instalado.

### Checkpoint 2 — visual A
- Criar skin Kodi Ruivo para a tela de playback/OSD.
- DPAD e touchscreen; layout responsivo phone/tablet/TV.
- Testar foco, legendas, trilhas de áudio, seek e mensagens de erro.

**Aceite:** nenhuma tela ou marca Kodi aparece no fluxo normal do usuário.

### Checkpoint 3 — bridge e catálogo
- Implementar `PlaybackCoordinator` e `PlaybackRequest`.
- Ligar catálogo Ruivo ao runtime e sincronizar estados de playback.
- Remover dependência de player legado/WebView onde duplicada.
- Telemetria local sem URLs sensíveis, tokens ou cabeçalhos secretos.

**Aceite:** iniciar, pausar, retomar, buscar, trocar legenda/faixa, parar e retornar à UI com estado correto.

### Checkpoint 4 — addons compatíveis
- Validar execução de addon de teste próprio, criado/licenciado para o projeto.
- Validar APIs Python e dependências; bloquear addons incompatíveis.
- Manter a Central Ruivo como UX e usar o runtime Kodi para execução.
- Revisar segurança, permissões, licenças e política de atualização.

**Aceite:** addon de teste autorizado executa no runtime e produz resultado verificável; addon incompatível não executa.

### Checkpoint 5 — ABI e homologação
- Produzir builds ABI por ABI.
- Testar em dispositivos reais e emuladores.
- Medir RAM, CPU, temperatura, tempo de inicialização e consumo.
- Assinar release em CI seguro.

**Aceite:** matriz publicada só com modelos/versões efetivamente testados.

## 9. Estado atual do ZIP recebido

O projeto atual tem:
- `MainActivity` baseada em Android `Activity` + `WebView`.
- `AddonManager` para addons declarativos.
- `KodiAddonAdapter` que inspeciona ZIPs Kodi estaticamente e não executa Python.
- Gradle app com `compileSdk 34`, `minSdk 21`, Java 17.
- Manifesto com `usesCleartextTraffic=true`; WebView habilita mixed content e cookies de terceiros.
- Nenhum NDK/`externalNativeBuild`, Kodi JNI, biblioteca Kodi ou Activity Kodi integrada.

O ambiente anterior não concluiu `assembleDebug` devido ao AAPT2 x86_64 não executar no host ARM64. Isso não prova defeito do código; exige runner Android compatível.

## 10. Segurança e hardening antes de release

- Remover `usesCleartextTraffic=true` salvo exceção documentada e restrita.
- Desativar `MIXED_CONTENT_ALWAYS_ALLOW`; usar HTTPS e allowlist explícita.
- Não aceitar cookies de terceiros globalmente sem necessidade.
- Restringir navegação e bridge WebView a assets locais/origem própria.
- Não encaminhar `Authorization`, cookies ou Referer indiscriminadamente a hosts arbitrários.
- Validar redirects, DNS/IP privados e destinos de download para evitar SSRF/rebinding no fluxo de addons.
- Guardar tokens em armazenamento seguro; não registrar URLs assinadas.
- Desabilitar depuração WebView em release.
- Revisar licenças Kodi e de cada dependência, addon, fonte, artwork e codec.
- Fornecer código-fonte e avisos GPL correspondentes ao fork distribuído.
- Manter chaves de release fora do repositório e do chat.

## 11. Critério de conclusão

Não declarar "Kodi integrado" até haver APK instalado num Android real que:
1. inicia diretamente na Home Ruivo;
2. reproduz um arquivo/stream de teste autorizado pelo runtime Kodi incorporado;
3. permite pausa, retomada, seek, áudio e legendas;
4. retorna à Home Ruivo sem encerrar o processo;
5. não depende de Kodi separado;
6. passa testes de lifecycle e memória;
7. identifica corretamente a ABI e versão Android suportadas.

## Referências oficiais

- Kodi Android build guide: https://github.com/xbmc/xbmc/blob/master/docs/README.Android.md
- Kodi Android development: https://kodi.wiki/view/Android_development
- Kodi Android app overview: https://kodi.wiki/view/Android
- Kodi CMake build system: https://github.com/xbmc/xbmc/blob/master/cmake/README.md
- Kodi Android application source: https://github.com/xbmc/xbmc/tree/master/tools/android/packaging
