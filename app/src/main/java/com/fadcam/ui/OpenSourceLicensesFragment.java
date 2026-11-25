package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.util.LicenseCatalog;

import java.util.List;
import java.util.Map;

/**
 * Lists every third-party library shipped in this build, grouped by license.
 *
 * <p>Data is generated per variant at build time (see
 * {@code app/build.gradle.kts}); nothing is fetched at runtime. Each group expands
 * to the license text bundled under {@code assets/licenses/}.
 */
public class OpenSourceLicensesFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_open_source_licenses, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> {
                if (getActivity() != null) {
                    OverlayNavUtil.dismiss(requireActivity());
                }
            });
        }

        LinearLayout list = view.findViewById(R.id.licenses_list);
        TextView summary = view.findViewById(R.id.licenses_summary);
        if (list == null) {
            return;
        }

        Map<String, List<String[]>> grouped = LicenseCatalog.load(requireContext());
        if (grouped.isEmpty()) {
            // A build without generated data is a packaging problem, not user error;
            // say so plainly instead of showing an empty screen.
            if (summary != null) {
                summary.setText(R.string.open_source_licenses_unavailable);
            }
            return;
        }

        int total = 0;
        for (List<String[]> libs : grouped.values()) {
            total += libs.size();
        }
        if (summary != null) {
            summary.setText(getString(R.string.open_source_licenses_summary, total, grouped.size()));
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Map.Entry<String, List<String[]>> entry : grouped.entrySet()) {
            addGroup(inflater, list, entry.getKey(), entry.getValue());
        }
    }

    private void addGroup(LayoutInflater inflater, LinearLayout parent,
                          String license, List<String[]> libraries) {
        View header = inflater.inflate(R.layout.item_license_group_header, parent, false);
        TextView name = header.findViewById(R.id.license_name);
        TextView count = header.findViewById(R.id.license_count);
        name.setText(license);
        count.setText(getResources().getQuantityString(
                R.plurals.open_source_licenses_library_count, libraries.size(), libraries.size()));

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);

        // Library names are plain text: a 142-row clickable list that only leads to a
        // URL would be noise, and every dependency already appears by name.
        StringBuilder sb = new StringBuilder();
        for (String[] lib : libraries) {
            sb.append("• ").append(lib[0]);
            if (lib[1] != null && !lib[1].isEmpty()) {
                sb.append('\n').append("   ").append(lib[1]);
            }
            sb.append('\n');
        }
        TextView names = new TextView(requireContext());
        names.setText(sb.toString().trim());
        names.setTextSize(13f);
        names.setLineSpacing(0f, 1.15f);
        names.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(names);

        String asset = LicenseCatalog.assetFor(license);
        if (asset != null) {
            View licenseRow = inflater.inflate(R.layout.item_license_text_row, body, false);
            TextView licenseText = licenseRow.findViewById(R.id.license_text);
            licenseText.setText(LicenseCatalog.readAsset(requireContext(), asset));
            body.addView(licenseRow);
        } else {
            // Terms that cannot be redistributed still need somewhere to point, or the
            // group expands to nothing at all.
            String url = LicenseCatalog.termsUrl(LicenseCatalog.load(requireContext()), license);
            View note = inflater.inflate(R.layout.item_license_link_row, body, false);
            TextView message = note.findViewById(R.id.license_link_message);
            TextView link = note.findViewById(R.id.license_link);
            if (url.isEmpty()) {
                message.setText(R.string.open_source_licenses_no_text);
                link.setVisibility(View.GONE);
            } else {
                message.setText(R.string.open_source_licenses_external_terms);
                link.setText(url);
                link.setOnClickListener(v -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)));
                    } catch (Exception e) {
                        com.fadcam.FLog.w("OpenSourceLicenses", "Could not open " + url, e);
                    }
                });
            }
            body.addView(note);
        }

        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            View chevron = header.findViewById(R.id.license_chevron);
            if (chevron != null) {
                chevron.animate().rotation(show ? 180f : 0f).setDuration(150L).start();
            }
        });

        // Header first so the expanded notice renders beneath its own heading.
        parent.addView(header);
        parent.addView(body);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
