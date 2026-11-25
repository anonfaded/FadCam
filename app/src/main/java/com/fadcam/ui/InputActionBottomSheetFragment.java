package com.fadcam.ui;

import android.os.Bundle;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.util.PreferencesBackupUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONObject;

/**
 * InputActionBottomSheetFragment for previewing JSON and confirming destructive
 * reset via unified sheet.
 */
public class InputActionBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_MODE = "mode";
    private static final String ARG_TITLE = "title";
    private static final String ARG_JSON = "json";
    private static final String ARG_REQUIRED_PHRASE = "phrase";
    private static final String ARG_ACTION_TITLE = "action_title";
    private static final String ARG_ACTION_SUBTITLE = "action_subtitle";
    private static final String ARG_ACTION_ICON = "action_icon";
    private static final String ARG_INPUT_VALUE = "input_value";
    private static final String ARG_INPUT_HINT = "input_hint";
    private static final String ARG_HELPER_TEXT = "helper_text";
    /** Confirmation rows are destructive (red) unless a caller says otherwise. */
    private static final String ARG_DESTRUCTIVE = "destructive";
    /** Optional live "will be saved as …" preview: a string format with one %1$s. */
    private static final String ARG_FILENAME_PREVIEW = "filename_preview";
    private static final String MODE_PREVIEW = "preview";
    private static final String MODE_RESET = "reset";
    private static final String MODE_INPUT = "input";
    // Lightweight confirmation mode (no text input) for single-action confirms
    private static final String MODE_CONFIRM = "confirm";

    public interface Callbacks {
        void onImportConfirmed(JSONObject json);

        void onResetConfirmed();

        default void onInputConfirmed(String input) {
            /* optional */ }
    }

    private Callbacks callbacks;

    public void setCallbacks(Callbacks cb) {
        this.callbacks = cb;
    }

    /**
     * Optional convenience to attach helper text (shown under the title) from callers
     * without exposing internal argument keys.
     */
    public InputActionBottomSheetFragment withHelperText(@Nullable String helperText) {
        Bundle args = getArguments();
        if (args == null) {
            args = new Bundle();
            setArguments(args);
        }
        args.putString(ARG_HELPER_TEXT, helperText);
        return this;
    }

    public static InputActionBottomSheetFragment newPreview(String title, String json) {
        InputActionBottomSheetFragment f = new InputActionBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, MODE_PREVIEW);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_JSON, json);
        f.setArguments(b);
        return f;
    }

    public static InputActionBottomSheetFragment newReset(String title, String phrase) {
        InputActionBottomSheetFragment f = new InputActionBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, MODE_RESET);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_REQUIRED_PHRASE, phrase);
        f.setArguments(b);
        return f;
    }

    /**
     * Create a simple confirmation sheet with a single action row. No text input.
     */
    public static InputActionBottomSheetFragment newConfirm(String title, String actionTitle, String actionSubtitle,
            int actionIconRes) {
        return newConfirm(title, actionTitle, actionSubtitle, actionIconRes, null);
    }

    /**
     * Create a simple confirmation sheet with a single action row and optional helper text.
     */
    public static InputActionBottomSheetFragment newConfirm(String title, String actionTitle, String actionSubtitle,
            int actionIconRes, @Nullable String helperText) {
        return newConfirm(title, actionTitle, actionSubtitle, actionIconRes, helperText, true);
    }

    /**
     * Confirmation sheet whose action row can be styled as a normal row. Destructive
     * actions (delete/reset) keep the red tint; anything that just proceeds — an export,
     * for example — should not look like it is about to delete something.
     */
    public static InputActionBottomSheetFragment newConfirm(String title, String actionTitle,
            String actionSubtitle, int actionIconRes, @Nullable String helperText, boolean destructive) {
        InputActionBottomSheetFragment f = new InputActionBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, MODE_CONFIRM);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_ACTION_TITLE, actionTitle);
        b.putString(ARG_ACTION_SUBTITLE, actionSubtitle);
        b.putInt(ARG_ACTION_ICON, actionIconRes);
        b.putBoolean(ARG_DESTRUCTIVE, destructive);
        if (helperText != null) b.putString(ARG_HELPER_TEXT, helperText);
        f.setArguments(b);
        return f;
    }

    /**
     * More flexible reset constructor allowing callers to customize the action row
     * title, subtitle and icon.
     * Backwards compatible with existing callers which use the simpler overload.
     */
    public static InputActionBottomSheetFragment newReset(String title, String phrase, String actionTitle,
            String actionSubtitle, int actionIconRes) {
        InputActionBottomSheetFragment f = new InputActionBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, MODE_RESET);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_REQUIRED_PHRASE, phrase);
        b.putString(ARG_ACTION_TITLE, actionTitle);
        b.putString(ARG_ACTION_SUBTITLE, actionSubtitle);
        b.putInt(ARG_ACTION_ICON, actionIconRes);
        f.setArguments(b);
        return f;
    }

    /**
     * Input sheet that also previews the sanitised filename under the field, so the
     * user sees exactly what the export will be called while they type.
     */
    public static InputActionBottomSheetFragment newFileNameInput(String title,
            String initialValue, String hint, String actionTitle, String actionSubtitle,
            int actionIconRes, String helperText, String previewFormat) {
        InputActionBottomSheetFragment f = newInput(title, initialValue, hint,
                actionTitle, actionSubtitle, actionIconRes, helperText);
        f.getArguments().putString(ARG_FILENAME_PREVIEW, previewFormat);
        return f;
    }

    /** Create a simple input sheet (single-line) with customizable action row. */
    public static InputActionBottomSheetFragment newInput(String title, String initialValue, String hint,
            String actionTitle, String actionSubtitle, int actionIconRes) {
        return newInput(title, initialValue, hint, actionTitle, actionSubtitle, actionIconRes, null);
    }

    /**
     * Create a simple input sheet (single-line) with customizable action row and
     * helper text.
     */
    public static InputActionBottomSheetFragment newInput(String title, String initialValue, String hint,
            String actionTitle, String actionSubtitle, int actionIconRes, String helperText) {
        InputActionBottomSheetFragment f = new InputActionBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, MODE_INPUT);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_INPUT_VALUE, initialValue);
        b.putString(ARG_INPUT_HINT, hint);
        b.putString(ARG_ACTION_TITLE, actionTitle);
        b.putString(ARG_ACTION_SUBTITLE, actionSubtitle);
        b.putInt(ARG_ACTION_ICON, actionIconRes);
        b.putString(ARG_HELPER_TEXT, helperText);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        String mode = args != null ? args.getString(ARG_MODE) : null;
        String title = args != null ? args.getString(ARG_TITLE) : null;
        TextView tvTitle = view.findViewById(R.id.picker_title);
        if (tvTitle != null && title != null)
            tvTitle.setText(title);

        // Handle helper text
        TextView helper = view.findViewById(R.id.picker_helper);
        String helperText = args != null ? args.getString(ARG_HELPER_TEXT) : null;
        if (helper != null) {
            if (helperText != null && !helperText.trim().isEmpty()) {
                helper.setText(helperText);
                helper.setVisibility(View.VISIBLE);
            } else {
                helper.setVisibility(View.GONE);
            }
        }

        // Handle close button
        ImageView closeButton = view.findViewById(R.id.picker_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        LinearLayout list = view.findViewById(R.id.picker_list_container);
        if (list != null) {
            list.removeAllViews();
            if (MODE_PREVIEW.equals(mode)) {
                buildPreview(list, args.getString(ARG_JSON));
            } else if (MODE_RESET.equals(mode)) {
                buildReset(list, args.getString(ARG_REQUIRED_PHRASE));
            } else if (MODE_INPUT.equals(mode)) {
                buildInput(list, args.getString(ARG_INPUT_VALUE), args.getString(ARG_INPUT_HINT));
            } else if (MODE_CONFIRM.equals(mode)) {
                buildConfirm(list);
            }
        }
    }

    /** Build a confirm-only sheet with a single action row that triggers onResetConfirmed(). */
    private void buildConfirm(LinearLayout parent) {
        // No divider for single-row confirm sheet
        Bundle args = getArguments();
        String actionTitle = args != null ? args.getString(ARG_ACTION_TITLE) : null;
        String actionSubtitle = args != null ? args.getString(ARG_ACTION_SUBTITLE) : null;
        int actionIcon = args != null ? args.getInt(ARG_ACTION_ICON, R.drawable.ic_delete) : R.drawable.ic_delete;
        final String finalActionTitle = actionTitle != null ? actionTitle : getString(R.string.prefs_reset_label);
        final String finalActionSubtitle = actionSubtitle != null ? actionSubtitle : "";
        // Destructive styling is opt-out; see newConfirm(...).
        boolean destructive = args == null || args.getBoolean(ARG_DESTRUCTIVE, true);
        parent.addView(actionRow(actionIcon, finalActionTitle, finalActionSubtitle, destructive, v -> {
            // Dismiss first for snappy UX, then callback
            try { dismiss(); } catch (Exception ignored) {}
            if (callbacks != null) callbacks.onResetConfirmed();
        }));
    }

    private void buildInput(LinearLayout parent, String initialValue, String hint) {
        final EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        if (initialValue != null)
            input.setText(initialValue);
        if (hint != null)
            input.setHint(hint);
        input.setBackgroundResource(R.drawable.prefs_input_bg);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white));
        input.setHintTextColor(0xFF777777);
        applyThemedInputAccent(input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        parent.addView(input, lp);

        // Live "saved as" preview: the export name is derived from what is typed, so
        // show the derived form rather than letting the file surprise the user.
        Bundle previewArgs = getArguments();
        final String previewFormat = previewArgs != null
                ? previewArgs.getString(ARG_FILENAME_PREVIEW) : null;
        if (previewFormat != null) {
            final TextView preview = new TextView(requireContext());
            preview.setTextSize(12f);
            preview.setTextColor(0xFF9E9E9E);
            preview.setPadding(dp(16), dp(6), dp(16), dp(2));
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            parent.addView(preview, previewLp);
            final java.util.function.Function<String, String> slug =
                    com.fadcam.ui.faditor.util.FileNameSlug::slugify;
            final Runnable refresh = () -> {
                String safe = slug.apply(input.getText().toString());
                if (safe.isEmpty()) {
                    preview.setVisibility(View.GONE);
                } else {
                    preview.setVisibility(View.VISIBLE);
                    preview.setText(String.format(previewFormat, safe));
                }
            };
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable s) { refresh.run(); }
            });
            refresh.run();
        }

        parent.addView(makeDivider());

        // Allow callers to override the action row's title/subtitle/icon via arguments
        Bundle args = getArguments();
        String actionTitle = args != null ? args.getString(ARG_ACTION_TITLE) : null;
        String actionSubtitle = args != null ? args.getString(ARG_ACTION_SUBTITLE) : null;
        int actionIcon = args != null ? args.getInt(ARG_ACTION_ICON, R.drawable.ic_edit_cut) : R.drawable.ic_edit_cut;

        final String finalActionTitle = actionTitle != null ? actionTitle : getString(R.string.prefs_reset_label);
        final String finalActionSubtitle = actionSubtitle != null ? actionSubtitle : "";

        parent.addView(actionRow(actionIcon, finalActionTitle, finalActionSubtitle, false, v -> {
            String val = input.getText().toString().trim();
            // Close first, always. The caller may reload a list or save a project, and
            // the sheet must never be left hovering over a completed action.
            dismissAllowingStateLoss();
            if (callbacks != null) {
                callbacks.onInputConfirmed(val);
            }
        }));
    }

    private void buildPreview(LinearLayout parent, String jsonStr) {
        JSONObject json = null;
        try {
            if (jsonStr != null) {
                json = new JSONObject(jsonStr);
            }
        } catch (Exception e) {
            // Malformed JSON: show a clear error instead of a blank/crash.
            TextView err = new TextView(requireContext());
            err.setText(getString(R.string.prefs_import_failed) + "\n" + e.getMessage());
            err.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.holo_red_light));
            err.setTextSize(13f);
            err.setPadding(dp(16), dp(12), dp(16), dp(12));
            parent.addView(err);
            return;
        }

        if (json == null) {
            Toast.makeText(requireContext(), getString(R.string.prefs_preview_none), Toast.LENGTH_SHORT).show();
            return;
        }

        // Simple, clean preview: list what will be imported. No warning counts,
        // no scare text — the import auto-repairs types behind the scenes.
        java.util.List<com.fadcam.util.PreferencesBackupUtil.PreviewEntry> entries =
                com.fadcam.util.PreferencesBackupUtil.buildPreviewEntries(requireContext(), json);

        // ── Summary chip (rounded, matches picker banner style) ──────
        TextView summaryChip = new TextView(requireContext());
        summaryChip.setText(getString(R.string.prefs_preview_ready, entries.size()));
        android.util.TypedValue accentValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(R.attr.homeRailAccent, accentValue, true);
        summaryChip.setTextColor(accentValue.data);
        summaryChip.setTextSize(13f);
        summaryChip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        summaryChip.setPadding(dp(14), dp(10), dp(14), dp(10));
        summaryChip.setBackgroundResource(R.drawable.picker_banner_bg);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.setMargins(dp(12), dp(10), dp(12), dp(4));
        parent.addView(summaryChip, chipLp);

        // ── Scrolling key/value list ────────────────────────────────
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout rows = new LinearLayout(requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(14);
        rows.setPadding(padH, dp(4), padH, dp(4));

        for (com.fadcam.util.PreferencesBackupUtil.PreviewEntry e : entries) {
            rows.addView(previewEntryRow(e));
        }

        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        scrollLp.topMargin = dp(4);
        parent.addView(scroll, scrollLp);

        // Prevent the sheet from being dismissed by a fling while the preview
        // is scrollable — otherwise scrolling the list closes the dialog.
        lockSheetDragWhileScrolling(scroll);

        parent.addView(makeDivider());

        final JSONObject finalJson = json;
        parent.addView(actionRow(R.drawable.ic_content_copy, getString(R.string.prefs_import_label),
                getString(R.string.prefs_import_subtitle), false, v -> {
                    if (finalJson == null) {
                        Toast.makeText(requireContext(), getString(R.string.prefs_preview_none), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Dismiss the preview sheet first so it doesn't linger over
                    // the app after the import (which recreates the activity).
                    try { dismiss(); } catch (Exception ignored) {}
                    if (callbacks != null) {
                        callbacks.onImportConfirmed(finalJson);
                    }
                }));
    }

    /** Builds one color-coded row: key (purple) — type chip — value (JSON-syntax colored). */
    private View previewEntryRow(com.fadcam.util.PreferencesBackupUtil.PreviewEntry e) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Key — soft blue (dark-theme JSON key color)
        TextView key = new TextView(requireContext());
        key.setText(e.key);
        key.setTypeface(key.getTypeface(), android.graphics.Typeface.BOLD);
        key.setTextSize(13f);
        key.setTextColor(0xFF82AAFF);
        key.setSingleLine(true);
        key.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(key, keyLp);

        // Type chip — translucent bg, tinted text per type
        TextView type = new TextView(requireContext());
        type.setText(e.type);
        type.setTextSize(10f);
        type.setPadding(dp(6), dp(2), dp(6), dp(2));
        type.setTextColor(valueColor(e.type, e.value));
        type.setBackgroundResource(R.drawable.prefs_type_chip_bg);
        type.setTypeface(type.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(type);
        row.addView(top);

        // Value — dark-theme JSON syntax colored
        TextView value = new TextView(requireContext());
        value.setText(e.value);
        value.setTextSize(12f);
        value.setTextColor(valueColor(e.type, e.value));
        value.setPadding(dp(0), 0, 0, 0);
        row.addView(value);

        return row;
    }

    /**
     * Dark-background JSON syntax palette (Material Ocean / One Dark inspired):
     * keys soft blue, strings soft green, numbers coral, booleans cyan, sets
     * light blue-gray. Deliberately avoids purple / danger red / warning yellow.
     */
    private int valueColor(String type, String value) {
        if (type == null) return 0xFFD6DEEB;
        switch (type) {
            case "String": return 0xFFC3E88D;          // soft green
            case "Int":
            case "Long":
            case "Float":
            case "Number": return 0xFFF78C6C;          // soft coral
            case "Boolean": return 0xFF89DDFF;         // cyan
            case "Set<String>": return 0xFFD6DEEB;     // light blue-gray
            default: return 0xFFD6DEEB;
        }
    }

    /**
     * While the inner ScrollView is scrolled down (content above), the sheet
     * must not be draggable — otherwise a scroll fling dismisses the dialog.
     * When the scroll is back at the very top, the sheet becomes draggable
     * again so the user can still swipe it down to close.
     */
    private void lockSheetDragWhileScrolling(android.widget.ScrollView scroll) {
        try {
            if (getDialog() == null) return;
            View sheet = getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
            scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                    behavior.setDraggable(scrollY <= 0));
        } catch (Exception ignored) {}
    }

    private void buildReset(LinearLayout parent, String phrase) {
        TextView info = new TextView(requireContext());
        info.setText(getString(R.string.prefs_reset_type_delete));
        info.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray));
        info.setTextSize(13f);
        info.setPadding(dp(16), dp(4), dp(16), dp(4));
        parent.addView(info);

        final EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint(phrase);
        // Removed automatic all-caps transformation so user must manually enter correct
        // uppercase phrase (case sensitive requirement).
        input.setBackgroundResource(R.drawable.prefs_input_bg);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white));
        input.setHintTextColor(0xFF777777);
        applyThemedInputAccent(input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        parent.addView(input, lp);
        parent.addView(makeDivider());

        // Allow callers to override the action row's title/subtitle/icon via arguments
        Bundle args = getArguments();
        String actionTitle = args != null ? args.getString(ARG_ACTION_TITLE) : null;
        String actionSubtitle = args != null ? args.getString(ARG_ACTION_SUBTITLE) : null;
        int actionIcon = args != null ? args.getInt(ARG_ACTION_ICON, R.drawable.ic_delete) : R.drawable.ic_delete;

        final String finalActionTitle = actionTitle != null ? actionTitle : getString(R.string.prefs_reset_label);
        final String finalActionSubtitle = actionSubtitle != null ? actionSubtitle
                : getString(R.string.prefs_reset_subtitle);

        parent.addView(actionRow(actionIcon, finalActionTitle, finalActionSubtitle, true, v -> {
            String val = input.getText().toString().trim();
            if (phrase != null && phrase.equals(val)) { // case sensitive match
                if (callbacks != null) {
                    callbacks.onResetConfirmed();
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.prefs_reset_not_matched), Toast.LENGTH_SHORT)
                        .show();
            }
        }));
    }

    private View makeDivider() {
        View d = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(8), 0, dp(8));
        d.setLayoutParams(lp);
        d.setBackgroundColor(0x33FFFFFF);
        return d;
    }

    private LinearLayout actionRow(int iconRes, String title, String subtitle, View.OnClickListener click) {
        return actionRow(iconRes, title, subtitle, false, click);
    }

    /**
     * Builds a standard action row. When destructive is true, applies a subtle red
     * tinted background consistent with PickerBottomSheetFragment's delete styling.
     */
    private LinearLayout actionRow(int iconRes, String title, String subtitle, boolean destructive, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setOnClickListener(click);
        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.setMarginEnd(dp(16));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconRes);
        icon.setImageTintList(
                android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray)));
        row.addView(icon);
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView primary = new TextView(requireContext());
        primary.setText(title);
        primary.setTypeface(primary.getTypeface(), android.graphics.Typeface.BOLD);
        android.util.TypedValue heading = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(R.attr.colorHeading, heading, true)) {
            primary.setTextColor(heading.data);
        } else {
            primary.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.colorHeading));
        }
        primary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        TextView secondary = new TextView(requireContext());
        secondary.setText(subtitle);
        secondary.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray));
        secondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        text.addView(primary);
        text.addView(secondary);
        row.addView(text);
        android.widget.ImageView arrow = new android.widget.ImageView(requireContext());
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(14)));
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(
                android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray)));
        row.addView(arrow);

        if (destructive) {
            try {
                // Apply subtle red overlay tint similar to PickerBottomSheetFragment
                row.getBackground().mutate();
                row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FF3B30));
            } catch (Exception ignored) {}
        }
        return row;
    }

    /**
     * Gives a text field the hosting theme's accent for its cursor, handles and
     * selection. A bottom sheet is its own window and Material falls back to its
     * purple defaults when nothing resolves the accent.
     */
    private void applyThemedInputAccent(@NonNull EditText input) {
        android.util.TypedValue accent = new android.util.TypedValue();
        if (!requireContext().getTheme().resolveAttribute(R.attr.colorHeading, accent, true)) {
            return;
        }
        // ONLY the selection highlight. Tinting the background made the whole field
        // look like a red/gold error state; the field keeps its own dark surface.
        input.setHighlightColor((accent.data & 0x00FFFFFF) | 0x4D000000);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public void onStart() {
        super.onStart();
        View view = getView();
        if (view != null) {
            // Landscape: a side panel, so it does not cover the short screen.
            com.fadcam.ui.utils.SideSheet.applyIfLandscape(
                    requireDialog(), view);
        }
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        });
        return dialog;
    }
}
