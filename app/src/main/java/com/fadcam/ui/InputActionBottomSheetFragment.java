package com.fadcam.ui;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
    private static final String MODE_PREVIEW = "preview";
    private static final String MODE_RESET = "reset";
    private static final String MODE_INPUT = "input";

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
            }
        }
    }

    // -------------- Fix Start for this method(buildInput)-----------
    private void buildInput(LinearLayout parent, String initialValue, String hint) {
        final EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        if (initialValue != null)
            input.setText(initialValue);
        if (hint != null)
            input.setHint(hint);
        input.setBackgroundResource(R.drawable.prefs_input_bg);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setTextColor(getResources().getColor(android.R.color.white));
        input.setHintTextColor(0xFF777777);
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
        int actionIcon = args != null ? args.getInt(ARG_ACTION_ICON, R.drawable.ic_edit_cut) : R.drawable.ic_edit_cut;

        final String finalActionTitle = actionTitle != null ? actionTitle : getString(R.string.prefs_reset_label);
        final String finalActionSubtitle = actionSubtitle != null ? actionSubtitle : "";

        parent.addView(actionRow(actionIcon, finalActionTitle, finalActionSubtitle, v -> {
            String val = input.getText().toString().trim();
            if (callbacks != null) {
                callbacks.onInputConfirmed(val);
            }
        }));
    }
    // -------------- Fix Ended for this method(buildInput)-----------

    // -------------- Fix Start for this method(buildPreview)-----------
    private void buildPreview(LinearLayout parent, String jsonStr) {
        JSONObject json = null;
        String pretty = "";
        try {
            if (jsonStr != null) {
                json = new JSONObject(jsonStr);
                pretty = json.toString(2); // standard pretty print
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.prefs_import_failed) + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        String withLines = addLineNumbers(pretty);
        CharSequence highlighted = colorizeJson(withLines);
        HorizontalScrollView hsv = new HorizontalScrollView(requireContext());
        hsv.setHorizontalScrollBarEnabled(true);
        android.widget.ScrollView vScroll = new android.widget.ScrollView(requireContext());
        vScroll.setVerticalScrollBarEnabled(true);
        int fixedH = dp(240);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fixedH);
        TextView tv = new TextView(requireContext());
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12f);
        tv.setHorizontallyScrolling(true);
        tv.setText(highlighted);
        tv.setTextColor(getResources().getColor(android.R.color.white));
        int pad = dp(12);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackgroundColor(0xFF121212);
        vScroll.addView(tv,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hsv.addView(vScroll,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(hsv, hsvLp);
        parent.addView(makeDivider());
        final JSONObject finalJson = json;
        parent.addView(actionRow(R.drawable.ic_content_copy, getString(R.string.prefs_import_label),
                getString(R.string.prefs_import_subtitle), v -> {
                    if (finalJson == null) {
                        Toast.makeText(requireContext(), getString(R.string.prefs_preview_none), Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    if (callbacks != null) {
                        callbacks.onImportConfirmed(finalJson);
                    }
                }));
    }
    // -------------- Fix Ended for this method(buildPreview)-----------

    /**
     * Apply lightweight JSON syntax highlighting over line-numbered text using
     * existing palette colors.
     */
    private CharSequence colorizeJson(String text) {
        if (text == null || text.isEmpty())
            return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int cKey = getResources().getColor(R.color.colorPrimary);
        int cString = getResources().getColor(R.color.greenPastel);
        int cNumber = getResources().getColor(R.color.gold);
        int cBoolean = getResources().getColor(R.color.redPastel);
        int cNull = getResources().getColor(R.color.gray500);
        int cPunc = getResources().getColor(android.R.color.darker_gray);
        int cLine = getResources().getColor(R.color.gray500);

        // Line numbers: start of line up to pipe
        applyRegex(sb, "(?m)^(\\s*\\d+\\s+\\|)", cLine);
        // Keys: "...": (highlight just the quoted name)
        applyRegex(sb, "\\\"[^\\\"\\n]*\\\"(?=\\s*:)", cKey);
        // String values (quoted strings not followed by colon)
        applyRegex(sb, "\\\"[^\\\"\\n]*\\\"(?!\\s*:)", cString);
        // Numbers
        applyRegex(sb, "(?<=:|,|\\\\[|\\\\{)\\s*-?\\d+(?:\\\\.\\d+)?(?=\\n|,|\\\\}|\\\\])", cNumber);
        // Booleans
        applyRegex(sb, "(?<=:|,|\\\\[|\\\\{)\\s*(true|false)(?=\\n|,|\\\\}|\\\\])", cBoolean);
        // null
        applyRegex(sb, "(?<=:|,|\\\\[|\\\\{)\\s*(null)(?=\\n|,|\\\\}|\\\\])", cNull);
        // Braces/brackets/colons/commas
        applyRegex(sb, "[{}\\\\[\\\\]:,]", cPunc);
        return sb;
    }

    private void applyRegex(SpannableStringBuilder sb, String pattern, int color) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(sb);
            while (m.find()) {
                sb.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } catch (Exception ignore) {
        }
    }

    private String addLineNumbers(String text) {
        if (text == null || text.isEmpty())
            return "";
        String[] lines = text.split("\n");
        int digits = (int) Math.floor(Math.log10(Math.max(1, lines.length))) + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            sb.append(String.format(java.util.Locale.US, "%" + digits + "d | ", lineNo)).append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    // -------------- Fix Start for this method(buildReset)-----------
    private void buildReset(LinearLayout parent, String phrase) {
        TextView info = new TextView(requireContext());
        info.setText(getString(R.string.prefs_reset_type_delete));
        info.setTextColor(getResources().getColor(android.R.color.darker_gray));
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
        input.setTextColor(getResources().getColor(android.R.color.white));
        input.setHintTextColor(0xFF777777);
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

        parent.addView(actionRow(actionIcon, finalActionTitle, finalActionSubtitle, v -> {
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
    // -------------- Fix Ended for this method(buildReset)-----------

    private View makeDivider() {
        View d = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(8), 0, dp(8));
        d.setLayoutParams(lp);
        d.setBackgroundColor(0x33FFFFFF);
        return d;
    }

    private LinearLayout actionRow(int iconRes, String title, String subtitle, View.OnClickListener click) {
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
                android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(icon);
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView primary = new TextView(requireContext());
        primary.setText(title);
        primary.setTypeface(primary.getTypeface(), android.graphics.Typeface.BOLD);
        primary.setTextColor(getResources().getColor(R.color.colorHeading));
        primary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        TextView secondary = new TextView(requireContext());
        secondary.setText(subtitle);
        secondary.setTextColor(getResources().getColor(android.R.color.darker_gray));
        secondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        text.addView(primary);
        text.addView(secondary);
        row.addView(text);
        android.widget.ImageView arrow = new android.widget.ImageView(requireContext());
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(14)));
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(
                android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(arrow);
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
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
