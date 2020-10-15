package com.appmut.scroball;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import com.google.common.base.Optional;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.appmut.scroball.db.ScroballDB;
import com.softartdev.lastfm.Caller;
import com.softartdev.lastfm.Result;

import java.util.ArrayList;
import java.util.List;

public class Scrobbler {

  private static final String TAG = Scrobbler.class.getName();
  private static final int SCROBBLE_THRESHOLD = 1 * 30 * 1000;    //Kai: initially 4 * 60 * 1000 = 4 minutes
  private static final int MINIMUM_SCROBBLE_TIME = 30 * 1000;     //Kai: initially 30 * 1000 = 30 seconds
  private static final int MAX_SCROBBLES = 50;

  private final LastfmClient client;
  private final ScrobbleNotificationManager notificationManager;
  private final ScroballDB scroballDB;
  private final ConnectivityManager connectivityManager;
  private final TrackLover trackLover;
  private final EventBus eventBus = ScroballApplication.getEventBus();
  private final List<PlaybackItem> pendingPlaybackItems;
  private final List<Scrobble> pending;
  private boolean isScrobbling = false;
  private long lastScrobbleTime = 0;
  private long nextScrobbleDelay = 0;

  //private PlaybackItem lastPlaybackItem = null; //Kai

  public Scrobbler(
      LastfmClient client,
      ScrobbleNotificationManager notificationManager,
      ScroballDB scroballDB,
      ConnectivityManager connectivityManager,
      TrackLover trackLover) {
    this.client = client;
    this.notificationManager = notificationManager;
    this.scroballDB = scroballDB;
    this.connectivityManager = connectivityManager;
    this.trackLover = trackLover;
    // TODO write unit test to ensure non-network plays get scrobbled with duration lookup.
    this.pendingPlaybackItems = new ArrayList<>(scroballDB.readPendingPlaybackItems());
    this.pending = new ArrayList<>(scroballDB.readPendingScrobbles());

    eventBus.register(this);
  }

  public void updateNowPlaying(Track track) {
    if (!client.isAuthenticated()) {
      Log.d(TAG, "Skipping now playing update, not logged in.");
      return;
    }

    NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
    boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

    if (!isConnected) {
      return;
    }
    client.updateNowPlaying(
        track,
        message -> {
          LastfmClient.Result result = (LastfmClient.Result) message.obj;
          int errorCode = result.errorCode();
          if (LastfmClient.isAuthenticationError(errorCode)) {
            notificationManager.notifyAuthError();
            ScroballApplication.getEventBus().post(AuthErrorEvent.create(errorCode));
          }
          return true;
        });
  }

  public void submit(PlaybackItem playbackItem, boolean skipFetchingTrackDuration) {
    //Log.v("WICHTIG", "submit function: " + playbackItem.toString());
    //Log.v("WICHTIG", "lastplaybackitem: " + lastPlaybackItem);
   /* if(lastPlaybackItem != null) {
      Log.v("Wichtig", "Comparing... " + lastPlaybackItem.getTrack().track().equals(playbackItem.getTrack().track()) + lastPlaybackItem.getTrack().artist().equals(playbackItem.getTrack().artist()));
    }

    if(lastPlaybackItem != null) {            //Kai: gegen Dopplungen: Methode kann nur 1x aufgerufen werden
      if(lastPlaybackItem.getTrack().track().equals(playbackItem.getTrack().track()) && lastPlaybackItem.getTrack().artist().equals(playbackItem.getTrack().artist())) {
        return;
      }
    }*/


    // Set final value for amount played, in case it was playing up until now.
    playbackItem.updateAmountPlayed();

    // Generate one scrobble per played period.
    Track track = playbackItem.getTrack();

    if(!skipFetchingTrackDuration){    //Kai
      if (!track.duration().isPresent()) {
        fetchTrackDurationAndSubmit(playbackItem);
        return;
      }
    }


    long duration = 0;  //Kai
    long playTime = playbackItem.getAmountPlayed();
    long timestamp = playbackItem.getTimestamp();

    if(!track.duration().isPresent()){  //Kai
      duration = playTime;
    }else{
      duration = track.duration().get();
    }




    if (playTime < 1) {
      return;
    }

    // Handle cases where player does not report duration *and* Last.fm does not report it either.
    if (duration == 0) {
      duration = playTime;
    }

    int playCount = (int) (playTime / duration);
    long scrobbleThreshold = Math.min(SCROBBLE_THRESHOLD, duration / 2);

    if (duration < MINIMUM_SCROBBLE_TIME) {
      return;
    }

    if (playTime % duration > scrobbleThreshold) {
      playCount++;
    }

    int newScrobbles = playCount - playbackItem.getPlaysScrobbled();

    /*
    if(newScrobbles != 0) { //Kai: Nur 1 neuer Scrobble erlaubt
      newScrobbles = 1;
    }

    if(playCount != 0) { //Kai: Nur 1 neuer Scrobble erlaubt
      playCount = 1;
    }*/



Log.v("Wichtig", "newScrobbles: " + newScrobbles);  //Kai
    Log.v("Wichtig", "playCount: " + playCount);
    Log.v("Wichtig", "playbackItem.getPlaysScrobbled(): " + playbackItem.getPlaysScrobbled());

    for (int i = playbackItem.getPlaysScrobbled(); i < playCount; i++) {
      int itemTimestamp = (int) ((timestamp + i * duration) / 1000);

      Scrobble scrobble = Scrobble.builder().track(track).timestamp(itemTimestamp).build();

      pending.add(scrobble);
      scroballDB.writeScrobble(scrobble);
      playbackItem.addScrobble();
    }

    if (newScrobbles > 0) {
      Log.d(TAG, String.format("Queued %d scrobbles", playCount));
    }

    notificationManager.notifyScrobbled(track, newScrobbles);
    scrobblePending();

    //lastPlaybackItem = playbackItem; //Kai: speichern des letzten Items gegen Dopplungen
  }

  public void fetchTrackDurationAndSubmit(final PlaybackItem playbackItem) {
    Log.v("WICHTIG", "Playback Item: " + playbackItem.toString()); //Kai

    NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
    boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

    if (!isConnected || !client.isAuthenticated()) {
      Log.d(TAG, "Offline or unauthenticated, can't fetch track duration. Saving for later.");
      queuePendingPlaybackItem(playbackItem);
      return;
    }


    Track track = playbackItem.getTrack();
    client.getTrackInfo(
        track,
        message -> {
          if (message.obj == null) {
            Result result = Caller.getInstance().getLastResult();
            int errorCode = 1;

            if (result != null) {
              errorCode = result.getErrorCode();
              //LastfmClient.failedToScrobble.add("Error code: " + errorCode); //Kai
            }else{
              //LastfmClient.failedToScrobble.add("Result null"); //Kai
            }
            if (errorCode == 6) {
              Log.d(TAG, "Track not found, cannot scrobble.");
              // TODO prompt user to scrobble anyway

              //submit(playbackItem, true); //Kai: trotzdem versuchen zu scrobbeln // doch nicht

              /*
              Track.Builder builder = Track.builder().track(playbackItem.getTrack().artist());    //Kai: switcht Artist und Title und versucht es nochmal
              builder.artist(playbackItem.getTrack().track());    //Kai         //UPDATE: nein, lieber doch nicht weil Last.fm hat irgendwie auch viele vertauschte

              Track switchedTrack = builder.build();    //Kai
              playbackItem.updateTrack(switchedTrack);    //Kai

              LastfmClient.failedToScrobble.add("Track: " + playbackItem.getTrack().track());*/
              String msg = track.track() + track.artist() + "// REASON: Track not found, cannot scrobble."; //Kai
              if(!(track.track().equals("") && track.artist().equals("")) && !LastfmClient.failedToScrobble.contains(msg)){  //Kai
                LastfmClient.failedToScrobble.add(msg);  //Kai
              }
            } else {
              if (LastfmClient.isTransientError(errorCode)) {
                Log.d(TAG, "Failed to fetch track duration, saving for later.");
                queuePendingPlaybackItem(playbackItem);
              }
              if (LastfmClient.isAuthenticationError(errorCode)) {
                notificationManager.notifyAuthError();
                ScroballApplication.getEventBus().post(AuthErrorEvent.create(errorCode));
              }else{
                //queuePendingPlaybackItem(playbackItem); //Kai
              }
            }
            return true;
          }

          Track updatedTrack = (Track) message.obj;
          playbackItem.updateTrack(updatedTrack);
          Log.d(TAG, String.format("Track info updated: %s", playbackItem));

          LastfmClient.loglog.add("TRACK INFO UPDATED: " + updatedTrack.track() + " " + updatedTrack.artist()); //Kai

          submit(playbackItem, false);
          return true;
        });
  }

  public void scrobblePending() {

    NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
    boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    boolean tracksPending = !(pending.isEmpty() && pendingPlaybackItems.isEmpty());
    boolean backoff = lastScrobbleTime + nextScrobbleDelay > System.currentTimeMillis();

    if(LastfmClient.isScrobbleTaskBlocked){  //Kai
      return;
    }

    if (!isConnected || !client.isAuthenticated() || backoff) {
      return;
    }

    trackLover.lovePending();

    if (isScrobbling || !tracksPending) {
      return;
    }

    List<PlaybackItem> playbackItems = new ArrayList<>(pendingPlaybackItems);
    pendingPlaybackItems.clear();
    scroballDB.clearPendingPlaybackItems();

    if (!playbackItems.isEmpty()) {
      Log.d(TAG, "Re-processing queued items with missing durations.");
    }

    for (PlaybackItem playbackItem : playbackItems) {

      fetchTrackDurationAndSubmit(playbackItem);
    }

    if (pending.isEmpty()) {
      return;
    }

    isScrobbling = true;
    final List<Scrobble> tracksToScrobble = new ArrayList<>(pending);

    while (tracksToScrobble.size() > MAX_SCROBBLES) {
      tracksToScrobble.remove(tracksToScrobble.size() - 1);
    }

    Log.v("WICHTIG!", "!!!Beginn tracksToScrobble");
    for(Scrobble s : tracksToScrobble){
      Log.v("WICHTIG!", s.track().track() + s.track().artist());
    }
    Log.v("WICHTIG!", "!!!Ende tracksToScrobble");

    /*
    if(lastPlaybackItems != null) {            //Kai: auch gegen Dopplungen: Methode kann nur 1x aufgerufen werden
    for(int i = 0; i < tracksToScrobble.size(); i++){
      if(lastPlaybackItems.get(i).track().track().equals(tracksToScrobble.get(i).track().track()) && lastPlaybackItems.get().track().artist().equals(tracksToScrobble.get(i).track().artist())) {
        return;
      }
    }
    }
*/

   // if(!LastfmClient.isScrobbleTaskBlocked){
      client.scrobbleTracks(
              tracksToScrobble,
              message -> {
                List<LastfmClient.Result> results = (List<LastfmClient.Result>) message.obj;
                boolean shouldBackoff = false;

                for (int i = 0; i < results.size(); i++) {
                  LastfmClient.Result result = results.get(i);
                  Scrobble scrobble = tracksToScrobble.get(i);

                  if (result.getCorrectedTitle() != null || result.getCorrectedArtist() != null) { //Kai
                    pending.remove(scrobble);

                    Track.Builder builder = Track.builder().track(result.getCorrectedTitle());
                    builder.artist(result.getCorrectedArtist());
                    Scrobble correctedScrobble = Scrobble.builder().track(builder.build()).timestamp(scrobble.timestamp()).build();
                    LastfmClient.failedToScrobble.add("Received corrected Scrobble: " + result.getCorrectedArtist() + " " + result.getCorrectedTitle());
                    pending.add(correctedScrobble);

                  }

                  if (result.isSuccessful()) {

                    scrobble.status().setScrobbled(true);
                    scroballDB.writeScrobble(scrobble);
                    pending.remove(scrobble);

                  } else {
                    int errorCode = result.errorCode();
                    if (!LastfmClient.isTransientError(errorCode)) {
                      pending.remove(scrobble);
                      shouldBackoff = true;
                    }
                    if (LastfmClient.isAuthenticationError(errorCode)) {
                      notificationManager.notifyAuthError();
                      ScroballApplication.getEventBus().post(AuthErrorEvent.create(errorCode));
                    }



                    scrobble.status().setErrorCode(errorCode);
                    scroballDB.writeScrobble(scrobble);
                  }
                }

                isScrobbling = false;
                lastScrobbleTime = System.currentTimeMillis();

                //lastPlaybackItem2 = playbackItem; //Kai: speichern des letzten Items gegen Dopplungen

                if (shouldBackoff) {
                  // Back off starting at 1 second, up to an hour max.
                  if (nextScrobbleDelay == 0) {
                    nextScrobbleDelay = 1000;
                  } else if (nextScrobbleDelay < 60 * 60 * 1000) {
                    nextScrobbleDelay *= 4;
                  }
                } else {
                  nextScrobbleDelay = 0;

                  // There may be more tracks waiting to scrobble. Keep going.
                  scrobblePending();
                }
                return false;
              });
      /*}else{
      Thread myThread = new Thread() {
        public void run() {
          Log.v("WICHTIG!", "Thread Start");
          while (true) {
            try { Thread.currentThread().sleep(500); } catch (Exception e) {}
            LastfmClient.myThreadCounter++;
            if (!LastfmClient.isScrobbleTaskBlocked) {
              client.scrobbleTracks(
                      tracksToScrobble,
                      message -> {
                        List<LastfmClient.Result> results = (List<LastfmClient.Result>) message.obj;
                        boolean shouldBackoff = false;

                        for (int i = 0; i < results.size(); i++) {
                          LastfmClient.Result result = results.get(i);
                          Scrobble scrobble = tracksToScrobble.get(i);

                          if (result.isSuccessful()) {


                            scrobble.status().setScrobbled(true);
                            scroballDB.writeScrobble(scrobble);
                            pending.remove(scrobble);

                          } else {
                            int errorCode = result.errorCode();
                            if (!LastfmClient.isTransientError(errorCode)) {
                              pending.remove(scrobble);
                              shouldBackoff = true;
                            }
                            if (LastfmClient.isAuthenticationError(errorCode)) {
                              notificationManager.notifyAuthError();
                              ScroballApplication.getEventBus().post(AuthErrorEvent.create(errorCode));
                            }
                            scrobble.status().setErrorCode(errorCode);
                            scroballDB.writeScrobble(scrobble);
                          }
                        }

                        isScrobbling = false;
                        lastScrobbleTime = System.currentTimeMillis();

                        //lastPlaybackItem2 = playbackItem; //Kai: speichern des letzten Items gegen Dopplungen

                        if (shouldBackoff) {
                          // Back off starting at 1 second, up to an hour max.
                          if (nextScrobbleDelay == 0) {
                            nextScrobbleDelay = 1000;
                          } else if (nextScrobbleDelay < 60 * 60 * 1000) {
                            nextScrobbleDelay *= 4;
                          }
                        } else {
                          nextScrobbleDelay = 0;

                          // There may be more tracks waiting to scrobble. Keep going.
                          scrobblePending();
                        }
                        return false;
                      });
              Log.v("WICHTIG!", "Thread Ende");
              return;
            }
          }
        }
      };
      myThread.start();
    }

*/


  }

  /**
   * Calculates the number of milliseconds of playback time remaining until the specified {@param
   * PlaybackItem} can be scrobbled, i.e. reaches 50% of track duration or SCROBBLE_THRESHOLD.
   *
   * @return The number of milliseconds remaining until the next scrobble for the current playback
   *     item can be submitted, or -1 if the track's duration is below MINIMUM_SCROBBLE_TIME.
   */
  public long getMillisecondsUntilScrobble(PlaybackItem playbackItem) {
    if (playbackItem == null) {
      return -1;
    }

    Optional<Long> optionalDuration = playbackItem.getTrack().duration();
    long duration = optionalDuration.or(0L);

    if (duration < MINIMUM_SCROBBLE_TIME) {
      if (optionalDuration.isPresent()) {
        Log.d(TAG, String.format("Not scheduling scrobble, track is too short (%d)", duration));
      } else {
        Log.d(TAG, "Not scheduling scrobble, track duration not known");
      }
      return -1;
    }

    long scrobbleThreshold = Math.min(duration / 2, SCROBBLE_THRESHOLD);
    long nextScrobbleAt = playbackItem.getPlaysScrobbled() * duration + scrobbleThreshold;

    return Math.max(0, nextScrobbleAt - playbackItem.getAmountPlayed());
  }

  private void queuePendingPlaybackItem(PlaybackItem playbackItem) {
    //if(!pendingPlaybackItems.contains(playbackItem)){ //Kai
      pendingPlaybackItems.add(playbackItem);
      scroballDB.writePendingPlaybackItem(playbackItem);
    //}

  }

  @Subscribe
  public void onTrackLoveEvent(TrackLoveEvent e) {
    trackLover.loveTrack(e.track());
  }
}
