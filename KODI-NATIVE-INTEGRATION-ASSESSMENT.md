# Avaliação da integração nativa do Kodi no Ruivo TV

**Data:** 31/10/2026

## Conclusão técnica

Um APK único com Kodi real e Brazuca Play original é **teoricamente construível como fork/customização do Kodi Android**, mas não existe nas fontes oficiais consultadas uma API/AAR de embedding pronta para hospedar o runtime Kodi dentro de uma Activity do Ruivo TV. O caminho oficial compila o Kodi como aplicativo Android completo, com runtime nativo, JNI, interface, dependências, recursos e empacotamento próprios. Isso não prova impossibilidade de uma integração customizada; significa que ela precisa ser desenvolvida e comprovada, não presumida a partir de uma biblioteca ou shim. [1] [2] [3] [4]

Não iniciei uma integração fictícia nem adicionei código de fachada. Isso contrariaria a exigência de entregar o runtime Kodi verdadeiro. **Não há APK com Kodi integrado e o Brazuca não foi executado.**

## Estado confirmado do Ruivo TV

- `MainActivity.java` estende `Activity` e cria uma `WebView` em tela cheia. As teclas do controle remoto são encaminhadas ao JavaScript da interface (`MainActivity.java`, linhas 26–30, 105–155 e 169–197). Não há `NativeActivity`, JNI ou ciclo de vida do Kodi nessa classe.
- `app/build.gradle.kts` declara `com.android.application`, `compileSdk 34`, `minSdk 21`, `targetSdk 34` e Java 17. Não há `externalNativeBuild`, versão de NDK, filtros de ABI ou bibliotecas Kodi configuradas nesse arquivo. O plugin Android está na versão 8.5.2 (`build.gradle.kts` na raiz).
- O manifesto registra somente `MainActivity` como launcher. Não declara serviço, Activity ou biblioteca Kodi. As permissões existentes são rede e estado da rede. O app ainda declara `usesCleartextTraffic=true`; a `WebView` permite conteúdo misto e cookies de terceiros. Essas opções devem ser revistas antes de integrar um runtime que manipula rede e addons, mas não foram alteradas para evitar quebrar fluxos existentes sem testes.
- `AddonManager.java` aceita addons JSON declarativos e analisa ZIPs Kodi como dados. `KodiAddonAdapter.java` não executa Python, não resolve `plugin://` e não chama APIs Kodi; só extrai metadados e classifica compatibilidade. Não existe runtime Kodi nem provider Brazuca registrado.

## O que uma integração real exigiria

O Kodi Android oficial usa uma Activity nativa própria, carrega a biblioteca Kodi por JNI e renderiza por uma `SurfaceView` que encaminha callbacks de superfície ao código nativo. O processo oficial inicia o loop do Kodi e controla pausa, retomada, encerramento e saída no nível do app. O empacotamento Gradle é um aplicativo Android, não uma biblioteca de player declarada para reuso. Para manter um único APK com uma experiência Ruivo, seria necessário adaptar e manter esse empacotamento/runtime, as Activities e o ciclo de vida, os recursos e assets, as bibliotecas nativas por ABI, a entrada DPAD e a navegação entre a tela Ruivo e o Kodi. Depois seria preciso provar áudio, vídeo, pausa, retorno e encerramento nos aparelhos-alvo. [2] [3] [4] [5]

O guia atual do Kodi especifica toolchain nativa para compilar e empacotar o app: Android SDK, NDK r28c, JDK 17 ou superior, CMake e a cadeia de dependências do projeto. Ele documenta targets Android e a geração do APK Kodi, mas não um artefato AAR que possa ser ligado diretamente ao Ruivo TV. Os downloads oficiais consultados oferecem APKs ARMv7A e ARM64; suporte por ABI e por modelo de TV precisa ser testado no dispositivo-alvo. [1] [10]

O Brazuca Matrix 2.1.4 é um plugin Python Kodi, com dispatcher, APIs de listagem e retorno ao host Kodi, resolvers e configurações de InputStream. O exame estático aponta dependências declaradas e módulos/resolvers cuja disponibilidade não foi verificada; por exemplo, `cloudrequest` é importado mas não consta no ZIP nem no manifesto examinado. Nenhum resolver, cabeçalho, proxy, legenda ou stream foi testado em runtime. [9]

## Bloqueios atuais

O ambiente disponível neste momento é Linux ARM64, com JDK 21 e aproximadamente 7,8 GiB de memória. A inspeção não encontrou Android SDK, NDK, CMake, `sdkmanager` nem Gradle/Gradle Wrapper acessível. O relatório de testes anterior registra que `assembleDebug` não gerou APK porque o AAPT2 x86_64 falhou ao iniciar no sandbox ARM64. Não há dispositivo/emulador conectado para os testes Android/Android TV exigidos. A compilação completa do Kodi não foi iniciada.

Se um interpretador Python embutido carregar o addon dentro do Ruivo TV, isso não cria automaticamente uma sandbox Android separada. A documentação Android alerta que código carregado dinamicamente executa com as permissões do APK hospedeiro; esse limite precisa fazer parte do desenho de segurança e da revisão do pacote. [7]

O manifesto do Brazuca e os metadados públicos do repositório examinados não declaram licença de redistribuição. A licença Kodi é GPL-2.0-or-later e o projeto avisa que outras licenças podem aplicar-se aos componentes. Isso não concede automaticamente direitos sobre o addon, dependências, assets ou fontes de mídia. Antes de colocar o addon original dentro de um APK distribuível, é necessária revisão das licenças e autorização documentada do titular para a redistribuição/modificação pretendida. Isso não é um parecer jurídico. [6] [8]

O usuário também solicita APK release assinado. Não há keystore fornecido, nem se deve compartilhar a senha ou chave privada no chat. A assinatura de release precisa acontecer em ambiente controlado pelo usuário ou por CI seguro.

## Para continuar sem alegar conclusão prematura

1. Confirmar por escrito a autorização/licença para empacotar e modificar o Brazuca Play e identificar as licenças de suas dependências e assets.
2. Informar o modelo exato dos dispositivos-alvo, versão Android e ABI que devem ser suportados. Se o HY300 continua sendo o alvo, confirmar variante e versão do firmware.
3. Disponibilizar um runner Linux x86_64 com Android SDK/NDK r28c, JDK 17+, CMake e dependências necessárias, ou um repositório GitHub/CI autorizado para construir e publicar o código. O build atual não é um repositório Git e o sandbox ARM64 não iniciou o AAPT2.
4. Somente depois, fazer um checkpoint de build do runtime Kodi e testar no aparelho-alvo; então adaptar o empacotamento do Ruivo e integrar o addon no runtime verdadeiro. A entrega só pode ser chamada de funcional depois de navegação, controle remoto, áudio/vídeo, legendas, lifecycle e reprodução autorizada serem testados no dispositivo. A chave privada de release deve permanecer no ambiente seguro do usuário/CI.

Até esses requisitos serem satisfeitos, o estado honesto é: **fonte Ruivo TV com analisador estático de addons Kodi; sem runtime Kodi embutido, sem execução Brazuca e sem APK final dessa integração**.

## Referências

[1]: https://github.com/xbmc/xbmc/blob/master/docs/README.Android.md "Kodi Android build guide"
[2]: https://github.com/xbmc/xbmc/blob/master/tools/android/packaging/Makefile.in "Kodi Android packaging Makefile"
[3]: https://github.com/xbmc/xbmc/blob/master/tools/android/packaging/xbmc/src/Main.java.in "Kodi Android Main NativeActivity"
[4]: https://github.com/xbmc/xbmc/blob/master/tools/android/packaging/xbmc/src/XBMCMainView.java.in "Kodi Android render surface"
[5]: https://developer.android.com/reference/android/app/NativeActivity "Android NativeActivity"
[6]: https://github.com/xbmc/xbmc/blob/master/LICENSE.md "Kodi source license"
[7]: https://developer.android.com/privacy-and-security/security-tips "Android security and dynamically loaded code"
[8]: https://api.github.com/repos/skyrisk/brazucaplay/contents "Brazuca Play repository contents and published license check"
[9]: https://raw.githubusercontent.com/skyrisk/brazucaplay/master/addons/plugin.video.BrazucaPlay.Matrix.zip "Brazuca Play Matrix ZIP reviewed statically"
[10]: https://developer.android.com/ndk/guides/abis "Android NDK ABIs"
