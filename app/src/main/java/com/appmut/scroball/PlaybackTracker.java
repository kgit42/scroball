package com.appmut.scroball;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.util.Log;

import com.appmut.scroball.transforms.MetadataTransformers;

import java.util.HashMap;
import java.util.Map;

public class PlaybackTracker {

  private final ScrobbleNotificationManager scrobbleNotificationManager;
  private final Scrobbler scrobbler;
  private final MetadataTransformers metadataTransformers = new MetadataTransformers();
  private Map<String, PlayerState> playerStates = new HashMap<>();

  private static long lastMetadataChangePermitted = Long.MAX_VALUE;  //Kai
  public static final long REPROCESS_THRESHOLD = 30 * 1000; //Kai

  public PlaybackTracker(
      ScrobbleNotificationManager scrobbleNotificationManager, Scrobbler scrobbler) {
    this.scrobbleNotificationManager = scrobbleNotificationManager;
    this.scrobbler = scrobbler;
  }

  public void handlePlaybackStateChange(String player, PlaybackState playbackState) {
    if (playbackState == null) {
      return;
    }

    PlayerState playerState = getOrCreatePlayerState(player);
    playerState.setPlaybackState(playbackState);
  }

  public void handleMetadataChange(String player, MediaMetadata metadata) {
    if (metadata == null) {
      return;
    }

    Track track =
        metadataTransformers.transformForPackageName(player, Track.fromMediaMetadata(metadata));

    if (!track.isValid()) {
      Log.v("IMP", "Oh no!"); //Kai
      //LastfmClient.failedToScrobble.add(Long.toString(System.currentTimeMillis() - lastMetadataChangePermitted)); //Kai
      if(Math.abs(System.currentTimeMillis() - lastMetadataChangePermitted) < REPROCESS_THRESHOLD ){  //Kai
        return;
      }else{

      }
           //im Prinzip einzige Änderung: Das return hier entfernen. Wenn Track nicht valid ist, soll er trotzdem übernommen werden, sonst bleibt alter Track bestehen und wird mehrfach gescrobbelt.
    }
    lastMetadataChangePermitted = System.currentTimeMillis(); //Kai

    PlayerState playerState = getOrCreatePlayerState(player);
    playerState.setTrack(track);
  }

  public void handleSessionTermination(String player) {
    PlayerState playerState = getOrCreatePlayerState(player);
    PlaybackState playbackState =
        new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1)
            .build();
    playerState.setPlaybackState(playbackState);

    /*
    Track.Builder builder = Track.builder().track("");  //Kai: wenn man das Radio pausiert, wird der PlaybackState gecleart
    builder.artist("");
    Track track = builder.build();
    playerState.setTrack(track);*/
  }

  private PlayerState getOrCreatePlayerState(String player) {
    PlayerState playerState = playerStates.get(player);

    if (!playerStates.containsKey(player)) {
      playerState = new PlayerState(player, scrobbler, scrobbleNotificationManager);
      playerStates.put(player, playerState);
    }

    return playerState;
  }
}
