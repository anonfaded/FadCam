package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.R;
import android.widget.TextView;
import android.view.ViewGroup;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import android.widget.LinearLayout;
import android.widget.ImageView;

/**
 * ReadmeBottomSheetFragment
 * Unified bottom sheet replacement for legacy README Material dialog.
 */
public class ReadmeBottomSheetFragment extends BottomSheetDialogFragment {

    public static ReadmeBottomSheetFragment newInstance(){
        return new ReadmeBottomSheetFragment();
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Reuse unified picker layout for consistent styling
        return inflater.inflate(R.layout.picker_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
    // 1. Set title text (retain standard unified spacing; no compact modifications)
    TextView title = view.findViewById(R.id.picker_title);
    if(title!=null){ title.setText(R.string.note_from_developer_title); }
    // 2. Apply gradient (standard)
    View root = view.findViewById(R.id.picker_root);
    // Match unified picker background
    if(root!=null){ root.setBackgroundColor(android.graphics.Color.TRANSPARENT); }
        // 3. Inject README content into existing card container (listContainer is inside a rounded card)
        ViewGroup listContainer = view.findViewById(R.id.picker_list_container);
        if(listContainer!=null){
            listContainer.removeAllViews();
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_readme, listContainer, true);
        }
        // 4. Hide helper if present
        View helper = view.findViewById(R.id.picker_helper);
        if(helper!=null){ helper.setVisibility(View.GONE); }
        // 5. Add separate links card OUTSIDE the main content card (after picker_card_container)
        LinearLayout mainCard = view.findViewById(R.id.picker_card_container);
        if(mainCard!=null){
            ViewGroup scrollContent = (ViewGroup) mainCard.getParent(); // parent linear inside scroll
            if(scrollContent!=null){
                // Construct links card (no extra heading for uniform minimal look)
                LinearLayout linksCard = new LinearLayout(requireContext());
                linksCard.setId(View.generateViewId());
                linksCard.setOrientation(LinearLayout.VERTICAL);
                linksCard.setBackgroundResource(R.drawable.settings_group_card_bg);
                int padH = dp(12); // mimic existing card internal horizontal padding
                linksCard.setPadding(padH, dp(4), padH, dp(4));

                // Website row (requested to be first)
                LinearLayout websiteRow = buildActionRow(R.drawable.ic_website, getString(R.string.website_title), getString(R.string.readme_website_subtitle));
                websiteRow.setOnClickListener(v -> openUrl("https://fadcam.faded.dev"));
                linksCard.addView(websiteRow);

                // Divider 1
                View divider1 = new View(requireContext());
                LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                divLp1.setMargins(0, dp(2), 0, dp(2));
                divider1.setLayoutParams(divLp1);
                divider1.setBackgroundColor(0x33FFFFFF);
                linksCard.addView(divider1);

                // GitHub row (second)
                LinearLayout githubRow = buildActionRow(R.drawable.ic_github, getString(R.string.button_readme_github), getString(R.string.readme_github_subtitle));
                githubRow.setOnClickListener(v -> openUrl("https://github.com/anonfaded/FadCam"));
                linksCard.addView(githubRow);

                // Divider 2
                View divider2 = new View(requireContext());
                LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                divLp2.setMargins(0, dp(2), 0, dp(2));
                divider2.setLayoutParams(divLp2);
                divider2.setBackgroundColor(0x33FFFFFF);
                linksCard.addView(divider2);

                // Discord row (third)
                LinearLayout discordRow = buildActionRow(R.drawable.ic_discord, getString(R.string.discord_title), getString(R.string.readme_discord_subtitle));
                discordRow.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));
                linksCard.addView(discordRow);
                // Insert after mainCard
                int mainIndex = scrollContent.indexOfChild(mainCard);
                scrollContent.addView(linksCard, mainIndex+1);
                // Add small top margin for separation
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) linksCard.getLayoutParams();
                lp.topMargin = dp(12);
                linksCard.setLayoutParams(lp);
            }
        }
        // 6. Handle close button
        ImageView closeButton = view.findViewById(R.id.picker_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }
        // 7. Disable dragging outside content causing accidental dismiss? Keep default for now.
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    /** Build a settings-style action row with leading icon, label + helper, trailing arrow. */
    private LinearLayout buildActionRow(int iconRes, String label, String helper){
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
    row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        int padStart = dp(16), padEnd = dp(16);
    row.setPadding(padStart, dp(6), padEnd, dp(6));
        ImageView icon = new ImageView(requireContext());
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.setMarginEnd(dp(16));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(icon);
        LinearLayout textContainer = new LinearLayout(requireContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textContainer.setLayoutParams(textLp);
        TextView primary = new TextView(requireContext());
        primary.setText(label);
        // Use theme attribute for heading color
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(R.attr.colorHeading, typedValue, true);
        primary.setTextColor(typedValue.data);
        primary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        primary.setTypeface(primary.getTypeface(), android.graphics.Typeface.BOLD);
        TextView secondary = new TextView(requireContext());
        secondary.setText(helper);
        secondary.setTextColor(getResources().getColor(android.R.color.darker_gray));
        secondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        textContainer.addView(primary);
        textContainer.addView(secondary);
        row.addView(textContainer);
        ImageView arrow = new ImageView(requireContext());
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(14)));
        arrow.setImageResource(R.drawable.ic_arrow_right);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(android.R.color.darker_gray)));
        row.addView(arrow);
        return row;
    }

    private int dp(int v){
        float d = getResources().getDisplayMetrics().density;
        return (int)(v * d + 0.5f);
    }

    private void openUrl(String url){
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(intent);
        } catch (Exception ignored) { }
    }

    @Override
    public int getTheme() { return R.style.CustomBottomSheetDialogTheme; }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if(bottomSheet!=null){
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic); // match other unified pickers (theme-dynamic gradient)
            }
        });
        return dialog;
    }
}
