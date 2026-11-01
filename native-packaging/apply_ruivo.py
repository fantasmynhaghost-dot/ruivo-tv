#!/usr/bin/env python3
"""Aplica o Ruivo TV sobre a árvore de build gerada do Kodi (tools/android/packaging).
Uso: apply_ruivo.py <KODI_BUILD_DIR> <RUIVO_REPO_DIR>
Falha de forma explícita se qualquer trecho esperado não for encontrado (Kodi fixado em 21.3-Omega)."""
import os, re, shutil, sys

build, repo = map(os.path.abspath, sys.argv[1:3])
pk = os.path.join(build, "tools/android/packaging")
app = os.path.join(repo, "app/src/main")
work = os.path.join(build, "ruivo-overlay")

def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def write(p, s):
    with open(p, "w", encoding="utf-8") as f: f.write(s)
def must(cond, msg):
    if not cond: sys.exit("apply_ruivo: " + msg)

# ---- overlay: java, assets, res ----
shutil.rmtree(work, ignore_errors=True)
for d in ("java", "assets", "res"): os.makedirs(os.path.join(work, d))
shutil.copytree(os.path.join(app, "java"), os.path.join(work, "java"), dirs_exist_ok=True)
shutil.copytree(os.path.join(repo, "playback-contract/src/main/java"), os.path.join(work, "java"), dirs_exist_ok=True)
shutil.copytree(os.path.join(app, "assets"), os.path.join(work, "assets"), dirs_exist_ok=True)
shutil.copytree(os.path.join(app, "res"), os.path.join(work, "res"), dirs_exist_ok=True)
must(os.path.exists(os.path.join(work, "java/br/sintonia/tv/MainActivity.java")), "MainActivity ausente")
must(os.path.exists(os.path.join(work, "java/br/sintonia/tv/playback/PlaybackCoordinator.java")), "contrato ausente")

# ---- AndroidManifest.xml ----
mp = os.path.join(pk, "xbmc/AndroidManifest.xml")
m = read(mp)
# 1) Kodi deixa de ser launcher e deixa de ser exportado: só o Ruivo o abre (intent explícito interno)
launcher = re.compile(r'\s*<intent-filter>\s*<action android:name="android.intent.action.MAIN" />.*?</intent-filter>', re.S)
m, n = launcher.subn("", m, count=1); must(n == 1, "filtro MAIN do Splash não encontrado")
for act in (".Splash", ".Main"):
    pat = re.compile(r'(<activity\s+android:name="%s".*?)android:exported="true"' % re.escape(act), re.S)
    m, n = pat.subn(r'\1android:exported="false"', m, count=1); must(n == 1, "exported de %s não encontrado" % act)
# 2) atividade Ruivo como launcher
ruivo = '''<activity
            android:name="br.sintonia.tv.MainActivity"
            android:label="Ruivo TV"
            android:exported="true"
            android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|uiMode"
            android:screenOrientation="fullSensor"
            android:theme="@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
        '''
m, n = re.subn(r'(<activity\s+android:name="\.Splash")', ruivo + r'\1', m, count=1); must(n == 1, "Splash não encontrado")
# 3) identidade do aplicativo
for old, new in (('android:banner="@drawable/banner"', 'android:banner="@drawable/ruivo_tv_banner"'),
                 ('android:icon="@drawable/ic_launcher"', 'android:icon="@mipmap/ic_launcher"'),
                 ('android:logo="@drawable/banner"', 'android:logo="@drawable/ruivo_tv_banner"'),
                 ('android:label="@string/app_name"', 'android:label="Ruivo TV"')):
    must(old in m, "atributo ausente: " + old); m = m.replace(old, new, 1)
write(mp, m)

# ---- build.gradle: applicationId Ruivo (namespace Kodi permanece, pois o JNI depende dele) ----
gp = os.path.join(pk, "xbmc/build.gradle")
g = read(gp)
g, n = re.subn(r'applicationId\s+"[^"]+"', 'applicationId "br.sintonia.tv"', g, count=1); must(n == 1, "applicationId não encontrado")
write(gp, g)

# ---- Makefile: copiar overlay no passo java ----
kp = os.path.join(pk, "Makefile")
k = read(kp)
anchor = "\t@cp -R xbmc/src/* xbmc/java/$(APP_PACKAGE_DIR)/\n"
must(anchor in k, "passo 'java' do Makefile mudou")
add = ("\tcp -R %s/java/. xbmc/java/\n\tmkdir -p xbmc/assets && cp -R %s/assets/. xbmc/assets/\n\tcp -R %s/res/. xbmc/res/\n") % (work, work, work)
write(kp, k.replace(anchor, anchor + add, 1))
print("apply_ruivo: ok")
