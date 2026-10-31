package br.sintonia.tv.playback;

import java.util.List;

public final class PlaybackSnapshot {
    public final String requestId; public final PlaybackState state; public final PlaybackError error;
    public final long positionMs, durationMs; public final List<String> audioTracks, subtitleTracks;
    public final int selectedAudio, selectedSubtitle; // -1 = desligado/indefinido
    public PlaybackSnapshot(String requestId, PlaybackState state, PlaybackError error, long positionMs, long durationMs,
                            List<String> audioTracks, List<String> subtitleTracks, int selectedAudio, int selectedSubtitle) {
        this.requestId = requestId; this.state = state; this.error = error; this.positionMs = positionMs; this.durationMs = durationMs;
        this.audioTracks = List.copyOf(audioTracks); this.subtitleTracks = List.copyOf(subtitleTracks);
        this.selectedAudio = selectedAudio; this.selectedSubtitle = selectedSubtitle;
    }
    public static PlaybackSnapshot idle() { return new PlaybackSnapshot("", PlaybackState.IDLE, null, 0, 0, List.of(), List.of(), -1, -1); }
}
