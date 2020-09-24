package com.appmut.scroball.ui;

import com.appmut.scroball.LastfmClient;

import java.util.Objects;

public class ScrobbleIdentifier {       //Kai
    private String artist;
    private String track;
    private long timestamp;

    public ScrobbleIdentifier(String track, String artist, long timestamp){
        this.artist = artist;
        this.track = track;
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getArtist() {
        return artist;
    }

    public String getTrack() {
        return track;
    }

    @Override
    public String toString() {
        return "ScrobbleIdentifier{" +
                "artist='" + artist + '\'' +
                ", track='" + track + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScrobbleIdentifier that = (ScrobbleIdentifier) o;
        return Math.abs(timestamp - that.timestamp) < LastfmClient.TIMESTAMP_THRESHOLD &&
                Objects.equals(artist, that.artist) &&
                Objects.equals(track, that.track);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artist, track, timestamp);
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
