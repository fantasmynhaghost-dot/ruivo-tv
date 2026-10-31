# Relatório de testes — Ruivo TV Addons + Compatibilidade Kodi

**Data:** 31/10/2026. Os testes de código foram executados no sandbox Linux; não havia aparelho/emulador Android conectado.

## Testes executados

| Verificação | Resultado |
| --- | --- |
| `python -m unittest discover -s tests -v` | **9 testes passaram**: schemas, contratos inválidos, hash/allowlist do ZIP demo e conteúdo fictício |
| `tests/run_kodi_adapter_selftest.sh` | **24 checks JVM passaram**: manifesto/dependências/APIs Kodi, padrões dinâmicos, nenhum Python executado, provider autorizado, projeção filme/série/episódio para o catálogo/player, autorização HTTPS e ZIP/XML hostil |
| Scanner aplicado ao ZIP público `plugin.video.BrazucaPlay.Matrix`, v2.1.4 | Classificado **incompatível** sem execução: extensão `xbmc.python.pluginsource`, dependências Kodi/Python, referências `xbmc`, `xbmcaddon`, `xbmcgui`; licença não declarada no `addon.xml`; zero permissões concedidas |
| `javac` para todas as classes Java do app contra Android API 34 | Passou; apenas avisos de API deprecada e opções do compilador |
| `node --check` | Os 3 blocos JavaScript embutidos em `index.html` passaram, inclusive após a atualização do texto Brazuca |
| JSON e XML | Schemas, dados demo, manifesto Android e ícone adaptativo foram parseados com sucesso |
| Chromium headless | Aba Compatibilidade renderizada e inspecionada em 1440×1000, 800×1280 e 390×844; o cartão “Exemplo Kodi” nas capturas usa payload visual fictício, não resultado de um scan real |
| `gradle assembleDebug` | **Não gerou APK neste sandbox**. O AAPT2 fornecido pelo Android Gradle Plugin é binário Linux x86_64 e falha ao iniciar no host ARM64 (`Syntax error: "(" unexpected`) na tarefa `processDebugResources`. As classes Java compilam, mas isso não substitui o build do APK. |

## Ainda pendente em Android/Android TV

A interface web foi renderizada em Chromium, mas não foi executada dentro de WebView em dispositivo. Permanecem pendentes: build em runner compatível Linux x86_64/Android Studio; instalação do APK; foco DPAD/controle remoto; leitura HTTPS real da URL de ZIP; testes runtime de addon por manifesto/repositório; ativação/desativação; atualização/rollback; remoção/cache; reprodução autorizada no player padrão; validação em Android TV e versões mínimas.

O workflow existente de GitHub Actions pode fazer o build em runner Linux apropriado depois que os arquivos forem enviados ao repositório. Até um build/install real, **não distribua como APK validado**.

## Limites do scanner Kodi

O scanner é análise estática com limites de tamanho/entradas. Não prova ausência de comportamento malicioso, não determina a situação jurídica de conteúdo, não valida que URLs vistas no código funcionam e não testa streams. Ele não extrai arquivos, não executa Python, não visita URLs de origem e não instala pacote Kodi. A auditoria aprofundada do dispatcher/resolvers também foi estática e está em [`BRAZUCA-DIRECT-EXECUTION-REPORT.md`](BRAZUCA-DIRECT-EXECUTION-REPORT.md); consulte ainda [`KODI-ADAPTER-RESEARCH.md`](KODI-ADAPTER-RESEARCH.md) e [`IMPLEMENTATION-REPORT.md`](IMPLEMENTATION-REPORT.md). Brazuca Play continua desativado até existir API/feed documentado e autorização verificável.

## Como repetir

```bash
pip install -r tests/requirements.txt
python -m unittest discover -s tests -v
tests/run_kodi_adapter_selftest.sh
```
