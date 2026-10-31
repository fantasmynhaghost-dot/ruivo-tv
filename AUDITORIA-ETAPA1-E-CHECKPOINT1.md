# Ruivo TV — Auditoria (Etapa 1) e Checkpoint 1

Data: 31/10/2026

## Inventário
- 60 arquivos, 1,9 MB. Android app único (`br.sintonia.tv`), Java 17, AGP 8.5.2, compileSdk/targetSdk 34, minSdk 21.
- UI: `assets/index.html` (1742 linhas, HTML/JS) em WebView; player atual = `<video>` + hls.js/YouTube (motor web, não Kodi).
- `MainActivity` (198 l.): WebView + proxy de requisições + teclas de TV. `AddonManager` (1013 l.): addons declarativos Ruivo.
- `KodiAddonAdapter` (373 l.): **somente análise estática** de ZIPs Kodi; não executa nada. Mantido como está.
- Testes: 9 Python (schemas/ZIP demo), 24 checks JVM do adapter. Sem testes instrumentados.
- Não há NDK, CMake, JNI, ABI filters nem código Kodi no projeto.

## Problemas encontrados (não corrigidos ainda; exigem teste em dispositivo)
1. `usesCleartextTraffic=true`, `MIXED_CONTENT_ALWAYS_ALLOW`, cookies de terceiros ligados.
2. Proxy do `MainActivity.intercept` encaminha requisições XHR arbitrárias (risco SSRF/rede interna) — restringir por allowlist.
3. Sem `externalNativeBuild`/`abiFilters`; sem release signing config.
4. `.github/workflows/build.yml` roda `gradle assembleDebug` na raiz: funciona só no runner Linux x86_64.

## Viabilidade do Kodi — fatos verificados hoje
- Git do GitHub alcançável; `dl.google.com`, Maven Central, Google Maven e Gradle distributions **bloqueados** neste ambiente → sem Android SDK/NDK, **não é possível gerar APK aqui**.
- Tag estável escolhida: **`21.3-Omega`** (commit `a3a448d26b8d560a65655dab2cd122994dc4e146`). Clonada e inspecionada (164 MB).
- O README Android dessa tag exige **NDK r20b (CI usa r21e)** + JDK 17+ — diferente do r28c citado no documento anterior, que vale para `master`. Matriz deve seguir a tag fixada.
- O empacotamento oficial (`tools/android/packaging`) gera app completo (NativeActivity), sem AAR de embedding, como o documento anterior já apontava. Caminho: fork do packaging + `depends` cross-compilado por ABI (horas de CPU, dezenas de GB; precisa runner x86_64 dedicado).

## Checkpoint 1 entregue (testado)
Módulo `playback-contract` (Java puro, sem motor falso):
- `PlaybackRequest` validado (só https/file/content; sem credenciais na URI; headers em allowlist; sem CRLF; `toString` sem segredos).
- `PlaybackState`/`PlaybackError`/`PlaybackSnapshot`.
- `KodiRuntimeController`: fronteira para o runtime nativo futuro.
- `UnavailableKodiRuntime`: enquanto o Kodi não está embutido, `play()` termina em `ERROR/RUNTIME` — nunca simula reprodução.
- `PlaybackCoordinator`: transições validadas, eventos ilegais ignorados, seek limitado, faixas de áudio/legenda, snapshot recuperável.
- Resultado: `tests/run_playback_contract_selftest.sh` → **25 ok, 0 falhas**. Módulo ligado ao Gradle (`settings.gradle.kts`, dependência do app); **o Gradle não foi executado aqui** (rede bloqueada), apenas `javac`.

## Não feito / sem evidência
APK, runtime Kodi, skin, execução de addons Kodi, ABIs, testes em dispositivo, assinatura. Nada disso está "pronto".

## Próximos passos
2. Runner x86_64 com SDK + NDK r21e (ou CI GitHub Actions) → validar build do Kodi 21.3 sem alterações (Etapa 2).
3. Fork de `tools/android/packaging` como `kodi-android-fork/` dentro do mesmo applicationId; Activity Kodi interna não exportada (Etapa 3).
4. Implementar `KodiRuntimeController` real via JNI/Intent interno e ligar à UI por bridge tipada.

## Como obter o APK (sem eu precisar de SDK)
1. Crie um repositório no GitHub e envie o conteúdo desta pasta (a raiz do repo deve conter `settings.gradle.kts`).
2. Aba Actions → **Ruivo TV APK** roda sozinho a cada push: executa os testes JVM e gera `app-debug.apk` (artefato `ruivo-tv-debug-apk`). É o Ruivo TV atual (WebView), **sem Kodi**.
3. Aba Actions → **Kodi baseline** (manual): compila o Kodi 21.3-Omega arm64 sem alterações. Escrito a partir do guia oficial; **não executado**, pode precisar de ajustes.
