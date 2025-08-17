package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * Placeholder fragment for the new "Faditor Mini" tab.
 * Displays a simple coming-soon UI and will be replaced with the editor later.
 */
public class FaditorMiniFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_faditor_mini, container, false);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Keep same UX as RemoteFragment: show a brief toast
        Toast.makeText(requireContext(), R.string.faditor_mini_toast, Toast.LENGTH_SHORT).show();
    }
}
