package br.sintonia.tv.playback;

import java.util.*;

/** Teste JVM sem dependências. O RecordingRuntime é um dublê de TESTE da fronteira; não é motor de reprodução. */
public final class PlaybackContractSelfTest {
    static int passed, failed;
    static void check(String n, boolean ok) { if (ok) passed++; else { failed++; System.out.println("FALHOU: " + n); } }
    static boolean rejects(Runnable r) { try { r.run(); return false; } catch (IllegalArgumentException e) { return true; } }

    static final class RecordingRuntime implements KodiRuntimeController {
        final List<String> calls = new ArrayList<>(); EventSink sink; boolean available = true;
        public boolean isAvailable() { return available; } public void setEventSink(EventSink s) { sink = s; }
        public void play(PlaybackRequest r) { calls.add("play"); } public void pause() { calls.add("pause"); }
        public void resume() { calls.add("resume"); } public void stop() { calls.add("stop"); }
        public void seekTo(long p) { calls.add("seek:" + p); } public void selectAudioTrack(int i) { calls.add("audio:" + i); }
        public void selectSubtitleTrack(int i) { calls.add("sub:" + i); }
    }

    public static void main(String[] a) {
        // validação de pedido
        check("https ok", PlaybackRequest.builder("r1", "https://exemplo.test/v.m3u8").build().uri.getHost().equals("exemplo.test"));
        check("http rejeitado", rejects(() -> PlaybackRequest.builder("r", "http://x.test/v")));
        check("javascript rejeitado", rejects(() -> PlaybackRequest.builder("r", "javascript:alert(1)")));
        check("plugin:// rejeitado", rejects(() -> PlaybackRequest.builder("r", "plugin://plugin.video.x/?a=1")));
        check("credenciais na URI", rejects(() -> PlaybackRequest.builder("r", "https://u:p@x.test/v")));
        check("header fora da lista", rejects(() -> PlaybackRequest.builder("r", "https://x.test/v").header("X-Evil", "1")));
        check("CRLF em header", rejects(() -> PlaybackRequest.builder("r", "https://x.test/v").header("Referer", "a\r\nb: c")));
        check("posição negativa", rejects(() -> PlaybackRequest.builder("r", "https://x.test/v").startPositionMs(-1)));
        PlaybackRequest sec = PlaybackRequest.builder("r2", "https://x.test/v?token=SEGREDO").header("Authorization", "Bearer SEGREDO").build();
        check("toString sem segredos", !sec.toString().contains("SEGREDO"));

        // runtime ausente: falha explícita
        PlaybackCoordinator none = new PlaybackCoordinator(new UnavailableKodiRuntime(), null);
        none.play(PlaybackRequest.builder("r3", "https://x.test/v").build());
        check("sem runtime => ERROR/RUNTIME", none.snapshot().state == PlaybackState.ERROR && none.snapshot().error == PlaybackError.RUNTIME);

        // ciclo de vida
        RecordingRuntime rt = new RecordingRuntime(); List<PlaybackState> seen = new ArrayList<>();
        PlaybackCoordinator c = new PlaybackCoordinator(rt, s -> seen.add(s.state));
        c.play(PlaybackRequest.builder("r4", "https://x.test/v.mp4").startPositionMs(5000).build());
        check("PREPARING", c.snapshot().state == PlaybackState.PREPARING && c.snapshot().positionMs == 5000);
        c.pause(); check("pause ignorado em PREPARING", !rt.calls.contains("pause"));
        rt.sink.onState(PlaybackState.PLAYING); rt.sink.onProgress(6000, 100000);
        rt.sink.onTracks(List.of("pt", "en"), List.of("pt"), 0, -1);
        check("PLAYING", c.snapshot().state == PlaybackState.PLAYING && c.snapshot().durationMs == 100000);
        c.seekBy(-10000); check("seek limitado a 0", rt.calls.contains("seek:0"));
        c.seekTo(999999); check("seek limitado à duração", rt.calls.contains("seek:100000"));
        c.selectAudioTrack(5); check("faixa inválida ignorada", !rt.calls.contains("audio:5"));
        c.selectAudioTrack(1); c.selectSubtitleTrack(-1); c.selectSubtitleTrack(0);
        check("troca de faixa", rt.calls.containsAll(List.of("audio:1", "sub:-1", "sub:0")));
        c.pause(); rt.sink.onState(PlaybackState.PAUSED); check("PAUSED", c.snapshot().state == PlaybackState.PAUSED);
        c.resume(); rt.sink.onState(PlaybackState.PLAYING);
        rt.sink.onState(PlaybackState.IDLE); check("transição ilegal ignorada", c.snapshot().state == PlaybackState.PLAYING);
        rt.sink.onError(PlaybackError.NETWORK);
        check("erro de rede", c.snapshot().state == PlaybackState.ERROR && c.snapshot().error == PlaybackError.NETWORK);
        rt.sink.onState(PlaybackState.PLAYING); check("ERROR só sai por play()", c.snapshot().state == PlaybackState.ERROR);
        c.play(PlaybackRequest.builder("r5", "https://x.test/v2.mp4").build());
        check("novo play limpa trilhas", c.snapshot().audioTracks.isEmpty() && c.snapshot().requestId.equals("r5"));
        rt.sink.onState(PlaybackState.PLAYING); c.stop();
        check("STOPPING", c.snapshot().state == PlaybackState.STOPPING); rt.sink.onState(PlaybackState.STOPPED);
        check("STOPPED", c.snapshot().state == PlaybackState.STOPPED);
        check("listener recebeu estados", seen.contains(PlaybackState.PREPARING) && seen.contains(PlaybackState.STOPPED));
        System.out.println("playback-contract: " + passed + " ok, " + failed + " falhas");
        System.exit(failed == 0 ? 0 : 1);
    }
}
