# Ruivo TV para Android / HY300

A marca “Ruivo TV” usa a logo enviada no wordmark da interface e no favicon. O launcher Android tem ícones adaptativos/fallback e banner para Android TV baseados no mesmo símbolo.

## Arquitetura híbrida Kodi

A decisão arquitetural e os checkpoints para a integração nativa estão em [`RUIVO-KODI-ARCHITECTURE.md`](RUIVO-KODI-ARCHITECTURE.md). O documento distingue o estado atual (scanner estático, sem runtime Kodi) dos marcos ainda não implementados e define a interface Ruivo como shell principal.

## Gerar o APK

Abra esta pasta no Android Studio e use **Build → Build APK(s)**. O APK debug deverá aparecer em `app/build/outputs/apk/debug/app-debug.apk`. Também é possível publicar a pasta em um repositório GitHub e usar o workflow `Build APK` existente. Este sandbox não possui um dispositivo Android conectado; confira o build e playback no Android Studio/device antes de distribuir.

## Instalar no HY300

Copie o APK para um pendrive e abra-o com o gerenciador de arquivos, permitindo fontes desconhecidas quando solicitado, ou instale com `adb install app-debug.apk`.

## Usar playlist M3U

No app, abra **Ajustes → Playlist M3U**. Cole o link de uma lista `.m3u`/`.m3u8` ou seu conteúdo e toque em **Importar**. Canais, logos e categorias ficam salvos no aparelho. Use somente listas e streams que você tem autorização para acessar.

## Central de Addons

A Central tem as abas **Explorar**, **Instalados**, **Instalar por URL**, **Repositórios**, **Atualizações** e **Compatibilidade**. O usuário pode adicionar índices JSON hospedados por desenvolvedores independentes. Instalação por manifesto ou pacote mostra uma prévia antes da confirmação; o app valida compatibilidade, campos, HTTPS, hosts e, para ZIPs declarativos, SHA-256. Addons ficam desativados após instalar e podem ser ativados, atualizados ou removidos individualmente.

O contrato v1 é declarativo. Um pacote de instalação só pode conter `manifest.json`, `catalog.json` e opcionalmente `README.md`; scripts, APKs, plugins Kodi e JavaScript de terceiros **não são executados**. A aba Compatibilidade pode inspecionar estaticamente um ZIP Kodi por HTTPS, sem instalar/extrair seus arquivos e sem seguir URLs do código. Só providers específicos compilados no app, com fonte/API autorizada documentada, podem ser classificados como **adaptados**. Todo catálogo reproduzível continua usando o player padrão do Ruivo TV; há cache privado por addon para catálogos carregados com sucesso e fallback offline.

Leia [`ADDON_CONTRACT.md`](ADDON_CONTRACT.md), [`RUÍVO-TV-ADDON-SDK.md`](RUÍVO-TV-ADDON-SDK.md), [`addon-manifest.schema.json`](addon-manifest.schema.json) e [`repository-index.schema.json`](repository-index.schema.json). Um exemplo de índice está em [`examples/`](examples/README.md). O addon demo embutido contém apenas dados fictícios e URLs de stream não funcionais. Veja [`TEST-REPORT.md`](TEST-REPORT.md) para os testes executados e os que ainda precisam de Android/emulador.

O projeto `skyrisk/brazucaplay` é um plugin Python de vídeo para Kodi. A integração está explicitamente desativada porque o addon exige o runtime/API Kodi, não tem uma API/feed externo autorizado confirmado e não declara licença no manifesto examinado. O app não executa o plugin nem inventa endpoints. Veja [`BRAZUCA-DIRECT-EXECUTION-REPORT.md`](BRAZUCA-DIRECT-EXECUTION-REPORT.md) para a avaliação detalhada e [`KODI-NATIVE-INTEGRATION-ASSESSMENT.md`](KODI-NATIVE-INTEGRATION-ASSESSMENT.md) para o estado e os bloqueios da meta de um APK único com Kodi interno; consulte também [`IMPLEMENTATION-REPORT.md`](IMPLEMENTATION-REPORT.md) e [`KODI-ADAPTER-RESEARCH.md`](KODI-ADAPTER-RESEARCH.md).

## Canais manuais e compatibilidade

Edite `app/src/main/assets/channels.json` para cadastrar streams autorizados e gere o APK novamente. Se a interface sair desalinhada no HY300, atualize o Android System WebView do aparelho.

## Controles

Setas/OK navegam. Voltar sai do player. CH+/CH− trocam canais quando disponíveis, 0–9 digitam o número do canal, a tecla amarela/Menu favorita, e Play/Pause controla a reprodução. A tecla vermelha/Delete remove um item.
