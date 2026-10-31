# Brazuca Play no Ruivo TV — auditoria de execução direta

**Data:** 31/10/2026  
**Escopo:** avaliar se o addon Kodi original pode ser executado dentro do APK Ruivo TV e registrar a alternativa tecnicamente segura. O ZIP foi lido e descompactado como dados; camadas Base64/zlib foram decodificadas somente para inspeção textual. Nenhum código do addon foi importado, avaliado, executado ou conectado à rede.

## 1. Conclusão técnica

**Não é possível oferecer execução fiel do Brazuca Play dentro do Ruivo TV com a arquitetura atual sem trazer o runtime Kodi ou reimplementar uma parcela extensa dele.** O addon é um plugin Kodi, não uma biblioteca Python isolada nem uma API de catálogo. Ele depende do protocolo de navegação e retorno de playback do Kodi, de objetos GUI/player, settings, monitoramento, módulos auxiliares e extensões InputStream.

A compatibilidade continua **incompatível e desativada**. O app conserva sua arquitetura v1 segura: inspeciona o pacote, mas não instala nem executa Python de terceiros. A seção **Brazuca Play** na aba Compatibilidade agora esclarece esse limite e orienta que a execução do pacote original seja feita separadamente no Kodi oficial. Não foi adicionada uma shim que finja compatibilidade, nem fonte de mídia, URL de reprodução ou endpoint inventado.

## 2. O que o pacote original contém

- O manifesto identifica `plugin.video.BrazucaPlay.Matrix`, versão **2.1.4**, entrada `default.py` e extensão `xbmc.python.pluginsource`. A nota do manifesto diz “KODI 16 >”, mas não fixa versões mínimas nas dependências; isso **não comprova** compatibilidade testada com qualquer versão atual. O código contém ramificações para Python 2/3 e versões do Kodi. [1] [3]
- O menu e o fluxo estático incluem seções de canais ao vivo, filmes, séries, desenhos, doramas, animes e novelas, além de opções auxiliares. Há caminho de conteúdo +18 com controles próprios; qualquer adaptação teria de preservar essa restrição, sem assumir que o comportamento funciona.
- Dependências obrigatórias declaradas: `script.module.six`, `script.module.kodi-six`, `script.module.requests` e `script.module.simplejson`. `inputstream.ffmpegdirect` e `script.module.inputstreamhelper` são marcadas como opcionais. O código configura também `inputstream.adaptive`, que não consta como dependência obrigatória no manifesto.
- O fluxo Kodi lê `sys.argv`, separa parâmetros como `mode`/URL/nome, monta diretórios com `addDirectoryItem` e encerra cada lista com `endOfDirectory`. A reprodução retorna ao host por `setResolvedUrl` ou por `xbmc.Player().play`. Não é uma função que simplesmente devolve uma URL ao app.
- A rotina de playback escolhe entre resolvers conforme o item, podendo usar headers, legendas, playlist, proxy local e configurações específicas de InputStream. O `codec.py` também é ofuscado e contém implementação de proxy/DNS local; `default.py` o usa em caminhos de reprodução. Essa parte não foi executada nem validada.
- O ZIP também contém `database.py` com funções que acessam e alteram o banco `Addons33.db` do perfil Kodi. Não foi detectada importação desse módulo pelo `default.py` no fluxo estático principal; portanto, não afirmo que essas funções sejam chamadas. Mesmo assim, o Ruivo TV não deve conceder ao addon acesso a dados do Kodi.
- `cloudrequest` é importado por caminhos do addon, mas não está incluído no ZIP nem declarado no manifesto; sua disponibilidade no Kodi do usuário não foi verificada. O código ainda referencia bibliotecas de resolução adicionais. Não foi possível comprovar de forma estática que todas as dependências estejam disponíveis em um ambiente específico.

A documentação Kodi confirma que addons Python rodam no runtime do Kodi e usam APIs do host. [4] Os detalhes desta seção são observações da inspeção estática do ZIP, não resultados de instalação ou reprodução. [1]

## 3. Avaliação das opções

### A. Colocar o Kodi completo dentro do Ruivo TV

O caminho mais fiel seria manter uma integração/fork do Kodi completo: código nativo C/C++, NDK/SDK, dependências compiladas, interpretador Python, addons binários, interface, player e processo de empacotamento/atualização. A documentação oficial orienta construir e empacotar o aplicativo Kodi completo; não apresenta um `PlayerView` ou SDK pequeno e suportado para hospedar o runtime dentro da tela de outro app. [5]

Isso seria um projeto de engenharia substancial — na prática, um produto híbrido ou fork — e não um adaptador pontual. Também exigiria revisão de licenças, distribuição e obrigações de código-fonte: o projeto Kodi declara GPL-2.0-or-later e informa que outras licenças podem aplicar-se a partes do código. Isso **não** concede automaticamente direitos sobre o addon ou os streams. O manifesto/metadata do Brazuca revisado não declara licença; isso não é conclusão jurídica sobre autoria ou autorização. [1] [2] [8]

### B. Executar o Python do addon com uma “Kodi API Shim”

Uma shim teria de reproduzir muito além de nomes de módulos: ciclo de vida do plugin, handles e argumentos, listagens e callbacks, `ListItem`, settings, diálogos, monitoramento, player, built-ins, paths especiais, resolvers, legendas, proxy e propriedades de InputStream. Uma implementação parcial poderia exibir categorias, mas falharia ao navegar ou resolver reprodução; não seria correto classificá-la como execução do original.

A segurança também não fecha: o pacote contém código Python comprimido/ofuscado e padrões de carregamento dinâmico. Se executado dentro do processo do Ruivo TV, o código compartilha as permissões do app. A documentação Android desaconselha carregar código externo dinamicamente e alerta que ele executa com as mesmas permissões do APK. Um processo separado com o mesmo pacote/UID não cria, por si só, uma fronteira de segurança. [9]

### C. Controlar uma instalação Kodi separada por JSON-RPC

`Addons.ExecuteAddon` pode pedir que uma instância Kodi **já em execução** execute um addon instalado, com permissão `ExecuteAddon`. Ele controla Kodi; não move seu runtime, interface ou player para dentro do Ruivo TV, nem transforma automaticamente a saída do addon em uma fonte para o player do Ruivo. [6]

O servidor web do Kodi fica desativado por padrão e precisa ser habilitado pelo usuário; as configurações incluem porta e credenciais. Expor uma interface de controle em rede sem configuração consciente e autenticação não é uma boa integração padrão. [7]

## 4. Alternativa recomendada

Para conservar a funcionalidade original, a opção viável hoje é **instalar e usar o addon separadamente no Kodi oficial**, mantendo o Ruivo TV independente. Um futuro botão explícito “Abrir Kodi” poderia apenas transferir o usuário para o app Kodi quando instalado; isso não instalaria o addon silenciosamente nem garantiria que ele esteja configurado. Esse botão ainda não foi implementado.

Se a meta obrigatória for catálogo e reprodução dentro do Ruivo TV, o caminho correto é obter do titular do Brazuca uma API/feed documentado, autorização escrita de integração e direitos de uso das fontes de mídia. Com isso, pode-se criar um provider nativo compilado no Ruivo, mapear as categorias para `RuivoContentItem` e encaminhar somente streams autorizados ao player existente. Sem essas evidências, a integração deve continuar desativada.

## 5. Testes e limites

**Feito:** revisão estática do manifesto, módulos do ZIP, dispatcher, dependências declaradas/observadas e fluxo de navegação/resolução; atualização da mensagem da seção Brazuca na UI.

**Não feito:** executar o addon, instalar dependências, consultar os servidores referenciados pelo código, resolver links, testar reprodução, integrar JSON-RPC, iniciar Kodi, testar em Android/Android TV ou produzir APK. Não há resultado de reprodução para declarar como aprovado. A limitação do build APK nesta máquina ARM64 permanece registrada no [`TEST-REPORT.md`](TEST-REPORT.md).

## Referências

[1]: https://raw.githubusercontent.com/skyrisk/brazucaplay/master/addons/plugin.video.BrazucaPlay.Matrix.zip "ZIP Brazuca Play Matrix revisado estaticamente"
[2]: https://api.github.com/repos/skyrisk/brazucaplay "Metadata pública do repositório Brazuca Play"
[3]: https://kodi.wiki/view/Addon.xml "Manifesto addon.xml do Kodi"
[4]: https://kodi.wiki/view/About_Add-ons "Runtime e comportamento de addons Kodi"
[5]: https://raw.githubusercontent.com/xbmc/xbmc/master/docs/README.Android.md "Instruções oficiais de build do Kodi para Android"
[6]: https://kodi.wiki/view/JSON-RPC_API/v13.5#Addons.ExecuteAddon "JSON-RPC Addons.ExecuteAddon"
[7]: https://kodi.wiki/view/Web_interface "Configuração da interface web Kodi"
[8]: https://github.com/xbmc/xbmc/blob/master/LICENSE.md "Licença do projeto Kodi"
[9]: https://developer.android.com/privacy-and-security/security-tips "Android Security Tips — código carregado dinamicamente e sandbox"
