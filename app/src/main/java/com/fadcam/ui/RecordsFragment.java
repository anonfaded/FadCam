package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RecordsFragment extends Fragment {

    private RecyclerView recyclerView;
    private RecordsAdapter adapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_records, container, false);

        recyclerView = view.findViewById(R.id.recycler_view_records);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        List<String> recordsList = getRecordsList();
        adapter = new RecordsAdapter(recordsList);
        recyclerView.setAdapter(adapter);

        return view;
    }

    private List<String> getRecordsList() {
        List<String> recordsList = new ArrayList<>();
        File recordsDir = new File(getContext().getExternalFilesDir(null), "FadCam");
        if (recordsDir.exists()) {
            File[] files = recordsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp4")) {
                        recordsList.add(file.getName());
                    }
                }
            }
        }
        return recordsList;
    }
}