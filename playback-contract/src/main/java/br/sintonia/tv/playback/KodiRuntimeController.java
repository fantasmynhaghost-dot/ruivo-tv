package br.sintonia.tv.playback;

/**
 * Fronteira com o runtime Kodi nativo (JNI/Activity interna). Implementações reais só existirão
 * quando o fork do Kodi estiver compilado e embutido; este módulo NÃO contém motor de reprodução.
 * Eventos retornam por {@link EventSink}, sempre em qualquer thread; o coordinator serializa.
 */
public interface KodiRuntimeController {
    interface EventSink {
        void onState(PlaybackState state);
        void onError(PlaybackError error);
        void onProgress(long positionMs, long durationMs);
        void onTracks(java.util.List<String> audio, java.util.List<String> subtitles, int selAudio, int selSub);
    }
    boolean isAvailable();
    void setEventSink(EventSink sink);
    void play(PlaybackRequest request);
    void pause();
    void resume();
    void stop();
    void seekTo(long positionMs);
    void selectAudioTrack(int index);
    void selectSubtitleTrack(int index); // -1 desativa
}
