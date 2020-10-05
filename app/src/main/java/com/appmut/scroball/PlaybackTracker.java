package com.appmut.scroball;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.util.Log;

import com.appmut.scroball.transforms.MetadataTransformers;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaybackTracker {

  private final ScrobbleNotificationManager scrobbleNotificationManager;
  private final Scrobbler scrobbler;
  private final MetadataTransformers metadataTransformers = new MetadataTransformers();
  private Map<String, PlayerState> playerStates = new HashMap<>();

  private static long lastMetadataChangePermitted = Long.MAX_VALUE;  //Kai
  public static final long REPROCESS_THRESHOLD = 0 * 1000; //Kai
  //public static boolean stopCondition = false;   //Kai
  public static ScheduledFuture scheduledFuture = null;
  public static boolean pollingTaskRunning = false; //Kai

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

    Log.v("WICHTIG!", "Player: " + player); //Kai
    boolean switchTrackAndArtist = false;
    if(player.equals("com.hv.replaio")){  //Kai
      switchTrackAndArtist = true;

      String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
      //LastfmClient.failedToScrobble.add(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) + " AND " + artist);

      //Kai: WDR2 zeigt manchmal im Wechsel zu den Metadaten des Songs Fußballergebnisse an... Daher hiermit rausfiltern:
      Pattern p = Pattern.compile("(.)* [0-9]:[0-9]");
      Matcher m = p.matcher(title);
      if (m.matches()){
        LastfmClient.failedToScrobble.add("A football score scrobble was avoided: " + title);
        return;
      }
    }





    if (metadata.getString(MediaMetadata.METADATA_KEY_TITLE).equals("1LIVE") && !PlaybackTracker.pollingTaskRunning) {   //Kai: Task um von 1LIVE die Metadaten abzugreifen

      PlaybackTracker.pollingTaskRunning = true;
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
      PlaybackTracker.scheduledFuture = scheduler.scheduleAtFixedRate(new Thread() {
        public void run() {
          try {

            Track track = null;

            URL url = new URL("https://exporte.wdr.de/1liveMobileServer/app/current_broadcast");

            InputStream is = url.openStream();    // Open a stream to read the server's message
            String resultString = "";

            int bytesRead = 0;   // Read the server message in a loop
            do {
              byte[] result = new byte[1024];
              bytesRead = is.read(result);
              resultString += new String(result);
            } while (bytesRead > 0);

            JSONObject jObject = new JSONObject(resultString);
            JSONObject currentTrack = jObject.getJSONObject("currentTrack");

            String title = currentTrack.getString("title");
            String newTitle = "";
            if(title.endsWith("*")){
              newTitle = title.substring(0, title.length() - 1);
            }else{
              newTitle = title;
            }
            String artist = currentTrack.getString("artist");

            String duration = currentTrack.getString("duration");
            String[] units = duration.split(":");
            long minutes = Long.parseLong(units[0]);
            long seconds = Long.parseLong(units[1]);
            long durationSong = seconds + 60 * minutes;

            Long endTime = currentTrack.getLong("timestamp") / 1000 + durationSong - 15;
            Log.v("WICHTIG!", String.valueOf(endTime));
            Log.v("WICHTIG!", String.valueOf(System.currentTimeMillis() / 1000));
            Log.v("WICHTIG!", Boolean.toString((System.currentTimeMillis() / 1000) < endTime));
            if((System.currentTimeMillis() / 1000) < endTime){    //Kai: nur neuen Track setzen, wenn Song nicht schon um ist
              Track.Builder builder = Track.builder().track(newTitle);
              builder.artist(artist);
              track = builder.build();
              Log.v("WICHTIG!", newTitle + artist);
            }else{
              Track.Builder builder = Track.builder().track("");
              builder.artist("");
              track = builder.build();
            }


            if (!track.isValid()) {
              Log.v("IMP", "Oh no!"); //Kai
              //LastfmClient.failedToScrobble.add(Long.toString(System.currentTimeMillis() - lastMetadataChangePermitted)); //Kai
              if(Math.abs(System.currentTimeMillis() - lastMetadataChangePermitted) < REPROCESS_THRESHOLD ){  //Kai

              }else{
                lastMetadataChangePermitted = System.currentTimeMillis(); //Kai
                PlayerState playerState = getOrCreatePlayerState(player);
                playerState.setTrack(track);
              }
            }else{
              lastMetadataChangePermitted = System.currentTimeMillis(); //Kai
              PlayerState playerState = getOrCreatePlayerState(player);
              playerState.setTrack(track);
            }


          } catch (Exception e) {
            Log.v("WICHTIG!", ">>>> Exception beim Abrufen der 1LIVE Playlist: " + e.getMessage());
          }
        }
      }, 0, 20, TimeUnit.SECONDS);

    }else {

      Track track = null;

      if(PlaybackTracker.pollingTaskRunning && scheduledFuture != null) {     //Kai
        PlaybackTracker.scheduledFuture.cancel(false);
        PlaybackTracker.pollingTaskRunning = false;
      }

      track = metadataTransformers.transformForPackageName(player, Track.fromMediaMetadata(metadata));

/*
      if(track.track().equals("553534435")){  //Kai
        return;
      }*/

      if(switchTrackAndArtist){ //Kai
        Track.Builder builder = Track.builder().track(track.artist());
        builder.artist(track.track());
        track = builder.build();
      }



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
