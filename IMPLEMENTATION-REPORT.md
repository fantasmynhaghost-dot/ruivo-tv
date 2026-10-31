# Relatório de implementação — adaptadores Kodi no Ruivo TV

**Data:** 31/10/2026  
**Escopo:** implementação na cópia de trabalho `ruivo-android-addons`. O ZIP de origem compartilhado pelo usuário foi preservado.

## Resultado em resumo

Foi adicionada uma camada de compatibilidade para **inspecionar** ZIPs Kodi sem executar o Kodi nem o código dos addons. A interface oferece uma aba **Compatibilidade** com análise por URL HTTPS, exibe dependências, pontos de extensão, APIs Kodi encontradas, licença declarada, hash e limites da análise. A classificação é **incompatível** por padrão; só muda para **adaptado** quando um provider compilado no host declara evidência revisada de fonte/API documentada e autorizada.

A análise concreta da variante Brazuca Play Matrix v2.1.4 terminou como **incompatível e desativada**. Ela depende do runtime e do protocolo de plugins Kodi: o dispatcher processa modos, cria listagens, usa player/playlist e retorna streams por `setResolvedUrl` ou `xbmc.Player().play`; playback também envolve resolvers, legendas, proxy e propriedades InputStream. A revisão ampliada confirmou que uma shim parcial não seria execução fiel e que executar Python de terceiros dentro do app não oferece isolamento aceitável. O `addon.xml` examinado não declara licença de software, e não foi confirmada API/feed público autorizado para terceiros. O Ruivo TV não executa o plugin, não inventa endpoints e não importa fontes de reprodução. A seção Brazuca na UI explica que o uso original requer Kodi oficial em separado. O novo requisito de APK único com runtime Kodi foi avaliado, mas **não foi implementado**.

## Arquivos criados

- `app/src/main/java/br/sintonia/tv/addons/KodiAddonAdapter.java` — inspeção estática de `addon.xml`/Python em memória, limites de tamanho/ZIP, detecção de APIs e classificação com provider autorizado.
- `app/src/main/java/br/sintonia/tv/addons/RuivoAddonProvider.java` — contrato para providers mantidos e compilados no código confiável do Ruivo TV; não existe carregamento de classes de ZIP, URL, WebView ou repositório.
- `app/src/main/java/br/sintonia/tv/addons/RuivoContentItem.java` — formato canônico para filme, série, episódio e canal, metadados e projeção de linhas para o agregador/player atual. Uma URL de reprodução exige HTTPS e autorização declarada.
- `tests/KodiAddonAdapterSelfTest.java` e `tests/run_kodi_adapter_selftest.sh` — testes portáteis JVM do scanner e do modelo canônico.
- `KODI-ADAPTER-RESEARCH.md` — fontes públicas revisadas e resultado da auditoria estática Brazuca.
- `BRAZUCA-DIRECT-EXECUTION-REPORT.md` — avaliação do dispatcher, resolvers, riscos, limites de teste e alternativa para execução direta.
- `KODI-NATIVE-INTEGRATION-ASSESSMENT.md` — arquitetura Kodi Android oficial, situação do host Ruivo e bloqueios para a meta de APK único.
- `IMPLEMENTATION-REPORT.md` — este relatório.
- Capturas da nova aba em `brand-assets/previews/ruivotv-kodi-compatibility.png`, `brand-assets/previews/ruivotv-kodi-compatibility-mobile.png` e `brand-assets/previews/ruivotv-kodi-compatibility-phone.png`.

As capturas usam um payload ilustrativo “Exemplo Kodi” apenas para validar a composição visual; não representam a saída do scanner Brazuca. A classificação real do ZIP público está registrada em [`KODI-ADAPTER-RESEARCH.md`](KODI-ADAPTER-RESEARCH.md) e [`TEST-REPORT.md`](TEST-REPORT.md).

## Arquivos alterados

- `app/src/main/java/br/sintonia/tv/addons/AddonManager.java` — operação `kodi-analyze` na ponte interna; baixa o ZIP por HTTPS usando a validação/restrição de destino existente, roda o scanner fora da UI thread e devolve metadados/hash. A análise não persiste nem instala o pacote.
- `app/src/main/assets/index.html` — nova aba Compatibilidade, estado e resultado correlacionado por request ID, distinção visual nativo/adaptado/incompatível e aviso Brazuca atualizado para deixar explícito que o Kodi oficial precisa ser usado em separado. Os controles existentes permanecem no fluxo v1.
- `ADDON_CONTRACT.md` e `LEIA-ME.md` — documentação do isolamento do contrato v1 e da análise estática.
- `KODI-ADAPTER-RESEARCH.md`, `BRAZUCA-DIRECT-EXECUTION-REPORT.md` e `KODI-NATIVE-INTEGRATION-ASSESSMENT.md` — pesquisa, mapeamento estático e avaliação da integração nativa requerida.
- `TEST-REPORT.md` — resultados e testes de dispositivo pendentes; a sintaxe dos três blocos JS foi repetida após atualizar a UI.

Não foram adicionadas permissões Android. O allowlist estrito dos ZIPs declarativos v1 continua aceitando somente `manifest.json`, `catalog.json` e `README.md`; ZIPs Kodi **não** podem ser instalados pelo instalador de addons nativo. Não foi alterada a implementação do player. O modelo canônico oferece projeção para o formato já usado pelo catálogo/player; nenhum provider Kodi está registrado hoje.

## Segurança e compatibilidade real

A análise Kodi tem limites de 5 MiB comprimidos, 8 MiB descompactados, 256 entradas, 128 KiB para `addon.xml`, até 100 arquivos Python e 2 MiB de fonte Python. Não grava entradas em disco; rejeita traversal, paths absolutos, barras invertidas, manifests ambíguos e DTD/entidades XML. O código do addon nunca é importado ou executado. URLs encontradas no texto são apenas contadas; não são visitadas.

A inspeção é estática: não entende completamente fluxo de controle, código gerado, aliases complexos, ofuscação, dependências carregadas em runtime ou comportamento do servidor. Um resultado não é certificação de segurança, autorização/licença, disponibilidade ou reprodução. Provider futuro deverá usar fonte documentada e autorizada, transporte HTTPS do host, validação de domínio/capacidades e player único do Ruivo TV.

## Testes e limitações de entrega

Os resultados estão detalhados em [`TEST-REPORT.md`](TEST-REPORT.md). Foram aprovados os 9 testes Python de artefatos, 24 checks JVM, compilação das classes Java contra Android API 34, sintaxe de 3 blocos JS, parse dos JSON/XML e renderização visual da aba em 1440×1000, 800×1280 e 390×844. O scanner rodou sobre o ZIP Matrix público e retornou `incompatible` sem executar arquivos.

O `gradle assembleDebug` **não gerou APK** neste sandbox ARM64: o AAPT2 x86_64 do Android Gradle Plugin não inicia (`Syntax error: "(" unexpected`) em `processDebugResources`. O código Java compila, mas o APK precisa ser construído no Android Studio ou em runner Linux x86_64, por exemplo no workflow existente do GitHub Actions.

Não foram testados em dispositivo/emulador: WebView Android real, Android TV/DPAD, fluxo HTTPS de ponta a ponta, instalação/atualização/remoção de addons, cache offline, rollback nem reprodução de um stream autorizado. O app não deve ser considerado testado para distribuição até esses testes e um build de APK concluído.
