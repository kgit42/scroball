package com.appmut.scroball;

import android.media.session.PlaybackState;
import android.os.PowerManager;
import android.util.Log;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.util.Timer;
import java.util.TimerTask;

public class PlayerState {

  private static final String TAG = PlayerState.class.getName();

  private final String player;
  private final Scrobbler scrobbler;
  private final ScrobbleNotificationManager notificationManager;
  private final EventBus eventBus = ScroballApplication.getEventBus();
  private PlaybackItem playbackItem;
  private Timer submissionTimer;

  public boolean isPaused; //Kai
  public PlaybackTracker playbackTracker; //Kai

  public PowerManager.WakeLock wakeLock;  //Kai

  private String lastMetadataTitle;  //Kai

  public PlayerState(
          String player, Scrobbler scrobbler, ScrobbleNotificationManager notificationManager, PowerManager.WakeLock wakeLock, PlaybackTracker playbackTracker) {
    this.player = player;
    this.scrobbler = scrobbler;
    this.notificationManager = notificationManager;
    this.wakeLock = wakeLock; //Kai
    this.isPaused = false;  //Kai
    this.playbackTracker = playbackTracker; //Kai
    this.lastMetadataTitle = "";  //Kai
    eventBus.register(this);
  }

  public void setPlaybackState(PlaybackState playbackState) {
    int state = playbackState.getState();
    boolean isPlaying = state == PlaybackState.STATE_PLAYING;

    if(isPlaying){
      isPaused = false; //Kai
      LastfmClient.loglog.add("isPaused = false: " + player);  //Kai
    }else{
      isPaused = true;  //Kai
      LastfmClient.loglog.add("isPaused = true: " + player);  //Kai

    }



    if (playbackItem == null) {
      return;

    }

    playbackItem.updateAmountPlayed();


    if (isPlaying) {
      Log.d(TAG, "Track playing");
      PlaybackTracker.activePlayer = player;  //Kai
      postEvent(playbackItem.getTrack());
      playbackItem.startPlaying();
      notificationManager.updateNowPlaying(playbackItem.getTrack());
      scheduleSubmission();

      playbackTracker.pollingTask(player);   //Kai: manchmal ist 1LIVE Polling Task beendet worden durch angebliche Pause. Wenn wieder Play soll er wieder starten.
    } else {
      /*if(PlaybackTracker.scheduledFuture != null) {
        PlaybackTracker.scheduledFuture.cancel(false);  //Kai
        LastfmClient.loglog.add("polling Task stopped: isPaused");
        PlaybackTracker.pollingTaskRunning = false; //Kai
        if (wakeLock.isHeld()){
          wakeLock.release();
        }
*/
      Log.d(TAG, String.format("Track paused (state %d)", state));
      postEvent(Track.empty());
      playbackItem.stopPlaying();
      if(PlaybackTracker.activePlayer.equals(player)){ //Kai: Benachrichtigung soll nur bei "echter" Pause erfolgen, nicht wenn z. B. das System erst Play bei Player 1 und DANACH Pause bei Player 2 sendet.
        notificationManager.removeNowPlaying();
      }

      scrobbler.submit(playbackItem, false);
    }
  }

  public void setTrack(Track track) {
    Track currentTrack = null;
    boolean isPlaying = false;
    long now = System.currentTimeMillis();

    if (playbackItem != null) {
      currentTrack = playbackItem.getTrack();
      isPlaying = playbackItem.isPlaying();
    }

    if (track.isSameTrack(currentTrack)) {
      Log.d(TAG, String.format("Track metadata updated: %s", track));

      // Update track in PlaybackItem, as this new one probably has updated details/more keys.
      playbackItem.setTrack(track);
    } else {
      Log.d(TAG, String.format("Changed track: %s", track));

      if (playbackItem != null) {
        playbackItem.stopPlaying();
        scrobbler.submit(playbackItem, false);
      }

      playbackItem = new PlaybackItem(track, now);
    }

    if (isPlaying) {
      postEvent(track);
      scrobbler.updateNowPlaying(track);
      notificationManager.updateNowPlaying(track);
      playbackItem.startPlaying();
      scheduleSubmission();
    }
  }

  private void scheduleSubmission() {
    Log.d(TAG, "Scheduling scrobble submission");

    if (submissionTimer != null) {
      submissionTimer.cancel();
    }

    long delay = scrobbler.getMillisecondsUntilScrobble(playbackItem);

    if (delay > -1) {
      Log.d(TAG, "Scrobble scheduled");
      submissionTimer = new Timer();
      submissionTimer.schedule(
          new TimerTask() {
            @Override
            public void run() {
              scrobbler.submit(playbackItem, false);
              scheduleSubmission();
            }
          },
          delay);
    }
  }

  private void postEvent(Track track) {
    eventBus.post(NowPlayingChangeEvent.builder().track(track).source(player).build());
  }

  public void setLastMetadataTitle(String s){ //Kai
    lastMetadataTitle = s;
  }

  public String getLastMetadataTitle(){ //Kai
    return lastMetadataTitle;
  }

  @Subscribe
  private void onTrackLoved(TrackLovedEvent e) {
    if (playbackItem.isPlaying()) {
      // Track love state has changed - refresh the notification.
      notificationManager.updateNowPlaying(playbackItem.getTrack());
    }
  }
}
