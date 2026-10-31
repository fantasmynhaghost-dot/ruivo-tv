package br.sintonia.tv.playback;

/** Estados do contrato: IDLE -> PREPARING -> PLAYING <-> PAUSED -> STOPPING -> STOPPED; ERROR é terminal até novo play. */
public enum PlaybackState { IDLE, PREPARING, PLAYING, PAUSED, STOPPING, STOPPED, ERROR }
