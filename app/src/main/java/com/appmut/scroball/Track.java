package com.appmut.scroball;

import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.util.Log;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Toast;

import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.appmut.scroball.transforms.TitleExtractor;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@AutoValue
public abstract class Track implements Serializable {

  public abstract String track();

  public abstract String artist();

  public abstract Optional<String> composer();

  public abstract Optional<String> album();

  public abstract Optional<String> albumArtist();

  public abstract Optional<Long> duration();

  public abstract Optional<Bitmap> art();

  public abstract Builder toBuilder();

  public boolean isValid() {
    return !track().equals("") && !artist().equals("");
  }

  public static Track fromMediaMetadata(MediaMetadata metadata) {
    String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
    String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
    String composer = metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER);
    String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
    String albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
    long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);






    if (title == null) {
      title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
      //LastfmClient.counter++;   //Kai

      if (title == null) {
        title = "";
      }


    }

    if(title.equals("1LIVE")){  //Kai
      Track.Builder builder = Track.builder().track("");
      builder.artist("");
      return builder.build();
    }

    if(albumArtist != null){  //Kai
      if(albumArtist.contains("WDR 2")){  //Kai
        Track.Builder builder = Track.builder().track("");
        builder.artist("");
        return builder.build();
      }
    }

    if(artist.contains("WDR 2")){  //Kai
      Track.Builder builder = Track.builder().track("");
      builder.artist("");
      return builder.build();
    }


    if (art == null) {
      art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
    }

    Track.Builder builder = Track.builder().track(title);

    if (duration < 1000) {
      // Apple Music incorrectly reports durations in seconds instead of ms (when it reports
      // duration at all).
      duration *= 1000;
    }

    if (duration > 0) {
      builder.duration(duration);
    }
    if (album != null && !album.isEmpty()) {
      builder.album(album);
    }
    if (albumArtist != null && !albumArtist.isEmpty()) {
      builder.albumArtist(albumArtist);
    }
    if (art != null) {
      builder.art(art);
    }
    if (artist != null) {
      if(title.equals("Radio Bonn/Rhein-Sieg") ||  title.equals("WDR 3") || title.equals("WDR 4") || title.equals("WDR 5") || title.equals("SWR1 Rheinland-Pfalz") || title.equals("SWR2 Kulturradio") || title.equals("SWR4 Rheinland Pfalz")){   //Kai
        return new TitleExtractor().transformByArtist(builder.track("").artist(artist).build(), false);
      /*}else if(title.equals("1LIVE")){
        return new TitleExtractor().transformByArtist(builder.track("").artist(artist).build(), true);*/
      }
      builder.artist(artist);
    } else if (albumArtist != null) {
      // Some apps (Telegram) set ALBUM_ARTIST but not ARTIST.
      builder.artist(albumArtist);
    } else {
      return new TitleExtractor().transform(builder.artist("").build());
    }
    if (composer != null) {
      builder.composer(composer);
    }
    return builder.build();
  }

  public boolean isSameTrack(Track track) {
    return track != null && track.track().equals(track()) && track.artist().equals(artist());
  }

  public static Track empty() {
    return Track.builder().track("").artist("").build();
  }

  public static Builder builder() {
    return new AutoValue_Track.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder track(String track);

    public abstract Builder artist(String artist);

    public abstract Builder composer(String composer);

    public abstract Builder album(String album);

    public abstract Builder albumArtist(String albumArtist);

    public abstract Builder duration(long duration);

    public abstract Builder art(Bitmap art);

    public abstract Track build();
  }
}
