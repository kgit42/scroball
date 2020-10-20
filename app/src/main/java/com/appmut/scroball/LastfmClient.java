package com.appmut.scroball;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import androidx.annotation.NonNull;

import android.os.PowerManager;
import android.util.Log;

import com.appmut.scroball.ui.ScrobbleIdentifier;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.softartdev.lastfm.CallException;
import com.softartdev.lastfm.Caller;
import com.softartdev.lastfm.Session;
import com.softartdev.lastfm.Track;
import com.softartdev.lastfm.scrobble.ScrobbleData;
import com.softartdev.lastfm.scrobble.ScrobbleResult;

/**
 * Client for accessing the Last.fm API.
 *
 * <p>Acts as a wrapper around the client in {@link com.softartdev.lastfm}, providing asynchronous methods
 * and transforming between the Scroball and internal API data types.
 */
public class LastfmClient {

  public static final int ERROR_NO_ERROR = 0;
  public static final int ERROR_UNKNOWN = 1;
  public static final int ERROR_OPERATION_FAILED = 8;
  public static final int ERROR_INVALID_SESSION = 9;
  public static final int ERROR_SERVICE_OFFLINE = 11;
  public static final int ERROR_UNAUTHORIZED_TOKEN = 14;
  public static final int ERROR_SERVICE_TEMPORARILY_UNAVAILABLE = 16;
  public static final int ERROR_RATE_LIMIT_EXCEEDED = 29;   //Kai

  public static final long cooldown = 5 * 1000; //Kai: Die App stellt zu viele getInfo Anfragen, die irgendwann dazu führen, dass man bei LastFM gesperrt wird temporär.
  public static long cooldownStart = 0; //Kai



  /**
   * The set of error codes which indicate transient errors, for which requests should be retried.
   */
  public static final ImmutableSet<Integer> TRANSIENT_ERROR_CODES =
          ImmutableSet.of(
                  ERROR_NO_ERROR,
                  ERROR_UNKNOWN,
                  ERROR_OPERATION_FAILED,
                  ERROR_SERVICE_OFFLINE,
                  ERROR_SERVICE_TEMPORARILY_UNAVAILABLE,
                  ERROR_RATE_LIMIT_EXCEEDED);   //Kai

  private static final String TAG = LastfmClient.class.getName();
  private static final String API_KEY = "91825f9f2cbf27d264d46c6cf3564ed9";  //"17f6f4f55152871370780cd9c0761509"; alter API Key
  private static final String API_SECRET = "c8a68bb8277f7cac9d1bc387ab19269b"; //"99eafa4c2412543f3141505121184b8a";


  private final LastfmApi api;
  private final Caller caller;
  private Session session;

  public static ArrayList<ScrobbleIdentifier> lastScrobbledTracks = new ArrayList<ScrobbleIdentifier>(); //Kai: with timestamps: um zu verhindern, dass, wenn man nach vielen Songs den Sender ausschaltet, doppelte nachgereicht werden
  public static ArrayList<String> lastXScrobbledTracks = new ArrayList<String>(); //Kai: without timestamp: um zu verhindern, dass bei Pause oder anderer Unterbrechung eines Songs dieser nochmal gescrobbelt wird
  private static final int MAX_LAST_SCROBBLED_SIZE = 2; //Kai
  //public static int numberOfFakeSuccess = 0; //Kai
  public static boolean isScrobbleTaskBlocked = false; //Kai
  public static final int TIMESTAMP_THRESHOLD = 300;  //Kai
  public static ArrayList<String> failedToScrobble = new ArrayList<String>(); //Kai
  public static ArrayList<String> loglog = new ArrayList<String>(); //Kai
  //public static int myThreadCounter = 0;  //Kai
  public static int counter = 0;  //Kai

  /**
   * Creates a new authenticated client.
   */
  public LastfmClient(LastfmApi api, Caller caller, String userAgent, String sessionKey) {
    this(api, caller, userAgent);
    setSession(sessionKey);
  }

  /**
   * Creates a new unauthenticated client. The only allowed client method on this instance will be
   * {@link #getSession(String, Handler.Callback)}
   */
  public LastfmClient(LastfmApi api, Caller caller, String userAgent) {
    this.api = api;
    this.caller = caller;
    caller.setUserAgent(userAgent);
    caller.setCache(null);
  }

  /**
   * Returns {@code true} if this client is authenticated and can be used to make calls to the
   * Last.fm API. A client will be authenticated if it was created with {@link
   * LastfmClient(LastfmApi, Caller, String, String)}, or was authenticated after creation with
   * {@link #getSession(String, Handler.Callback)}.
   */
  public boolean isAuthenticated() {
    return session != null;
  }

  /**
   * Returns the URL to redirect users to for browser-based authentication.
   */
  public Uri getAuthUrl() {
    return Uri.parse(
            "http://www.last.fm/api/auth/?api_key=" + API_KEY + "&cb=scroball://authenticate");
  }

  /**
   * Authenticates with the Last.fm API using the browser-based token authentication, setting up an
   * active session on this client.
   *
   * @param token    token received from the Last.fm API through a redirect.
   * @param callback callback which will be called with an {@link AuthResult} as the message
   *                 payload.
   */
  public void getSession(String token, Handler.Callback callback) {
    new GetSessionTask(
            api,
            caller,
            message -> {
              AuthResult result = (AuthResult) message.obj;
              if (result.sessionKey().isPresent()) {
                setSession(result.sessionKey().get());
              }
              callback.handleMessage(message);
              return true;
            })
            .execute(token);
  }

  /**
   * Updates the user's Now Playing status on the Last.fm API.
   *
   * @param track    the track to take metadata from. Only track and artist will be used.
   * @param callback the callback which will be invoked with the request result, with a {@link
   *                 Result} as the message payload.
   */
  public void updateNowPlaying(com.appmut.scroball.Track track, Handler.Callback callback) {
    int now = (int) System.currentTimeMillis() / 1000;
    new UpdateNowPlayingTask(api, session, callback).execute(getScrobbleData(track, now));
  }

  /**
   * Submits the specified scrobbles to the Last.fm API for the current user.
   *
   * @param scrobbles the list of scrobbles to submit. Must be 50 or fewer items.
   * @param callback  the callback which will be invoked with the results of the submissions, with a
   *                  list of {@link Result} as the message payload.
   */
  public void scrobbleTracks(List<Scrobble> scrobbles, Handler.Callback callback) {
    isScrobbleTaskBlocked = true; //Kai

    /*
    for (Scrobble s:scrobbles) {  //Kai
      failedToScrobble.add("Scrobble try 0: " + s.track().track() + " " + s.track().artist());
    }
*/

    //int numberOfFakeSuccess = 0;
    ArrayList<String> doubleScrobblesChecker = new ArrayList<String>(); //Kai: diese Liste prüft, ob die Scrobbles, die der Methode hier als param übergeben werden, gleiche enthalten: verhindern, dass, wenn ein Lied fälschlicherweise länger da steht als es lang ist, es 2x gescrobbelt wird



    Preconditions.checkArgument(
            scrobbles.size() <= 50, "Cannot submit more than 50 scrobbles at once");
    final ScrobbleData[] scrobbleData = new ScrobbleData[scrobbles.size()];

    for (int i = 0; i < scrobbles.size(); i++) {
      Scrobble scrobble = scrobbles.get(i);
      ScrobbleIdentifier scrobbleIdentifier = new ScrobbleIdentifier(scrobble.track().track(), scrobble.track().artist(), scrobble.timestamp());  //Kai
      //String scrobbleString = {scrobble.track().track() + scrobble.track().artist(), scrobble.timestamp()};
      String trackAndArtist = scrobble.track().track() + scrobble.track().artist();

      Log.v("WICHTIG!", scrobbleIdentifier.toString());  //Kai
      Log.v("WICHTIG!", "---Vergleich Start: (" + lastScrobbledTracks.size() + ")");
      for (int j = 0; j < lastScrobbledTracks.size(); j++) {
        Log.v("WICHTIG!", "aktuelles j: " + j + " von: " + lastScrobbledTracks.size());
        Log.v("WICHTIG!", lastScrobbledTracks.get(j) + " AND " + scrobbleIdentifier.toString() + " are the same?" + lastScrobbledTracks.get(j).equals(scrobbleIdentifier));
      }
      Log.v("WICHTIG!", "---Vergleich Ende: ");

      if (lastScrobbledTracks.contains(scrobbleIdentifier) || doubleScrobblesChecker.contains(trackAndArtist) || lastXScrobbledTracks.contains(trackAndArtist)) {   //Kai: Wenn das Element schon mal in den letzten 4 Scrobbles vorkam, wird ein "Fake Success Code" im AsyncTask (s. u.) erzeugt
        //numberOfFakeSuccess++;
        if(lastScrobbledTracks.contains(scrobbleIdentifier)){ //wenn ein gleicher Track gefunden wurde, der zu ähnlicher Zeit (siehe TIMESTAMP_THRESHOLD) gescrobbelt wurde, bekommt der Track in der Liste diesen neuen Timestamp (damit, falls noch weitere doppelte Scrobbles folgen, diese auch abgefangen werden...)
          for(int k = 0; k < lastScrobbledTracks.size(); k++){
            if (lastScrobbledTracks.get(k).equals(scrobbleIdentifier)) {
              lastScrobbledTracks.get(k).setTimestamp(scrobbleIdentifier.getTimestamp());
            }
          }
        }
        scrobbleData[i] = new ScrobbleData();    //nein, stattdessen einfach ein leeres ScrobbleData, sonst NullPointerE
      } else {
        doubleScrobblesChecker.add(trackAndArtist); //Kai
        scrobbleData[i] = getScrobbleData(scrobble.track(), scrobble.timestamp());
      }

    }

    /*
    List<Result> results = scrobbleTracksTask(api, session, callback, 0, scrobbleData); //Kai: statt AsyncTask normale Methode

    Message message = Message.obtain();
    message.obj = results;
    callback.handleMessage(message);
    Log.d(TAG, String.format("Scrobbles submitted: %s", Arrays.toString(results.toArray())));
    //isScrobbleTaskBlocked = false;  //Kai
    */



   /* if (!isScrobbleTaskBlocked) { *///Kai  //bringt an dieser Stelle absolut nichts: denn nach sleep(500) wird doch trotzdem ohne zu Prüfen ob doppelt gescrobbelt?!
      new ScrobbleTracksTask(api, session, callback, 0).execute(scrobbleData);    //das war als einziges von diesem Absatz schon bei initialem Scroball da
  /* } else {  //Kai: alles hier drunter. Ganz selten gab es vermutlich ein Double Scrobble dadurch, dass der Scrobble Async Task so schnell hintereinander 2x parallel ausgeführt wurde, dass ArrayList noch nicht aktuell war. Hiermit wird das vermieden
      Thread myThread = new Thread() {
        public void run() {
          Log.v("WICHTIG!", "Thread Start");
          while (isScrobbleTaskBlocked) {   //Achtung: hier müsste true hin (siehe unten)
            try { Thread.currentThread().sleep(500); } catch (Exception e) {}
            myThreadCounter++;
            if (!isScrobbleTaskBlocked) {     //Achtung: kann passieren, dass in einem Moment isScrobbleTask Blocked noch true ist, dann aber nicht mehr, wenn while condition gecheckt wird.
              new ScrobbleTracksTask(api, session, callback, 0).execute(scrobbleData);
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


  public static String getLastScrobbledTracks(){ //Kai
    String s = new String();
    try {
      for (int j = 0; j < lastScrobbledTracks.size(); j++) {
        s = lastScrobbledTracks.get(j).toString() + "\n" + "\n" + s;
      }
    }catch(Exception e){
      s = (e.getMessage()) + s;
    }
    return s;
  }

  public static String getLastXScrobbledTracks(){ //Kai
    String s = new String();
    try {
      for (int j = 0; j < lastXScrobbledTracks.size(); j++) {
        s = (lastXScrobbledTracks.get(j) + "\n") + s;
      }
    }catch(Exception e){
      s = (e.getMessage() + s);
    }
    return s;
  }

  public static String getFailedToScrobble(){ //Kai
    String s = new String();
    try {
      for (int j = 0; j < failedToScrobble.size(); j++) {
        s = (failedToScrobble.get(j) + "\n" + "\n") + s;
      }
    }catch(Exception e){
      s = (e.getMessage()) + s;
    }
    return s;
  }

  public static String getLoglog(){ //Kai
    String s = new String();
    try {
      for (int j = 0; j < loglog.size(); j++) {
        s = (loglog.get(j) + "\n" + "\n") + s;
      }
    }catch(Exception e){
      s = (e.getMessage()) + s;
    }
    return s;
  }

  public static void clearAllLists(){ //Kai
    lastScrobbledTracks.clear();
    lastXScrobbledTracks.clear();
    loglog.clear();
    failedToScrobble.clear();
  }

  public void getTrackInfo(com.appmut.scroball.Track track, Handler.Callback callback) {
    if(Math.abs(System.currentTimeMillis() - cooldownStart) < cooldown){    //Kai
      return;
    }

    cooldownStart = System.currentTimeMillis();


    new GetTrackInfoTask(session, callback).execute(track);
  }

  /**
   * Loves the specified track on the Last.fm API.
   *
   * @param track the track to take metadata from. Only track and artist will be used.
   * @param callback the callback which will be invoked with the result of the request, with a
   *     {@link Result} as the message payload.
   */
  public void loveTrack(com.appmut.scroball.Track track, Handler.Callback callback) {
    new LoveTrackTask(api, session, callback).execute(track);
  }

  public void clearSession() {
    session = null;
  }

  private void setSession(String sessionKey) {
    session = Session.createSession(API_KEY, API_SECRET, sessionKey);
  }

  /**
   * Returns {@code true} if the specified {@code errorCode} is transient, and the request should be
   * retried.
   */
  public static boolean isTransientError(int errorCode) {
    return TRANSIENT_ERROR_CODES.contains(errorCode);
  }

  /**
   * Returns {@code true} if the specified {@code errorCode} indicates an authentication error, and
   * the user must reauthenticate.
   */
  public static boolean isAuthenticationError(int errorCode) {
    return errorCode == ERROR_INVALID_SESSION || errorCode == ERROR_UNAUTHORIZED_TOKEN;
  }

  @NonNull
  private ScrobbleData getScrobbleData(com.appmut.scroball.Track track, int timestamp) {
    ScrobbleData data = new ScrobbleData(track.artist(), track.track(), timestamp);
    if (track.album().isPresent()) {
      data.setAlbum(track.album().get());
    }
    if (track.albumArtist().isPresent()) {
      data.setAlbumArtist(track.albumArtist().get());
    }
    if (track.duration().isPresent() && track.duration().get() > 0) {
      data.setDuration((int) (track.duration().get() / 1000));
    }
    return data;
  }

  private static class GetSessionTask extends AsyncTask<String, Void, AuthResult> {
    private final LastfmApi api;
    private final Caller caller;
    private final Handler.Callback callback;

    public GetSessionTask(LastfmApi api, Caller caller, Handler.Callback callback) {
      this.api = api;
      this.caller = caller;
      this.callback = callback;
    }

    @Override
    protected AuthResult doInBackground(String... params) {
      String token = params[0];

      Session session = api.getSession(token, API_KEY, API_SECRET);
      if (session != null) {
        return AuthResult.builder().sessionKey(session.getKey()).build();
      }

      com.softartdev.lastfm.Result result = caller.getLastResult();
      AuthResult.Builder authResultBuilder = AuthResult.builder();
      int httpErrorCode = result.getHttpErrorCode();
      int errorCode = result.getErrorCode();
      String errorMessage = result.getErrorMessage();

      if (httpErrorCode > -1) {
        authResultBuilder.httpErrorCode(httpErrorCode);
      }

      if (errorCode > -1) {
        authResultBuilder.errorCode(errorCode);
      }

      if (errorMessage != null) {
        authResultBuilder.error(errorMessage);
      }

      return authResultBuilder.build();
    }

    @Override
    protected void onPostExecute(AuthResult authResult) {
      Message message = Message.obtain();
      message.obj = authResult;
      callback.handleMessage(message);
    }
  }

  private static class UpdateNowPlayingTask
      extends AsyncTask<ScrobbleData, Object, ScrobbleResult> {
    private final LastfmApi api;
    private final Session session;
    private final Handler.Callback callback;

    public UpdateNowPlayingTask(LastfmApi api, Session session, Handler.Callback callback) {
      this.api = api;
      this.session = session;
      this.callback = callback;
    }

    @Override
    protected ScrobbleResult doInBackground(ScrobbleData... params) {
      ScrobbleData scrobbleData = params[0];
      try {
        return api.updateNowPlaying(scrobbleData, session);
      } catch (CallException e) {
        Log.d(TAG, "Failed to update now playing status", e);
      }
      return null;
    }

    @Override
    protected void onPostExecute(ScrobbleResult scrobbleResult) {
      if (scrobbleResult != null && scrobbleResult.isSuccessful()) {
        Log.d(TAG, "Now playing status updated");
      } else {
        Log.d(TAG, String.format("Failed to update now playing status: %s", scrobbleResult));
      }

      Result result = Result.error(ERROR_UNKNOWN);
      if (scrobbleResult != null) {
        if (scrobbleResult.isSuccessful()) {
          result = Result.success();
        } else {
          int errorCode = scrobbleResult.getErrorCode();
          result = Result.error(errorCode >= 0 ? errorCode : ERROR_UNKNOWN);
        }
      }

      Message message = Message.obtain();
      message.obj = result;
      callback.handleMessage(message);
    }
  }
/*
  //Kai: geht leider nicht so, da api.scrobble eine Netzwerk-Aktivität ist und diese nicht im UI Thread laufen sollten, da App sonst einfriert. --> Async Task doch nötig
  private static List<Result> scrobbleTracksTask(LastfmApi api, Session session, Handler.Callback callback, int numberOfFakeSuccess, ScrobbleData... params){  //Kai: AsyncTask zu synchroner normaler Methode gemacht.
    if(params != null){
      try {

        List<ScrobbleResult> results = api.scrobble(ImmutableList.copyOf(params), session);
        ImmutableList.Builder<Result> builder = ImmutableList.builder();

        for (ScrobbleResult result : results) {
          if (result.isSuccessful()) {
            String trackAndArtist = result.getTrack() + result.getArtist(); //Kai
            if(!trackAndArtist.equals("nullnull") && !lastScrobbledTracks.contains(trackAndArtist)){  //Kai
              lastScrobbledTracks.add(result.getTrack() + result.getArtist());  //Kai
            }
            if(lastScrobbledTracks.size() > MAX_LAST_SCROBBLED_SIZE){ //Kai
              lastScrobbledTracks.remove(0);
            }
            Log.v("WICHTIG!", result.getTrack() + result.getArtist());

            builder.add(Result.success());

          } else {
            int errorCode = result.getErrorCode();
            builder.add(Result.error(errorCode >= 0 ? errorCode : ERROR_UNKNOWN));
          }
        }

          for(int i = 0; i < numberOfFakeSuccess; i++){   //Kai
            builder.add(Result.success());
          }

        return builder.build();
      } catch (CallException e) {
        Log.d(TAG, "Failed to submit scrobbles", e);
      }

      ImmutableList.Builder<Result> results = ImmutableList.builder();
      for (ScrobbleData p : params) {
        results.add(Result.error(ERROR_UNKNOWN));
      }
      return results.build();
    }else{
      ImmutableList.Builder<Result> builder = ImmutableList.builder();

        /*
        for(int i = 0; i < numberOfFakeSuccess; i++){   //Kai
          builder.add(Result.success());
        }



      return builder.build();
    }

  }
*/

  private static class ScrobbleTracksTask extends AsyncTask<ScrobbleData, Object, List<Result>> {
    private final LastfmApi api;
    private final Session session;
    private final Handler.Callback callback;
    private int numberOfFakeSuccess;  //Kai

    ScrobbleTracksTask(LastfmApi api, Session session, Handler.Callback callback, int numberOfFakeSuccess) {    //Kai: added numberOfFakeSuccess
      this.api = api;
      this.callback = callback;
      this.session = session;
      this.numberOfFakeSuccess = numberOfFakeSuccess; //Kai
    }

    /*
    @Override
    protected void onPreExecute(){  //Kai
      isScrobbleTaskBlocked = true;
      //Log.v("WICHTIG!", "onPreExecute");
    }
*/

    @Override
    protected List<Result> doInBackground(ScrobbleData... params) {
      if(params != null){
        try {


          for (ScrobbleData s:params) {   //Kai
            loglog.add("Scrobble try: " + s.getTrack() + " " + s.getArtist());
          }


          List<ScrobbleResult> results = api.scrobble(ImmutableList.copyOf(params), session);
          //List<ScrobbleResult> results = new ArrayList<ScrobbleResult>();    //Kai

          ImmutableList.Builder<Result> builder = ImmutableList.builder();

          for (ScrobbleResult result : results) {
            if (result.isSuccessful()) {
              if(result.isArtistCorrected() || result.isTrackCorrected()) { //Kai
                Result resultToAdd = Result.error(ERROR_UNKNOWN); //Kai
                resultToAdd.setCorrected(result.getTrack(), result.getArtist());  //Kai
                builder.add(resultToAdd); //Kai
                failedToScrobble.add("Corrected Item received and try to scrobble... " + result.getTrack() + result.getArtist());
              }else{
                ScrobbleIdentifier scrobbleIdentifier = new ScrobbleIdentifier(result.getTrack(), result.getArtist(), result.getTimestamp());
                //String scrobbleString = result.getTrack() + result.getArtist() + result.getTimestamp(); //Kai
                if((!scrobbleIdentifier.getTrack().equals("null") && !scrobbleIdentifier.getArtist().equals("null")) && (!scrobbleIdentifier.getTrack().equals("\"") && !scrobbleIdentifier.getArtist().equals("\"")) && !(scrobbleIdentifier.getTrack().equals("") || scrobbleIdentifier.getTrack().equals(""))){ //&& !lastScrobbledTracks.contains(trackAndArtist)){  //Kai
                  lastScrobbledTracks.add(new ScrobbleIdentifier(result.getTrack(), result.getArtist(), result.getTimestamp()));  //Kai
                  lastXScrobbledTracks.add(result.getTrack() + result.getArtist()); //KAI
                }

                if(lastXScrobbledTracks.size() > MAX_LAST_SCROBBLED_SIZE){ //Kai
                  lastXScrobbledTracks.remove(0);
                }
                Log.v("WICHTIG!", result.getTrack() + result.getArtist());

                builder.add(Result.success());
              }



            } else {
              int errorCode = result.getErrorCode();
              builder.add(Result.error(errorCode >= 0 ? errorCode : ERROR_UNKNOWN));
            }
          }
          /*
          for(int i = 0; i < numberOfFakeSuccess; i++){   //Kai
            builder.add(Result.success());
          }
*/
          return builder.build();
        } catch (CallException e) {
          Log.d(TAG, "Failed to submit scrobbles", e);
        }

        ImmutableList.Builder<Result> results = ImmutableList.builder();
        for (ScrobbleData p : params) {
          results.add(Result.error(ERROR_UNKNOWN));
        }
        return results.build();
      }else{
        ImmutableList.Builder<Result> builder = ImmutableList.builder();

        /*
        for(int i = 0; i < numberOfFakeSuccess; i++){   //Kai
          builder.add(Result.success());
        }
        */


        return builder.build();
      }

    }

    @Override
    protected void onPostExecute(List<Result> results) {
      Message message = Message.obtain();
      message.obj = results;
      callback.handleMessage(message);
      Log.d(TAG, String.format("Scrobbles submitted: %s", Arrays.toString(results.toArray())));
      isScrobbleTaskBlocked = false;  //Kai
      Log.v("WICHTIG!", "onPostExecute");
    }
  }

  private static class GetTrackInfoTask
      extends AsyncTask<com.appmut.scroball.Track, Object, Track> {
    private final Session session;
    private final Handler.Callback callback;
    private com.appmut.scroball.Track track;

    public GetTrackInfoTask(Session session, Handler.Callback callback) {
      this.session = session;
      this.callback = callback;
    }

    @Override
    protected Track doInBackground(com.appmut.scroball.Track... params) {
      track = params[0];

        //Kai
        loglog.add("Track info try: " + track.track() + " " + track.artist());


      try {
        return Track.getInfo(track.artist(), track.track(), session.getApiKey());
      } catch (CallException e) {
        Log.d(TAG, "Failed to fetch track info", e);
      }
      return null;
    }

    @Override
    protected void onPostExecute(Track updatedTrack) {
      Message message = Message.obtain();
      loglog.add("UPDATED TRACK: " + updatedTrack); //Kai
      //loglog.add("Failedtoscrobble has " + LastfmClient.failedToScrobble.size() + " entries.");  //Kai

      if (updatedTrack != null) {
        com.appmut.scroball.Track.Builder builder =
            com.appmut.scroball.Track.builder()
                .artist(updatedTrack.getArtist())   //Kai: hier und bei track stand vorher 'track' statt 'updatedTrack'. Dadurch wurde korrigierte Version nicht übernommen
                .track(updatedTrack.getName())
                .duration(updatedTrack.getDuration() * 1000);

        /*if (track.album().isPresent()) {
          builder.album(track.album().get());
        } else */if (updatedTrack.getAlbum() != null) {
          builder.album(updatedTrack.getAlbum());
        }
        message.obj = builder.build();
      }

      callback.handleMessage(message);
    }
  }

  private static class LoveTrackTask
      extends AsyncTask<com.appmut.scroball.Track, Object, Result> {
    private final LastfmApi api;
    private final Session session;
    private final Handler.Callback callback;

    public LoveTrackTask(LastfmApi api, Session session, Handler.Callback callback) {
      this.api = api;
      this.session = session;
      this.callback = callback;
    }

    @Override
    protected Result doInBackground(com.appmut.scroball.Track... params) {
      com.appmut.scroball.Track track = params[0];
      try {
        com.softartdev.lastfm.Result result = api.love(track.artist(), track.track(), session);
        if (result.isSuccessful()) {
          return Result.success();
        }
        int errorCode = result.getErrorCode();
        return Result.error(errorCode >= 0 ? errorCode : ERROR_UNKNOWN);
      } catch (CallException e) {
        Log.d(TAG, "Failed to fetch track info", e);
      }
      return null;
    }

    @Override
    protected void onPostExecute(Result result) {
      Message message = Message.obtain();
      message.obj = result;
      callback.handleMessage(message);
      Log.d(TAG, String.format("Track loved: %s", result));
    }
  }

  /** Represents the result of an API call. */
  @AutoValue
  public abstract static class Result {

    public String correctedTitle; //Kai
    public String correctedArtist; //Kai

    public void setCorrected(String title, String artist){ //Kai
      this.correctedTitle = title;
      this.correctedArtist = artist;
    }

    public String getCorrectedTitle(){ //Kai
      return correctedTitle;
    }

    public String getCorrectedArtist(){ //Kai
      return correctedArtist;
    }


    public abstract int errorCode();


    public boolean isSuccessful() {
      return errorCode() < 0;
    }

    public static Result error(int errorCode) {
      Preconditions.checkArgument(errorCode >= 0, "Negative error codes are not possible");
      return new AutoValue_LastfmClient_Result(errorCode);
    }

    public static Result success() {
      return new AutoValue_LastfmClient_Result(-1);
    }
  }
}
