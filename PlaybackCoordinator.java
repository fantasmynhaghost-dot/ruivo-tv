package br.sintonia.tv.playback;

import java.util.List;

/**
 * Único ponto pelo qual a UI Ruivo controla a reprodução. Valida transições, descarta eventos de
 * requisições antigas, mantém snapshot recuperável e nunca expõe JNI/JSON-RPC à UI.
 */
public final class PlaybackCoordinator implements KodiRuntimeController.EventSink {
    public interface Listener { void onSnapshot(PlaybackSnapshot snapshot); }

    private final KodiRuntimeController runtime;
    private final Listener listener;
    private PlaybackSnapshot snap = PlaybackSnapshot.idle();

    public PlaybackCoordinator(KodiRuntimeController runtime, Listener listener) {
        this.runtime = runtime; this.listener = listener; runtime.setEventSink(this);
    }

    public synchronized PlaybackSnapshot snapshot() { return snap; }

    public synchronized void play(PlaybackRequest r) {
        if (!runtime.isAvailable()) { set(r.requestId, PlaybackState.ERROR, PlaybackError.RUNTIME, 0, 0); return; }
        if (snap.state == PlaybackState.PREPARING || snap.state == PlaybackState.PLAYING || snap.state == PlaybackState.PAUSED) runtime.stop();
        set(r.requestId, PlaybackState.PREPARING, null, r.startPositionMs, 0);
        runtime.play(r);
    }
    public synchronized void pause() { if (snap.state == PlaybackState.PLAYING) runtime.pause(); }
    public synchronized void resume() { if (snap.state == PlaybackState.PAUSED) runtime.resume(); }
    public synchronized void stop() {
        if (snap.state == PlaybackState.IDLE || snap.state == PlaybackState.STOPPED) return;
        set(snap.requestId, PlaybackState.STOPPING, null, snap.positionMs, snap.durationMs); runtime.stop();
    }
    public synchronized void seekTo(long ms) {
        if (snap.state != PlaybackState.PLAYING && snap.state != PlaybackState.PAUSED) return;
        long t = Math.max(0, snap.durationMs > 0 ? Math.min(ms, snap.durationMs) : ms); runtime.seekTo(t);
    }
    public synchronized void seekBy(long deltaMs) { seekTo(snap.positionMs + deltaMs); }
    public synchronized void selectAudioTrack(int i) { if (i >= 0 && i < snap.audioTracks.size()) runtime.selectAudioTrack(i); }
    public synchronized void selectSubtitleTrack(int i) { if (i >= -1 && i < snap.subtitleTracks.size()) runtime.selectSubtitleTrack(i); }

    // ---- eventos do runtime ----
    @Override public synchronized void onState(PlaybackState s) {
        if (!legal(snap.state, s)) return;
        set(snap.requestId, s, s == PlaybackState.ERROR ? snap.error : null, snap.positionMs, snap.durationMs);
    }
    @Override public synchronized void onError(PlaybackError e) { set(snap.requestId, PlaybackState.ERROR, e, snap.positionMs, snap.durationMs); }
    @Override public synchronized void onProgress(long p, long d) {
        if (snap.state != PlaybackState.PLAYING && snap.state != PlaybackState.PAUSED) return;
        set(snap.requestId, snap.state, null, Math.max(0, p), Math.max(0, d));
    }
    @Override public synchronized void onTracks(List<String> a, List<String> s, int sa, int ss) {
        snap = new PlaybackSnapshot(snap.requestId, snap.state, snap.error, snap.positionMs, snap.durationMs, a, s, sa, ss); notifyListener();
    }

    private static boolean legal(PlaybackState from, PlaybackState to) {
        switch (from) {
            case IDLE: return to == PlaybackState.PREPARING;
            case PREPARING: return to == PlaybackState.PLAYING || to == PlaybackState.STOPPED || to == PlaybackState.ERROR;
            case PLAYING: return to == PlaybackState.PAUSED || to == PlaybackState.STOPPING || to == PlaybackState.STOPPED || to == PlaybackState.ERROR;
            case PAUSED: return to == PlaybackState.PLAYING || to == PlaybackState.STOPPING || to == PlaybackState.STOPPED || to == PlaybackState.ERROR;
            case STOPPING: return to == PlaybackState.STOPPED;
            default: return false; // STOPPED/ERROR só saem via play()
        }
    }
    private void set(String id, PlaybackState s, PlaybackError e, long p, long d) {
        snap = new PlaybackSnapshot(id, s, e, p, d, s == PlaybackState.PREPARING ? List.of() : snap.audioTracks,
                s == PlaybackState.PREPARING ? List.of() : snap.subtitleTracks, snap.selectedAudio, snap.selectedSubtitle);
        notifyListener();
    }
    private void notifyListener() { if (listener != null) listener.onSnapshot(snap); }
}
