package com.appmut.scroball.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.fragment.app.Fragment;

import com.appmut.scroball.R;
import com.appmut.scroball.ScroballApplication;
import com.appmut.scroball.Scrobble;
import com.appmut.scroball.db.ScroballDB;
import com.appmut.scroball.db.ScroballDBUpdateEvent;
import com.google.common.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.List;

public class DebugFragment extends Fragment { //Kai



  @Nullable
  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    View rootView = inflater.inflate(R.layout.fragment_debug, container, false);



    return rootView;
  }

  @Override
  public void onResume() {
    super.onResume();
    ScroballApplication.getEventBus().register(this);

  }

  @Override
  public void onPause() {
    super.onPause();
    ScroballApplication.getEventBus().unregister(this);
  }

}
