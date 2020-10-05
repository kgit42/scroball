package com.appmut.scroball.transforms;

import com.appmut.scroball.LastfmClient;
import com.google.common.base.Joiner;
import com.appmut.scroball.Track;
import com.raizlabs.android.dbflow.StringUtils;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TitleExtractor implements MetadataTransform {

  private static final String[] SEPARATORS =
      //new String[] {" von ", " -- ", "--", " - ", " – ", " — ", "-", "–", "—", ":", "|", "///", };
       //new String[] {" von ", " -- ", "--", " – ", " — ", "—", "–", "///", "|", " - ", "-", ":" };  //Kai
       new String[] {" von "}; //Kai

  @Override
  public Track transform(Track track) {
    String title = null;
    String artist = null;

    for (String separator : SEPARATORS) {
      int count = (track.artist().length() - track.artist().replace(separator,"").length()) / separator.length(); //Kai: computes how many times the seperator occurs
      if(count > 1){  //Kai
        LastfmClient.failedToScrobble.add(track.track() + " // REASON: a seperator occured more than once");
        Track.Builder builder = Track.builder().track("");
        builder.artist("");
        return builder.build();
      }
      String[] components = track.track().split(Pattern.quote(separator));

      if (components.length > 1) {
        String[] titleComponents = Arrays.copyOfRange(components, 1, components.length);

        title = components[0];   //Kai: ursprünglich: artist = ...
        artist = Joiner.on(separator).join(titleComponents);   //Kai: ursprünglich: title = ...
        break;
      }
    }

    if (title == null || artist == null) {
      return track;
    }

    title = title.trim();
    artist = artist.trim();

    return track.toBuilder().artist(artist).track(title).build();
  }




  public Track transformByArtist(Track track, boolean ArtistFirst) {
    String title = null;
    String artist = null;



    for (String separator : SEPARATORS) {
      int count = (track.artist().length() - track.artist().replace(separator,"").length()) / separator.length();//Kai: computes how many times the seperator occurs
      if(count > 1){  //Kai
        LastfmClient.failedToScrobble.add(track.artist() + " // REASON: a seperator occured more than once");
        Track.Builder builder = Track.builder().track("");
        builder.artist("");
        return builder.build();
      }
      String[] components = track.artist().split(Pattern.quote(separator));

      if (components.length > 1) {
        String[] titleComponents = Arrays.copyOfRange(components, 1, components.length);

        if(ArtistFirst){
          artist = components[0];
          title = Joiner.on(separator).join(titleComponents);
        }else{
          title = components[0];   //Kai: ursprünglich: artist = ...
          artist = Joiner.on(separator).join(titleComponents);   //Kai: ursprünglich: title = ...
        }

        break;
      }
    }

    if (title == null || artist == null) {
      return track;
    }

    title = title.trim();
    artist = artist.trim();

    return track.toBuilder().artist(artist).track(title).build();
  }
}
