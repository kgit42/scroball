package com.appmut.scroball;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.appmut.scroball.db.ScroballDB;
import com.raizlabs.android.dbflow.config.FlowManager;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import com.softartdev.lastfm.Caller;

public class ScroballApplication extends Application {

  private static EventBus eventBus = new EventBus();
  private static NowPlayingChangeEvent lastEvent =
      NowPlayingChangeEvent.builder().source("").track(Track.empty()).build();

  private LastfmClient lastfmClient;
  private ScroballDB scroballDB;
  private SharedPreferences sharedPreferences;


  /* Checks if external storage is available for read and write */    //Kai
  public boolean isExternalStorageWritable() {
    String state = Environment.getExternalStorageState();
    if ( Environment.MEDIA_MOUNTED.equals( state ) ) {
      return true;
    }
    return false;
  }

  /* Checks if external storage is available to at least read */    //Kai
  public boolean isExternalStorageReadable() {
    String state = Environment.getExternalStorageState();
    if ( Environment.MEDIA_MOUNTED.equals( state ) ||
            Environment.MEDIA_MOUNTED_READ_ONLY.equals( state ) ) {
      return true;
    }
    return false;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    LastfmClient.loglog.add("onCreate");    //Kai

    // ..........

    if ( isExternalStorageWritable() ) {    //Kai von hier
      LastfmClient.loglog.add("ExternalStorage is Writable");  //Kai

      File appDirectory = new File( Environment.getExternalStorageDirectory() + "/MyPersonalAppFolder" );
      File mediaStorageDir = new File(getExternalFilesDir(null) + "/MyAppFolder");  //Kai
      LastfmClient.loglog.add(mediaStorageDir.getPath());
      File logDirectory = new File( mediaStorageDir + "/logs" );
      File logFile = new File( logDirectory, "logcat_" + System.currentTimeMillis() + ".txt" );

      // create app folder
      if ( !mediaStorageDir.exists() ) {
        LastfmClient.loglog.add(Boolean.toString(mediaStorageDir.mkdir()));
      }

      // create log folder
      if ( !logDirectory.exists() ) {
        logDirectory.mkdir();
      }

      // clear the previous logcat and then write the new one to the file
      try {
        Process process = Runtime.getRuntime().exec("logcat -c");
        process = Runtime.getRuntime().exec("logcat -f " + logFile);
      } catch ( IOException e ) {
        e.printStackTrace();
      }

    } else if ( isExternalStorageReadable() ) {
      // only readable
    } else {
      // not accessible
    }                       // Kai bis hier  .........

    //Fabric.with(this, new Crashlytics());
    FlowManager.init(this);
    //MobileAds.initialize(this, "ca-app-pub-9985743520520066~4279780475");

    sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

    String userAgent =
        String.format(Locale.UK, "%s.%d", BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE);
    String sessionKeyKey = getString(R.string.saved_session_key);
    LastfmApi api = new LastfmApi();
    Caller caller = Caller.getInstance();

    if (sharedPreferences.contains(sessionKeyKey)) {
      String sessionKey = sharedPreferences.getString(sessionKeyKey, null);
      lastfmClient = new LastfmClient(api, caller, userAgent, sessionKey);
    } else {
      lastfmClient = new LastfmClient(api, caller, userAgent);
    }


    scroballDB = new ScroballDB();
    eventBus.register(this);
  }

  public void startListenerService() {
    if (ListenerService.isNotificationAccessEnabled(this) && getLastfmClient().isAuthenticated()) {
      startService(new Intent(this, ListenerService.class));
    }
  }


  public void stopListenerService() {
    stopService(new Intent(this, ListenerService.class));
  }

  public void logout() {
    SharedPreferences preferences = getSharedPreferences();
    SharedPreferences.Editor editor = preferences.edit();
    editor.remove(getString(R.string.saved_session_key));
    editor.apply();

    stopListenerService();
    getScroballDB().clear();
    getLastfmClient().clearSession();
  }

  public LastfmClient getLastfmClient() {
    return lastfmClient;
  }

  public ScroballDB getScroballDB() {
    return scroballDB;
  }

  public SharedPreferences getSharedPreferences() {
    return sharedPreferences;
  }

  @Subscribe
  public void onNowPlayingChange(NowPlayingChangeEvent event) {
    lastEvent = event;
  }

  @Subscribe
  public void onAuthError(AuthErrorEvent event) {
    logout();
  }

  public static EventBus getEventBus() {
    return eventBus;
  }

  public static NowPlayingChangeEvent getLastNowPlayingChangeEvent() {
    return lastEvent;
  }
}
