package com.appmut.scroball.db;

import android.util.Log;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.appmut.scroball.LastfmClient;
import com.appmut.scroball.PlaybackItem;
import com.appmut.scroball.ScroballApplication;
import com.appmut.scroball.Scrobble;
import com.appmut.scroball.ScrobbleStatus;
import com.appmut.scroball.Track;
import com.raizlabs.android.dbflow.annotation.Database;
import com.raizlabs.android.dbflow.sql.language.Delete;
import com.raizlabs.android.dbflow.sql.language.SQLite;

import java.util.List;

/** FlowDB database to store scrobble history and pending scrobbles for the application. */
@Database(name = ScroballDB.NAME, version = ScroballDB.VERSION)
public class ScroballDB {

  static final String NAME = "ScroballDB";
  static final int VERSION = 3;

  private static final int MAX_ROWS = 1000;

  private EventBus eventBus = ScroballApplication.getEventBus();

  //private Scrobble lastScrobble = null; //Kai

  /** Returns a list of all pending and submitted {@link Scrobble}s. */
  public List<Scrobble> readScrobbles() {
    List<ScrobbleLogEntry> entries =
        SQLite.select()
            .from(ScrobbleLogEntry.class)
            .orderBy(ScrobbleLogEntry_Table.timestamp, false)
            .queryList();
    return scrobbleEntriesToScrobbles(entries);
  }

  /**
   * Writes a single scrobble to the database. If the scrobble already has already been written it
   * will be updated.
   */
  public void writeScrobble(Scrobble scrobble) {
    Log.v("WICHTIG", "writeScrobble: " + scrobble.track().track() + " von " + scrobble.track().artist() + " (" + scrobble.timestamp() + ")"); //Kai

    /*Log.v("Wichtig", "Comparing... " + lastScrobble + scrobble);
    if(lastScrobble != null){   //Kai
      if(lastScrobble.equals(scrobble)) {
        Log.v("WICHTIG", "!!!");
        return;
      }
    }*/



    Track track = scrobble.track();
    ScrobbleStatus status = scrobble.status();
    ScrobbleLogEntry logEntry = new ScrobbleLogEntry();
    logEntry.timestamp = scrobble.timestamp();
    logEntry.artist = track.artist();
    logEntry.track = track.track();
    logEntry.status = scrobble.status().getErrorCode();

    if (track.album().isPresent()) {
      logEntry.album = track.album().get();
    }
    if (track.albumArtist().isPresent()) {
      logEntry.albumArtist = track.albumArtist().get();
    }
    if (status.getDbId() > -1) {
      logEntry.id = status.getDbId();
    }

    logEntry.save();
    scrobble.status().setDbId(logEntry.id);

    eventBus.post(ScroballDBUpdateEvent.create(scrobble));

    //lastScrobble = scrobble; //Kai
  }

  /**
   * Writes a list of scrobbles to the database.
   *
   * @see #writeScrobble(Scrobble)
   */
  public void writeScrobbles(List<Scrobble> scrobbles) {
    for (Scrobble scrobble : scrobbles) {
      writeScrobble(scrobble);
    }
  }

  /**
   * Returns a list of all pending {@link PlaybackItem}s which have been written to the database.
   */
  public List<Scrobble> readPendingScrobbles() {
    List<ScrobbleLogEntry> entries =
        SQLite.select()
            .from(ScrobbleLogEntry.class)
            .where(ScrobbleLogEntry_Table.status.in(LastfmClient.TRANSIENT_ERROR_CODES))
            .orderBy(ScrobbleLogEntry_Table.timestamp, false)
            .queryList();
    return scrobbleEntriesToScrobbles(entries);
  }

  /**
   * Writes a single {@link PlaybackItem} to the database. If the item has already been written it
   * will be updated.
   */
  public void writePendingPlaybackItem(PlaybackItem playbackItem) {
    Log.v("WICHTIG", "writePendingPlaybackItem: " + playbackItem.getTrack().track() + " von " + playbackItem.getTrack().artist() + " (" + playbackItem.getTimestamp() + ")");  //Kai
    Track track = playbackItem.getTrack();
    PendingPlaybackItemEntry entry = new PendingPlaybackItemEntry();
    entry.timestamp = playbackItem.getTimestamp();
    entry.artist = track.artist();
    entry.track = track.track();
    entry.amountPlayed = playbackItem.getAmountPlayed();

    if (track.album().isPresent()) {
      entry.album = track.album().get();
    }
    if (track.albumArtist().isPresent()) {
      entry.albumArtist = track.albumArtist().get();
    }
    if (playbackItem.getDbId() > -1) {
      entry.id = playbackItem.getDbId();
    }

    entry.save();
    playbackItem.setDbId(entry.id);
  }

  /** Returns a list of all {@link PlaybackItem}s which have been written to the database. */
  public List<PlaybackItem> readPendingPlaybackItems() {
    List<PendingPlaybackItemEntry> entries =
        SQLite.select()
            .from(PendingPlaybackItemEntry.class)
            .orderBy(PendingPlaybackItemEntry_Table.timestamp, true)
            .queryList();
    return pendingPlaybackEntriesToPlaybackItems(entries);
  }

  /**
   * Clears all {@link PlaybackItem}s from the database. This method should be called when pending
   * items have been queued for re-submission.
   */
  public void clearPendingPlaybackItems() {
    Delete.table(PendingPlaybackItemEntry.class);
  }

  /** Returns a list of all pending track love actions. */
  public List<LovedTracksEntry> readPendingLoves() {
    return SQLite.select()
        .from(LovedTracksEntry.class)
        .where(LovedTracksEntry_Table.status.in(LastfmClient.TRANSIENT_ERROR_CODES))
        .queryList();
  }

  /** Writes a single track love to the database. */
  public LovedTracksEntry writeLove(Track track, int status) {
    LovedTracksEntry entry = new LovedTracksEntry();
    entry.artist = track.artist().toLowerCase();
    entry.track = track.track().toLowerCase();
    entry.status = status;
    entry.save();
    return entry;
  }

  public boolean isLoved(Track track) {
    return SQLite.select()
            .from(LovedTracksEntry.class)
            .where(LovedTracksEntry_Table.artist.eq(track.artist().toLowerCase()))
            .and(LovedTracksEntry_Table.track.eq(track.track().toLowerCase()))
            .querySingle()
        != null;
  }

  /** Clears all {@link Scrobble} and {@link PlaybackItem}s from the database. */
  public void clear() {
    Delete.tables(ScrobbleLogEntry.class, PendingPlaybackItemEntry.class);
  }

  private List<Scrobble> scrobbleEntriesToScrobbles(List<ScrobbleLogEntry> entries) {
    ImmutableList.Builder<Scrobble> builder = ImmutableList.builder();

    for (ScrobbleLogEntry entry : entries) {
      Track.Builder track = Track.builder().track(entry.track).artist(entry.artist);
      if (entry.albumArtist != null) {
        track.albumArtist(entry.albumArtist);
      }
      if (entry.album != null) {
        track.album(entry.album);
      }

      Scrobble scrobble =
          Scrobble.builder()
              .timestamp(entry.timestamp)
              .status(new ScrobbleStatus(entry.status, entry.id))
              .track(track.build())
              .build();

      builder.add(scrobble);
    }
    return builder.build();
  }

  private List<PlaybackItem> pendingPlaybackEntriesToPlaybackItems(
      List<PendingPlaybackItemEntry> entries) {
    ImmutableList.Builder<PlaybackItem> builder = ImmutableList.builder();

    for (PendingPlaybackItemEntry entry : entries) {
      Track.Builder track = Track.builder().track(entry.track).artist(entry.artist);
      if (entry.albumArtist != null) {
        track.albumArtist(entry.albumArtist);
      }
      if (entry.album != null) {
        track.album(entry.album);
      }
      builder.add(new PlaybackItem(track.build(), entry.timestamp, entry.amountPlayed, entry.id));
    }
    return builder.build();
  }
}
