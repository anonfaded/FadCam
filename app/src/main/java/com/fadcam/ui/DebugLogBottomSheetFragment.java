package com.fadcam.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * DebugLogBottomSheetFragment
 * Full-screen style bottom sheet to Preview/Search/Share/Delete the FADCAM_debug.html log.
 */
public class DebugLogBottomSheetFragment extends BottomSheetDialogFragment {
    private static final int MAX_LINES = 1000; // viewer cap

    public static DebugLogBottomSheetFragment newInstance(){ return new DebugLogBottomSheetFragment(); }

    private ActivityResultLauncher<Intent> shareLauncher;
    private RecyclerView previewRv;
    private EditText searchEdit;
    private int currentMatchIndex = -1;
    private java.util.List<String> linesHtml = new java.util.ArrayList<>();
    private java.util.List<String> linesPlainLower = new java.util.ArrayList<>();
    private LogAdapter adapter;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchRunnable;
    private int iconGrayEnabled;
    private int matchColor;
    private int currentMatchColor;
    private static class MatchPos { int line; int start; int length; MatchPos(int l,int s,int len){line=l;start=s;length=len;} }
    private final java.util.List<MatchPos> allMatches = new java.util.ArrayList<>();
    private int currentMatchLine = -1; private int currentMatchStart = -1; private int currentMatchLen = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shareLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {});
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        TextView title = view.findViewById(R.id.picker_title);
        if (title != null) { title.setText(R.string.debug_log_tools_title); }
        TextView helper = view.findViewById(R.id.picker_helper);
        if (helper != null) { helper.setText(R.string.debug_log_tools_helper); helper.setVisibility(View.VISIBLE); }
        LinearLayout list = view.findViewById(R.id.picker_list_container);
        if (list == null) return;
        list.removeAllViews();

        iconGrayEnabled = getResources().getColor(android.R.color.darker_gray);
        matchColor = 0x66FFFF00;
        currentMatchColor = resolveAccentColor();

        View preview = buildPreviewArea();
        list.addView(preview);

        LinearLayout mainCard = view.findViewById(R.id.picker_card_container);
        if (mainCard != null) {
            ViewGroup scrollContent = (ViewGroup) mainCard.getParent();
            if (scrollContent != null) {
                LinearLayout optionsCard = new LinearLayout(requireContext());
                optionsCard.setOrientation(LinearLayout.VERTICAL);
                optionsCard.setBackgroundResource(R.drawable.settings_group_card_bg);
                optionsCard.setPadding(dp(12), dp(4), dp(12), dp(4));

                optionsCard.addView(buildToggleRow());
                optionsCard.addView(makeDivider());

                LinearLayout shareRow = buildActionRow(android.R.drawable.ic_menu_share,
                        getString(R.string.debug_log_share_title),
                        getString(R.string.debug_log_share_subtitle));
                shareRow.setOnClickListener(v -> onShare());
                optionsCard.addView(shareRow);
                optionsCard.addView(makeDivider());

                LinearLayout deleteRow = buildActionRow(R.drawable.ic_delete,
                        getString(R.string.debug_log_delete_title),
                        getString(R.string.debug_log_delete_subtitle));
                deleteRow.setOnClickListener(v -> onDeleteWithConfirm());
                optionsCard.addView(deleteRow);
                optionsCard.addView(makeDivider());

                LinearLayout openRow = buildActionRow(R.drawable.ic_info,
                        getString(R.string.debug_log_open_title),
                        getString(R.string.debug_log_open_subtitle));
                openRow.setOnClickListener(v -> onOpenExternal());
                optionsCard.addView(openRow);

                int mainIndex = scrollContent.indexOfChild(mainCard);
                scrollContent.addView(optionsCard, mainIndex + 1);
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) optionsCard.getLayoutParams();
                lp.topMargin = dp(12);
                optionsCard.setLayoutParams(lp);

                boolean hasContent = hasLogContent();
                setRowEnabled(shareRow, hasContent);
                setRowEnabled(openRow, hasContent);
                setRowEnabled(deleteRow, hasContent);
            }
        }
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // -------------- Fix Start for this method(buildPreviewArea)-----------
    private View buildPreviewArea() {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
    // -------------- Fix Start for padding (buildPreviewArea)-----------
    // Apply same gutters as Settings rows: 14dp start / 12dp end, with small vertical padding
    // -------------- Fix Start for this method(buildPreviewArea padding)-----------
    container.setPadding(dp(14), dp(6), dp(12), dp(6));
    // -------------- Fix Ended for this method(buildPreviewArea padding)-----------
    // -------------- Fix Ended for padding (buildPreviewArea)-----------

        LinearLayout searchRow = new LinearLayout(requireContext());
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    // Add bottom spacing so search and list don't stick together
    searchRow.setPadding(0, 0, 0, dp(6));
        searchEdit = new EditText(requireContext());
        searchEdit.setHint(R.string.debug_log_search_hint);
        searchEdit.setBackgroundResource(R.drawable.prefs_input_bg);
        int pad = dp(10);
        searchEdit.setPadding(pad, pad, pad, pad);
        searchEdit.setTextColor(getResources().getColor(android.R.color.white));
        searchEdit.setHintTextColor(0xFF777777);
        searchEdit.setSingleLine(true);
        searchEdit.setMaxLines(1);
        searchEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchRow.addView(searchEdit, searchLp);
        View btnUp = makeNavButton(true);
        View btnDown = makeNavButton(false);
        searchRow.addView(btnUp, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        searchRow.addView(btnDown, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(searchRow);

        HorizontalScrollView hsv = new HorizontalScrollView(requireContext());
        hsv.setHorizontalScrollBarEnabled(true);
        previewRv = new RecyclerView(requireContext());
        previewRv.setLayoutManager(new LinearLayoutManager(requireContext()));
        previewRv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        adapter = new LogAdapter();
        previewRv.setAdapter(adapter);
        int p = dp(8);
        previewRv.setPadding(p,p,p,p);
        hsv.addView(previewRv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(hsv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        renderBaseAsync();

        searchEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
                String query = s == null ? null : s.toString();
                searchRunnable = () -> scheduleSearchApply(query, 0, true);
                searchHandler.postDelayed(searchRunnable, 120);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        return container;
    }
    // -------------- Fix Ended for this method(buildPreviewArea)-----------

    // -------------- Fix Start for this method(onShare)-----------
    private void onShare() {
        try {
            Uri share = Log.getSharableLogUri(requireContext());
            if (share == null) {
                Toast.makeText(requireContext(), R.string.debug_log_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("*/*");
            send.putExtra(Intent.EXTRA_STREAM, share);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, getString(R.string.debug_log_share_title));
            shareLauncher.launch(chooser);
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.debug_log_share_fail) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    // -------------- Fix Ended for this method(onShare)-----------

    // -------------- Fix Start for this method(onDeleteWithConfirm)-----------
    private void onDeleteWithConfirm() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.debug_log_delete_title)
                .setMessage(R.string.debug_log_delete_subtitle)
                .setPositiveButton(R.string.universal_delete, (d, which) -> {
                    boolean ok = Log.deleteLog(requireContext());
                    if (ok) {
                        Toast.makeText(requireContext(), R.string.universal_deleted, Toast.LENGTH_SHORT).show();
                        dismiss();
                    } else {
                        Toast.makeText(requireContext(), R.string.universal_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.universal_cancel, (d, which) -> d.dismiss())
                .show();
    }
    // -------------- Fix Ended for this method(onDeleteWithConfirm)-----------

    // -------------- Fix Start for this method(onOpenExternal)-----------
    private void onOpenExternal() {
        try {
            Uri uri = Log.getSharableLogUri(requireContext());
            if (uri == null) {
                Toast.makeText(requireContext(), R.string.debug_log_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "text/html");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.debug_log_open_fail) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    // -------------- Fix Ended for this method(onOpenExternal)-----------

    // -------------- Fix Start for this method(makeDivider)-----------
    private View makeDivider(){
        View d = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        // Match SettingsDivider insets and color
        lp.setMargins(dp(14), dp(2), dp(12), dp(2));
        d.setLayoutParams(lp);
    d.setBackgroundColor(0xFF262626);
        return d;
    }
    // -------------- Fix Ended for this method(makeDivider)-----------

    // -------------- Fix Start for this method(buildActionRow)-----------
    private LinearLayout buildActionRow(int iconRes, String title, String subtitle){
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        // -------------- Fix Start for this method(buildActionRow)-----------
        // 14dp start / 12dp end gutters, light vertical padding
        row.setPadding(dp(14), dp(6), dp(12), dp(6));
        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.setMarginEnd(dp(16));
        icon.setLayoutParams(iconLp);
    icon.setImageResource(iconRes);
    // Match tinting approach used across settings/readme screens
    icon.setImageTintList(ColorStateList.valueOf(iconGrayEnabled));
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
        text.addView(primary); text.addView(secondary); row.addView(text);
        android.widget.ImageView arrow = new android.widget.ImageView(requireContext());
    LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(14), dp(14));
    arrowLp.setMarginStart(dp(12)); // value-to-arrow breathing room
    arrow.setLayoutParams(arrowLp);
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(arrow);
    // -------------- Fix Ended for this method(buildActionRow)-----------
    return row;
    }
    // -------------- Fix Ended for this method(buildActionRow)-----------

    // -------------- Fix Start for this method(buildToggleRow)-----------
    private LinearLayout buildToggleRow(){
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
    // -------------- Fix Start for this method(buildToggleRow)-----------
    row.setPadding(dp(14), dp(6), dp(12), dp(6));
        TextView label = new TextView(requireContext());
        label.setText(getString(R.string.setting_debug_title));
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        label.setTextColor(getResources().getColor(R.color.colorHeading));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, lp);
        android.widget.Switch sw = new android.widget.Switch(requireContext());
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
        sw.setChecked(prefs.isDebugLoggingEnabled());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.sharedPreferences.edit().putBoolean(Constants.PREF_DEBUG_DATA, isChecked).apply();
            Log.setDebugEnabled(isChecked);
        });
    row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    // -------------- Fix Ended for this method(buildToggleRow)-----------
    return row;
    }
    // -------------- Fix Ended for this method(buildToggleRow)-----------

    private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void setRowEnabled(View row, boolean enabled){
        row.setEnabled(enabled);
        if (row instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) row;
            if (ll.getChildCount() > 0 && ll.getChildAt(0) instanceof android.widget.ImageView) {
                android.widget.ImageView icon = (android.widget.ImageView) ll.getChildAt(0);
                icon.setImageTintList(ColorStateList.valueOf(iconGrayEnabled));
                icon.setAlpha(enabled ? 1f : 0.45f);
            }
            if (ll.getChildCount() > 1 && ll.getChildAt(1) instanceof LinearLayout) {
                LinearLayout text = (LinearLayout) ll.getChildAt(1);
                for (int i = 0; i < text.getChildCount(); i++) {
                    if (text.getChildAt(i) instanceof TextView) {
                        ((TextView) text.getChildAt(i)).setAlpha(enabled ? 1f : 0.45f);
                    }
                }
            }
        }
    }

    private boolean hasLogContent(){
        String html = Log.readLogAsHtml(requireContext());
        if (html == null) return false;
        String normalized = normalizeHtml(html);
        String plain = normalized.replaceAll("<br ?/?>", "\n").replaceAll("<[^>]*>", "").trim();
        return !plain.isEmpty();
    }

    private String normalizeHtml(String html){
        if (html == null) return "";
        String s = html.replace("</br>", "<br>")
                       .replace("<br/>", "<br>")
                       .replace("<br />", "<br>")
                       .replace("<BR>", "<br>");
        s = s.replaceAll("color=\"([0-9a-fA-F]{6})\"", "color=\"#$1\"");
        return s;
    }

    private void renderBaseAsync(){
        new Thread(() -> {
            try {
                String html = Log.readLogAsHtml(requireContext());
                java.util.List<String> newLinesHtml = new java.util.ArrayList<>();
                java.util.List<String> newLinesPlainLower = new java.util.ArrayList<>();
                if (!TextUtils.isEmpty(html)) {
                    String normalized = normalizeHtml(html);
                    String[] lines = normalized.split("<br>\\n?|\\n");
                    for (String l : lines) {
                        if (l == null) continue;
                        newLinesHtml.add(l);
                        String plain = l.replaceAll("<[^>]*>", "");
                        newLinesPlainLower.add(plain.toLowerCase(java.util.Locale.ROOT));
                    }
                }
                if (newLinesHtml.size() > MAX_LINES) {
                    int start = newLinesHtml.size() - MAX_LINES;
                    newLinesHtml = new java.util.ArrayList<>(newLinesHtml.subList(start, newLinesHtml.size()));
                    newLinesPlainLower = new java.util.ArrayList<>(newLinesPlainLower.subList(start, newLinesPlainLower.size()));
                }
                linesHtml = newLinesHtml; linesPlainLower = newLinesPlainLower;
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        if (searchEdit != null) {
                            String q = searchEdit.getText() == null ? null : searchEdit.getText().toString();
                            scheduleSearchApply(q, 0, false);
                        }
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void scheduleSearchApply(@Nullable String query, int delayMs){
        scheduleSearchApply(query, delayMs, false);
    }
    private void scheduleSearchApply(@Nullable String query, int delayMs, boolean autoScrollFirst){
        if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
        final String q = query == null ? "" : query.trim();
        searchRunnable = () -> new Thread(() -> {
            allMatches.clear();
            currentMatchIndex = -1; currentMatchLine = -1; currentMatchStart = -1; currentMatchLen = 0;
            if (!TextUtils.isEmpty(q) && !linesPlainLower.isEmpty()){
                String ql = q.toLowerCase(java.util.Locale.ROOT);
                for (int i=0;i<linesPlainLower.size();i++){
                    String lower = linesPlainLower.get(i);
                    if (lower == null) continue;
                    int idx = 0;
                    while (true){
                        idx = lower.indexOf(ql, idx);
                        if (idx < 0) break;
                        allMatches.add(new MatchPos(i, idx, q.length()));
                        idx += q.length();
                    }
                }
                if (!allMatches.isEmpty()){
                    currentMatchIndex = 0;
                    MatchPos m = allMatches.get(0);
                    currentMatchLine = m.line; currentMatchStart = m.start; currentMatchLen = m.length;
                }
            }
            if (isAdded()) requireActivity().runOnUiThread(() -> {
                adapter.setQuery(q);
                if (autoScrollFirst && currentMatchIndex >= 0) {
                    try {
                        ((LinearLayoutManager)previewRv.getLayoutManager()).scrollToPositionWithOffset(currentMatchLine, dp(24));
                    } catch (Exception ignored) {}
                }
            });
        }).start();
        searchHandler.postDelayed(searchRunnable, Math.max(0, delayMs));
    }

    @Override public int getTheme(){ return R.style.CustomBottomSheetDialogTheme; }
    @Override public android.app.Dialog onCreateDialog(Bundle savedInstanceState){ android.app.Dialog dialog = super.onCreateDialog(savedInstanceState); dialog.setOnShowListener(d->{ View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog).findViewById(com.google.android.material.R.id.design_bottom_sheet); if(bottomSheet!=null){ bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg); } }); return dialog; }

    private View makeNavButton(boolean up){
        android.widget.ImageButton b = new android.widget.ImageButton(requireContext());
        b.setBackgroundResource(R.drawable.prefs_input_bg);
        b.setImageResource(up ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float);
        b.setColorFilter(resolveAccentColor());
        b.setOnClickListener(v -> navigateToMatch(up ? -1 : 1));
        return b;
    }

    private void navigateToMatch(int delta){
        if (allMatches.isEmpty()) return;
        if (currentMatchIndex < 0) currentMatchIndex = 0;
        currentMatchIndex = (currentMatchIndex + delta + allMatches.size()) % allMatches.size();
        MatchPos m = allMatches.get(currentMatchIndex);
        currentMatchLine = m.line; currentMatchStart = m.start; currentMatchLen = m.length;
        previewRv.post(() -> {
            try {
                ((LinearLayoutManager)previewRv.getLayoutManager()).scrollToPositionWithOffset(currentMatchLine, dp(24));
                adapter.notifyDataSetChanged();
            } catch (Exception ignored) {}
        });
    }

    private int resolveAccentColor(){
        android.util.TypedValue tv = new android.util.TypedValue();
        android.content.res.Resources.Theme theme = requireContext().getTheme();
        int color = 0xFF33B5E5;
        if (theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, tv, true)) {
            color = tv.data;
        } else if (theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, tv, true)) {
            color = tv.data;
        }
        return color;
    }

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH>{
        private final int grey = getResources().getColor(android.R.color.darker_gray);
        private String query = "";
        void setQuery(String q){ this.query = q==null?"":q; notifyDataSetChanged(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType){
            TextView tv = new TextView(parent.getContext());
            tv.setTextColor(getResources().getColor(android.R.color.white));
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position){
            if (position<0 || position>=linesHtml.size()) { holder.tv.setText(""); return; }
            String lineHtml = linesHtml.get(position);
            int digits = (int)Math.floor(Math.log10(Math.max(1, linesHtml.size()))) + 1;
            String prefix = String.format(java.util.Locale.US, "%"+digits+"d | ", position+1);
            SpannableStringBuilder out = new SpannableStringBuilder();
            int start = out.length();
            out.append(prefix);
            out.setSpan(new ForegroundColorSpan(grey), start, start + prefix.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            CharSequence spanned = HtmlCompat.fromHtml(lineHtml, HtmlCompat.FROM_HTML_MODE_LEGACY);
            int contentStart = out.length();
            out.append(spanned);
            if (!TextUtils.isEmpty(query)){
                String lower = linesPlainLower.size()>position? linesPlainLower.get(position): spanned.toString().toLowerCase(java.util.Locale.ROOT);
                String ql = query.toLowerCase(java.util.Locale.ROOT);
                int idx = 0;
                while (true){
                    idx = lower.indexOf(ql, idx);
                    if (idx < 0) break;
                    out.setSpan(new BackgroundColorSpan(matchColor), contentStart + idx, contentStart + idx + ql.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (position == currentMatchLine && idx == currentMatchStart){
                        out.setSpan(new BackgroundColorSpan(currentMatchColor), contentStart + idx, contentStart + idx + ql.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    idx += ql.length();
                }
            }
            holder.tv.setText(out);
        }
        @Override public int getItemCount(){ return linesHtml==null?0:linesHtml.size(); }
        class VH extends RecyclerView.ViewHolder{ TextView tv; VH(@NonNull View itemView){ super(itemView); tv=(TextView)itemView; } }
    }
}
 
