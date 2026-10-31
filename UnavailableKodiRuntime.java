package br.sintonia.tv.playback;

/** Controlador usado enquanto o runtime Kodi real não está embutido: falha de forma explícita, sem simular reprodução. */
public final class UnavailableKodiRuntime implements KodiRuntimeController {
    private EventSink sink;
    @Override public boolean isAvailable() { return false; }
    @Override public void setEventSink(EventSink s) { sink = s; }
    @Override public void play(PlaybackRequest r) { if (sink != null) sink.onError(PlaybackError.RUNTIME); }
    @Override public void pause() {} @Override public void resume() {} @Override public void stop() {}
    @Override public void seekTo(long p) {} @Override public void selectAudioTrack(int i) {} @Override public void selectSubtitleTrack(int i) {}
}
