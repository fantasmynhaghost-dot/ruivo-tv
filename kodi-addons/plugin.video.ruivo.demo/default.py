# Ruivo TV Demo — addon de exemplo. Licença: GPL-2.0-or-later.
# Vídeos de demonstração: Blender Foundation, CC-BY 3.0 (https://durian.blender.org, https://orange.blender.org).
import sys
from urllib.parse import parse_qsl, urlencode

import xbmcgui
import xbmcplugin

HANDLE = int(sys.argv[1])
BASE = sys.argv[0]
VIDEOS = {
    "bbb": ("Big Buck Bunny", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
    "ed": ("Elephants Dream", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
}


def list_videos():
    for key, (title, _url) in VIDEOS.items():
        item = xbmcgui.ListItem(label=title)
        item.setProperty("IsPlayable", "true")
        item.setInfo("video", {"title": title})
        xbmcplugin.addDirectoryItem(HANDLE, BASE + "?" + urlencode({"action": "play", "id": key}), item, False)
    xbmcplugin.endOfDirectory(HANDLE)


def play(key):
    entry = VIDEOS.get(key)
    if entry is None:
        xbmcplugin.setResolvedUrl(HANDLE, False, xbmcgui.ListItem())
        return
    item = xbmcgui.ListItem(label=entry[0], path=entry[1])
    xbmcplugin.setResolvedUrl(HANDLE, True, item)


def run(query):
    params = dict(parse_qsl(query.lstrip("?")))
    if params.get("action") == "play":
        play(params.get("id", ""))
    else:
        list_videos()


run(sys.argv[2] if len(sys.argv) > 2 else "")
