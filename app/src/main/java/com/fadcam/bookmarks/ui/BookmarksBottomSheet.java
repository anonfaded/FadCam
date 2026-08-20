package com.fadcam.bookmarks.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.bookmarks.Bookmark;
import com.fadcam.bookmarks.BookmarkRepository;
import com.fadcam.ui.faditor.util.TimeFormatter;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/**
 * Lists the bookmarks of the video being played so the user can jump straight to
 * a marked moment, drop a single mark, or clear them all.
 *
 * <p>The host supplies the seek behaviour through {@link Listener}; the sheet owns
 * the storage side and reports back whenever the set of bookmarks changed so the
 * timeline markers can be redrawn.</p>
 */
public class BookmarksBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_MEDIA_NAME = "media_name";

    /** Callbacks the hosting player must provide. */
    public interface Listener {
        /**
         * Seeks the player to a bookmarked moment.
         *
         * @param positionMs the bookmark position, in milliseconds
         */
        void onBookmarkSelected(long positionMs);

        /** Called after any change to the stored bookmarks. */
        void onBookmarksChanged();
    }

    private String mediaName;
    private BookmarkRepository repository;
    private LinearLayout container;
    private TextView emptyText;
    private TextView clearAllButton;

    /**
     * @param mediaName display name of the video whose bookmarks are shown
     * @return a configured sheet instance
     */
    @NonNull
    public static BookmarksBottomSheet newInstance(@NonNull String mediaName) {
        BookmarksBottomSheet sheet = new BookmarksBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_MEDIA_NAME, mediaName);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaName = getArguments() != null ? getArguments().getString(ARG_MEDIA_NAME) : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_bookmarks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = BookmarkRepository.getInstance(requireContext());
        container = view.findViewById(R.id.bookmarks_container);
        emptyText = view.findViewById(R.id.bookmarks_empty_text);
        clearAllButton = view.findViewById(R.id.bookmarks_clear_all);
        clearAllButton.setOnClickListener(v -> {
            repository.clear(mediaName);
            notifyChanged();
            dismiss();
        });
        renderBookmarks();
    }

    /** Rebuilds the list from storage. */
    private void renderBookmarks() {
        container.removeAllViews();
        List<Bookmark> bookmarks = repository.getAll(mediaName);
        boolean empty = bookmarks.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        clearAllButton.setVisibility(empty ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int index = 0; index < bookmarks.size(); index++) {
            final Bookmark bookmark = bookmarks.get(index);
            View row = inflater.inflate(R.layout.item_bookmark_row, container, false);
            TextView position = row.findViewById(R.id.bookmark_position);
            TextView label = row.findViewById(R.id.bookmark_label);
            TextView delete = row.findViewById(R.id.bookmark_delete);

            position.setText(TimeFormatter.formatAuto(bookmark.getPositionMs()));
            label.setText(getString(R.string.bookmarks_item_label, index + 1));

            row.setOnClickListener(v -> {
                Listener listener = resolveListener();
                if (listener != null) {
                    listener.onBookmarkSelected(bookmark.getPositionMs());
                }
                dismiss();
            });
            delete.setOnClickListener(v -> {
                repository.remove(mediaName, bookmark.getPositionMs());
                notifyChanged();
                renderBookmarks();
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Math.round(8 * getResources().getDisplayMetrics().density);
            container.addView(row, params);
        }
    }

    /** Tells the host that stored bookmarks changed. */
    private void notifyChanged() {
        Listener listener = resolveListener();
        if (listener != null) {
            listener.onBookmarksChanged();
        }
    }

    /** @return the hosting activity as a {@link Listener}, or {@code null}. */
    @Nullable
    private Listener resolveListener() {
        return getActivity() instanceof Listener ? (Listener) getActivity() : null;
    }
}
