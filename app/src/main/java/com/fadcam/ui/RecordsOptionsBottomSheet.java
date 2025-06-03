package com.fadcam.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class RecordsOptionsBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "RecordsOptionsSheet";
    private SharedPreferencesManager sharedPreferencesManager;
    private OnSortOptionSelectedListener sortListener;
    private OnDeleteAllClickedListener deleteListener;
    private SortOption currentSortOption;

    // Enum for sort options (copied from RecordsFragment)
    public enum SortOption { LATEST_FIRST, OLDEST_FIRST, SMALLEST_FILES, LARGEST_FILES }

    public interface OnSortOptionSelectedListener {
        void onSortOptionSelected(SortOption sortOption);
    }

    public interface OnDeleteAllClickedListener {
        void onDeleteAllClicked();
    }

    // Constructor with all parameters
    public RecordsOptionsBottomSheet(SharedPreferencesManager sharedPreferencesManager, 
                                  SortOption currentSortOption,
                                  OnSortOptionSelectedListener sortListener,
                                  OnDeleteAllClickedListener deleteListener) {
        this.sharedPreferencesManager = sharedPreferencesManager;
        this.currentSortOption = currentSortOption;
        this.sortListener = sortListener;
        this.deleteListener = deleteListener;
    }
    
    // Constructor with just listeners (SharedPreferencesManager will be obtained from context)
    public RecordsOptionsBottomSheet(SortOption currentSortOption,
                                  OnSortOptionSelectedListener sortListener,
                                  OnDeleteAllClickedListener deleteListener) {
        this.currentSortOption = currentSortOption;
        this.sortListener = sortListener;
        this.deleteListener = deleteListener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_records_options, container, false);

        // Initialize SharedPreferencesManager if needed
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }

        // Get current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        // Set up the sort options radio group
        RadioGroup sortOptionsGroup = view.findViewById(R.id.sort_options_group);
        LinearLayout deleteAllOption = view.findViewById(R.id.option_delete_all);

        // Pre-select current sort option
        if (sortOptionsGroup != null) {
            switch (currentSortOption) {
                case LATEST_FIRST: sortOptionsGroup.check(R.id.sort_latest); break;
                case OLDEST_FIRST: sortOptionsGroup.check(R.id.sort_oldest); break;
                case SMALLEST_FILES: sortOptionsGroup.check(R.id.sort_smallest); break;
                case LARGEST_FILES: sortOptionsGroup.check(R.id.sort_largest); break;
            }
            Log.d(TAG, "Sort options pre-checked: " + currentSortOption);

            sortOptionsGroup.setOnCheckedChangeListener((group, checkedId) -> {
                SortOption newSortOption = currentSortOption; // Start assuming no change
                if (checkedId == R.id.sort_latest) newSortOption = SortOption.LATEST_FIRST;
                else if (checkedId == R.id.sort_oldest) newSortOption = SortOption.OLDEST_FIRST;
                else if (checkedId == R.id.sort_smallest) newSortOption = SortOption.SMALLEST_FILES;
                else if (checkedId == R.id.sort_largest) newSortOption = SortOption.LARGEST_FILES;

                if (newSortOption != currentSortOption) {
                    Log.i(TAG, "Sort option changed to: " + newSortOption);
                    
                    // Call the sort listener
                    if (sortListener != null) {
                        sortListener.onSortOptionSelected(newSortOption);
                    }
                } else {
                    Log.d(TAG, "Sort option clicked, but no change: " + currentSortOption);
                }
                dismiss();
            });
        }

        if (deleteAllOption != null) {
            deleteAllOption.setOnClickListener(v -> {
                dismiss();
                if (deleteListener != null) {
                    deleteListener.onDeleteAllClicked();
                }
            });
        }

        // Apply theme-specific styling
        if (isSnowVeilTheme) {
            // Use white text for Snow Veil theme in bottom sheet
            int textColorPrimary = Color.WHITE;
            int textColorSecondary = Color.WHITE;
            
            // Find and update the title text color
            TextView titleTextView = view.findViewById(R.id.bottom_sheet_title);
            if (titleTextView != null) {
                titleTextView.setTextColor(Color.RED); // Keep red title for visibility
            }
            
            // Find sort by title
            TextView sortByTextView = (TextView) ((ViewGroup) sortOptionsGroup.getParent()).getChildAt(0);
            if (sortByTextView != null) {
                sortByTextView.setTextColor(Color.WHITE);
            }
            
            // Ensure radio buttons have white tint
            if (sortOptionsGroup != null) {
                // Create a white ColorStateList for radio buttons
                int[][] states = new int[][] {
                    new int[] { android.R.attr.state_checked },  // checked state
                    new int[] { -android.R.attr.state_checked }  // unchecked state
                };
                int[] colors = new int[] {
                    Color.WHITE,  // color for checked state - WHITE
                    Color.WHITE   // color for unchecked state - WHITE
                };
                android.content.res.ColorStateList colorStateList = new android.content.res.ColorStateList(states, colors);
                
                for (int i = 0; i < sortOptionsGroup.getChildCount(); i++) {
                    View child = sortOptionsGroup.getChildAt(i);
                    if (child instanceof RadioButton) {
                        ((RadioButton) child).setButtonTintList(colorStateList);
                        ((RadioButton) child).setTextColor(Color.WHITE);
                    }
                }
            }
            
            // Set other text colors
            setTextColorsRecursive(view, textColorPrimary, textColorSecondary);
        } else if (isFadedNightTheme) {
            // Use white text and radio buttons for Faded Night theme
            int textColorPrimary = ContextCompat.getColor(requireContext(), android.R.color.white);
            int textColorSecondary = ContextCompat.getColor(requireContext(), R.color.gray_text_light);
            
            // Ensure radio buttons have white tint
            if (sortOptionsGroup != null) {
                // Create a white ColorStateList for radio buttons
                int[][] states = new int[][] {
                    new int[] { android.R.attr.state_checked },  // checked state
                    new int[] { -android.R.attr.state_checked }  // unchecked state
                };
                int[] colors = new int[] {
                    Color.WHITE,  // color for checked state - WHITE
                    Color.WHITE   // color for unchecked state - WHITE
                };
                android.content.res.ColorStateList colorStateList = new android.content.res.ColorStateList(states, colors);
                
                for (int i = 0; i < sortOptionsGroup.getChildCount(); i++) {
                    View child = sortOptionsGroup.getChildAt(i);
                    if (child instanceof RadioButton) {
                        ((RadioButton) child).setButtonTintList(colorStateList);
                        ((RadioButton) child).setTextColor(Color.WHITE);
                    }
                }
            }
            
            // Set other text colors
            setTextColorsRecursive(view, textColorPrimary, textColorSecondary);
        }

        return view;
    }

    private void setTextColorsRecursive(View view, int primary, int secondary) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setTextColorsRecursive(group.getChildAt(i), primary, secondary);
            }
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            
            // Skip setting text color for delete option as it has special colors
            if (textView.getParent() != null && 
                textView.getParent().getParent() != null && 
                textView.getParent().getParent() instanceof LinearLayout &&
                ((View)textView.getParent().getParent()).getId() == R.id.option_delete_all) {
                return;
            }
            
            // Skip the title text which should remain red for visibility
            if (textView.getId() == R.id.bottom_sheet_title) {
                return;
            }
            
            textView.setTextColor(primary);
        }
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog).findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.gradient_background);
            }
        });
        return dialog;
    }
} 