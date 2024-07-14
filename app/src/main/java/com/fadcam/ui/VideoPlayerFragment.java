package com.fadcam.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.fadcam.R;

public class VideoPlayerFragment extends Fragment {

    private static final String ARG_VIDEO_PATH = "video_path";
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private ImageButton backButton;

    public static VideoPlayerFragment newInstance(String videoPath) {
        VideoPlayerFragment fragment = new VideoPlayerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_VIDEO_PATH, videoPath);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_video_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        playerView = view.findViewById(R.id.player_view);
        backButton = view.findViewById(R.id.back_button);

        String videoPath = getArguments().getString(ARG_VIDEO_PATH);
        initializePlayer(videoPath);
        setupBackButton();
    }

    private void initializePlayer(String videoPath) {
        player = new ExoPlayer.Builder(requireContext()).build();
        playerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoPath));
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    private void setupBackButton() {
        backButton.setOnClickListener(v -> getParentFragmentManager().popBackStack());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}