# Auditoria pública para compatibilidade Kodi — Ruivo TV

**Data da verificação:** 31/10/2026. Inspeção estática somente; nenhum script, binário ou addon foi executado.

## Resultado

A documentação oficial do Kodi descreve `addon.xml` como manifesto com ID, nome, versão, autor, dependências (`requires/import`) e extensões (`extension point`). Esse arquivo identifica a estrutura e a integração esperada com o runtime Kodi, mas não fornece por si só um catálogo de títulos ou uma API externa de reprodução. [1]

O repositório público Brazuca contém pacotes ZIP de addons e um índice Kodi XML. A página oficial descreve como instalar/adicionar o repositório ao Kodi e caracteriza o addon como agregador de links; o XML lista IDs, versões, dependências, extensão de vídeo e URLs para os próprios índices/ZIPs do repositório. O material consultado não documenta uma API/feed de catálogo de mídia para terceiros. Um índice de distribuição de addons Kodi não foi tratado como API de conteúdo. [2] [3] [4]

O ZIP Matrix consultado foi baixado somente para análise estática: `plugin.video.BrazucaPlay.Matrix.zip`. O manifesto interno indica ID `plugin.video.BrazucaPlay.Matrix`, versão `2.1.4`, extensão `xbmc.python.pluginsource` e dependências `script.module.six`, `script.module.kodi-six`, `script.module.requests` e `script.module.simplejson`, mais dependências opcionais de inputstream. A inspeção estática da cópia mostrou 7 arquivos Python, com referências às APIs `xbmc`, `xbmcaddon` e `xbmcgui` em 2 arquivos. Não foi encontrado campo `<license>` no `addon.xml` do pacote analisado. [4] [5]

A API pública de metadados do GitHub retorna `license: null` para o repositório. Isso significa que os metadados revisados não registram uma licença formal; não é uma conclusão jurídica sobre autoria ou direitos. A página do projeto descreve o uso e inclui um aviso de responsabilidade sobre links agregados, mas não especifica uma API externa autorizada para integrar catálogo/reprodução. [2] [3]

## Decisão no Ruivo TV

A adaptação do Brazuca Play permanece **desativada/incompatível**. O Ruivo TV não executa nem instala o código Kodi, não usa URLs internas do plugin como endpoints e não importa mídia ou fontes de reprodução sem documentação/autorização verificável. Para alterar essa decisão será necessária uma fonte/API externa documentada, autorização do titular dos dados/streams e testes reais de reprodução no player padrão.

## Referências

[1]: https://kodi.wiki/view/Addon.xml "Kodi Add-on XML manifest"
[2]: https://api.github.com/repos/skyrisk/brazucaplay "GitHub repository metadata for skyrisk/brazucaplay"
[3]: https://skyrisk.github.io/brazucaplay/ "Brazuca Community Repo official project page"
[4]: https://raw.githubusercontent.com/skyrisk/brazucaplay/master/addons/repo/addons_matrix.xml "Brazuca Play Matrix add-on repository index"
[5]: https://raw.githubusercontent.com/skyrisk/brazucaplay/master/addons/plugin.video.BrazucaPlay.Matrix.zip "Brazuca Play Matrix ZIP inspected statically"


## Execução direta e alternativas Android

A documentação Android do repositório oficial Kodi descreve a construção do aplicativo completo: SDK e NDK específicos, compilação de dependências, configuração CMake, build do Kodi e empacotamento com o alvo `apk`. A documentação consultada não oferece uma API Android suportada para incorporar Kodi como uma pequena biblioteca ou um `PlayerView` dentro de outro app. Ela documenta produzir e instalar o APK do Kodi. [6]

A documentação do Kodi confirma que addons Python executam no interpretador embutido e usam APIs do host. Um plugin de vídeo produz listagens para a interface Kodi e, ao selecionar um item, pode entregar uma URL para reprodução ou chamar novamente o plugin para resolver o stream. Portanto, uma lista de nomes de módulos Python observados estaticamente não é uma camada compatível por si só: seria necessário reproduzir as semânticas de navegação, objetos GUI, callbacks, settings, módulos dependentes e resolução de mídia que o addon realmente usa. [7]

O método JSON-RPC `Addons.ExecuteAddon` pode iniciar um addon instalado **no próprio Kodi em execução**, com ID e parâmetros, e requer a permissão `ExecuteAddon`. Ele é uma interface de controle do servidor Kodi; não transfere o runtime, a tela do addon ou o player para dentro do Ruivo TV. Usá-lo implica manter Kodi instalado/em execução, normalmente como aplicativo/processo separado. [8]

**Comparação técnica:** embutir/forkar Kodi exigiria manter a aplicação e o stack nativo Kodi (NDK/C++, dependências compiladas, Python embutido, empacotamento, atualizações e compatibilidade ARM), além de revisar as condições GPL e as permissões do addon antes de redistribuir. Uma camada de compatibilidade exigiria um interpretador Python isolado e a implementação fiel das APIs e dos contratos Kodi; uma shim parcial que só declare os módulos importáveis não garante que o plugin navegue ou resolva streams, e aumenta a superfície de ataque. Nenhuma dessas opções é um patch pequeno ou seguro para o Ruivo TV atual.

**Conclusão atual:** não foi implementada execução direta nem uma shim de execução. A alternativa fiel, quando o usuário tem autorização para instalar e usar o addon, é executar o pacote no Kodi oficial, com o Kodi responsável pelo runtime e player. A integração JSON-RPC pode ser investigada depois como controle opcional de uma instância Kodi autorizada, mas não atende ao requisito de manter o addon rodando dentro do APK Ruivo TV.

## Mapeamento aprofundado do addon Brazuca

A leitura estática do dispatcher revelou uma sequência que não pode ser reproduzida só com um catálogo: `sys.argv` → análise de parâmetros → menu/listagens por `CHIndex`, `getData`/`getItems` e `addDir`/`addLink` → nova chamada do plugin por item → resolução e retorno ao host. `mode==0` inicializa o menu; `mode==1` processa seleções/listagens; `mode==11` prepara playlist/player; `mode==16` chama `player_normal`. A entrega de playback usa `xbmcplugin.setResolvedUrl` ou `xbmc.Player().play`, dependendo do caminho.

Além de `xbmc`, `xbmcaddon`, `xbmcgui` e `xbmcplugin`, foram observados usos de player/playlist, diálogo/teclado/progresso, built-ins, monitor, labels do sistema e caminhos Kodi. Os fluxos de reprodução têm resolvers, headers e legendas, propriedades InputStream, proxy local e tratamento de HLS. O `codec.py` também é ofuscado/compactado e contém implementação de proxy/DNS local; `default.py` o usa em caminhos de reprodução. Essas camadas foram decodificadas apenas para inspeção textual, nunca executadas.

O ZIP inclui `database.py` com operações que consultam e alteram o banco `Addons33.db` do perfil Kodi, mas não foi localizada importação desse módulo no caminho principal de `default.py`; não se conclui que o fluxo normal o chame. `cloudrequest` aparece importado mas não acompanha o pacote nem está declarado no manifesto; sua disponibilidade em uma instalação Kodi é desconhecida. A auditoria não instalou dependências nem fez chamadas de rede. A versão `2.1.4` e a nota “KODI 16 >” não substituem testes por versão. Detalhamento em [`BRAZUCA-DIRECT-EXECUTION-REPORT.md`](BRAZUCA-DIRECT-EXECUTION-REPORT.md). [5]

## Consequências para controle externo e segurança

`Addons.ExecuteAddon` executa um addon pelo ID e parâmetros numa instância Kodi; exige a permissão `ExecuteAddon`, mas não incorpora runtime nem player ao Ruivo TV. [8] A interface web do Kodi é desativada por padrão e, quando ativada pelo usuário, permite configurar porta e credenciais. [9]

A documentação Android recomenda não carregar código dinamicamente de fora do APK e informa que esse código executa com as permissões do app. Por isso, executar Python do addon no processo do Ruivo ou propor uma shim parcial foi rejeitado. Kodi declara GPL-2.0-or-later e informa que outras licenças também podem aplicar-se; isso não concede automaticamente direitos sobre o addon ou as fontes de mídia. [10] [11]

**Alternativa fiel:** usar o Kodi oficial separadamente. Um botão explícito de handoff pode ser considerado futuramente, mas não foi implementado. Uma integração interna só deve ser reconsiderada com API/feed documentado e autorização verificável do titular.

## Referências adicionais

[6]: https://raw.githubusercontent.com/xbmc/xbmc/master/docs/README.Android.md "Kodi official Android build instructions"
[7]: https://kodi.wiki/view/About_Add-ons "Kodi add-on Python runtime and plugin behavior"
[8]: https://kodi.wiki/view/JSON-RPC_API/v13.5 "Kodi JSON-RPC API Addons.ExecuteAddon"
[9]: https://kodi.wiki/view/Web_interface "Kodi Web interface, disabled by default"
[10]: https://developer.android.com/privacy-and-security/security-tips "Android Security Tips: dynamic code loading and app permissions"
[11]: https://github.com/xbmc/xbmc/blob/master/LICENSE.md "Kodi source license"
