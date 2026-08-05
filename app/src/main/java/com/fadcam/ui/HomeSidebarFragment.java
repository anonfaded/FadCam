package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.fadcam.ui.AvatarToggleView;
import androidx.fragment.app.DialogFragment;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.ui.miniapps.TorchToolFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.google.android.material.sidesheet.SideSheetDialog;

/**
 * HomeSidebarFragment
 * Side overlay with settings-style grouped rows for Home options (tips, etc).
 * Based on RecordsSidebarFragment pattern.
 */
public class HomeSidebarFragment extends DialogFragment {

    private String resultKey = "home_sidebar_result";

    public static HomeSidebarFragment newInstance() {
        return new HomeSidebarFragment();
    }

    public void setResultKey(String key) {
        this.resultKey = key;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Create a Material SideSheetDialog to host the sidebar content
        SideSheetDialog dialog = new SideSheetDialog(requireContext());

        // Make window background fully transparent so our gradient shape shows without gray corners
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT
                )
            );
            // Remove decor view padding/insets that can cause gray strips
            android.view.View decor = window.getDecorView();
            if (decor instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) decor).setPadding(0, 0, 0, 0);
                decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
        
        // Use setOnShowListener to clear any Material Components backgrounds after dialog is shown
        dialog.setOnShowListener(d -> {
            android.view.View container = dialog.findViewById(android.R.id.content);
            if (container != null && container instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) container;
                // Recursively clear backgrounds from all child views that Material added
                clearMaterialBackgrounds(group);
            }
        });
        
        return dialog;
    }
    
    private void clearMaterialBackgrounds(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            // Skip the actual content (ScrollView with our gradient drawable)
            if (child.getId() != R.id.home_sidebar_root_scroll) {
                child.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                if (child instanceof android.view.ViewGroup) {
                    clearMaterialBackgrounds((android.view.ViewGroup) child);
                }
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        // Inflate the side sheet content (modal side sheet provided by Material components)
        return inflater.inflate(
            R.layout.fragment_home_sidebar,
            container,
            false
        );
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        // Handle close button
        ImageView closeButton = view.findViewById(R.id.home_sidebar_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        // Profiles row
        View profilesRow = view.findViewById(R.id.row_profiles);
        if (profilesRow != null) {
            profilesRow.setOnClickListener(v -> {
                showProfilesComingSoon();
                dismiss();
            });
        }

        // What's New row
        View whatsNewRow = view.findViewById(R.id.row_whats_new);
        if (whatsNewRow != null) {
            // Show badge if feature not yet seen
            TextView whatsNewBadge = view.findViewById(R.id.badge_whats_new);
            if (whatsNewBadge != null) {
                boolean showBadge = com.fadcam.ui.utils.NewFeatureManager.shouldShowBadge(requireContext(), "whats_new");
                whatsNewBadge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
            }
            
            whatsNewRow.setOnClickListener(v -> {
                openWhatsNew();
                // Mark badge as seen when clicked
                com.fadcam.ui.utils.NewFeatureManager.markFeatureAsSeen(requireContext(), "whats_new");
                dismiss();
            });
        }

        // Tips row
        View tipsRow = view.findViewById(R.id.row_tips);
        if (tipsRow != null) {
            tipsRow.setOnClickListener(v -> {
                openTipsPicker();
                dismiss();
            });
        }

        // Preview control row - bind to existing layout elements and use centralized strings/prefs.
        try {
            final SharedPreferencesManager sp =
                SharedPreferencesManager.getInstance(requireContext());

            View previewRow = view.findViewById(R.id.row_preview_toggle);
            if (previewRow != null) {
                TextView tvTitle = previewRow.findViewById(R.id.tv_preview_toggle_title);
                TextView tvSub   = previewRow.findViewById(R.id.tv_preview_toggle_sub);
                AvatarToggleView swPreview = previewRow.findViewById(R.id.iv_preview_toggle);

                if (tvTitle != null) tvTitle.setText(R.string.ui_preview_area);

                boolean current = Boolean.TRUE.equals(sp.isPreviewEnabled());
                if (tvSub != null) {
                    tvSub.setText(current
                        ? getString(R.string.setting_enabled_msg)
                        : getString(R.string.setting_disabled_msg));
                }
                if (swPreview != null) {
                    swPreview.setChecked(current);
                    swPreview.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        sp.setPreviewEnabled(isChecked);
                        if (tvSub != null) {
                            tvSub.setText(isChecked
                                ? getString(R.string.setting_enabled_msg)
                                : getString(R.string.setting_disabled_msg));
                        }
                        try {
                            Bundle b = new Bundle();
                            b.putBoolean("preview_enabled", isChecked);
                            getParentFragmentManager().setFragmentResult(resultKey, b);
                        } catch (Exception ignored) {}
                    });
                    previewRow.setOnClickListener(v -> swPreview.performClick());
                }
            }

            View quickActionsRow = view.findViewById(R.id.row_preview_quick_actions_toggle);
            if (quickActionsRow != null) {
                TextView tvSub = quickActionsRow.findViewById(R.id.tv_preview_quick_actions_sub);
                AvatarToggleView sw = quickActionsRow.findViewById(R.id.switch_preview_quick_actions_toggle);
                if (sw != null) {
                    boolean current = sp.isPreviewQuickActionsAlwaysVisible();
                    sw.setChecked(current);
                    if (tvSub != null) {
                        tvSub.setText(current
                            ? getString(R.string.preview_quick_actions_state_always)
                            : getString(R.string.preview_quick_actions_state_recording_only));
                    }
                    sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        sp.setPreviewQuickActionsAlwaysVisible(isChecked);
                        if (tvSub != null) {
                            tvSub.setText(isChecked
                                ? getString(R.string.preview_quick_actions_state_always)
                                : getString(R.string.preview_quick_actions_state_recording_only));
                        }
                        try {
                            Bundle b = new Bundle();
                            b.putBoolean("preview_quick_actions_always_visible", isChecked);
                            getParentFragmentManager().setFragmentResult(resultKey, b);
                        } catch (Exception ignored) {}
                    });
                    quickActionsRow.setOnClickListener(v -> sw.performClick());
                }
            }

            View gridRow = view.findViewById(R.id.row_grid_lines);
            if (gridRow != null) {
                TextView tvGridSub = gridRow.findViewById(R.id.tv_grid_lines_sub);
                AvatarToggleView swGrid = gridRow.findViewById(R.id.switch_grid_lines);
                if (swGrid != null) {
                    boolean gridEnabled = sp.isGridLinesEnabled();
                    swGrid.setChecked(gridEnabled);
                    if (tvGridSub != null) {
                        tvGridSub.setText(gridEnabled
                            ? getString(R.string.setting_enabled_msg)
                            : getString(R.string.setting_disabled_msg));
                    }
                    swGrid.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        sp.setGridLinesEnabled(isChecked);
                        if (tvGridSub != null) {
                            tvGridSub.setText(isChecked
                                ? getString(R.string.setting_enabled_msg)
                                : getString(R.string.setting_disabled_msg));
                        }
                        try {
                            Bundle b = new Bundle();
                            b.putBoolean("grid_lines_enabled", isChecked);
                            getParentFragmentManager().setFragmentResult(resultKey, b);
                        } catch (Exception ignored) {}
                    });
                    gridRow.setOnClickListener(v -> swGrid.performClick());
                }
            }
        } catch (Exception e) {
            FLog.w(
                "HomeSidebar",
                "Failed to bind preview control",
                e
            );
        }

        // Discord branding row
        View discordRow = view.findViewById(R.id.row_discord_branding);
        if (discordRow != null) {
            discordRow.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://discord.gg/kvAZvdkuuN"));
                    startActivity(intent);
                } catch (Exception e) {
                    FLog.w("HomeSidebar", "Failed to open Discord link", e);
                }
            });
        }

        // Mini Apps Section
        setupMiniAppRows(view);

        // Mini Apps Info Button
        View btnMiniAppsInfo = view.findViewById(R.id.btn_mini_apps_info);
        if (btnMiniAppsInfo != null) {
            btnMiniAppsInfo.setOnClickListener(v -> {
                MiniAppsInfoBottomSheet infoBS = MiniAppsInfoBottomSheet.newInstance();
                infoBS.show(getParentFragmentManager(), "mini_apps_info");
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // AvatarToggleView handles its own animation cleanup via onDetachedFromWindow().
    }

    private void setupMiniAppRows(View view) {
        // Torch Mini App - open full screen tool
        View torchRow = view.findViewById(R.id.row_mini_app_torch);
        if (torchRow != null) {
            torchRow.setOnClickListener(v -> {
                try {
                    TorchToolFragment torchTool = TorchToolFragment.newInstance();
                    OverlayNavUtil.show(requireActivity(), torchTool, "torch_tool");
                    dismiss();
                } catch (Exception e) {
                    FLog.w("HomeSidebar", "Failed to open torch tool", e);
                }
            });
        }

        // Compass Mini App - Coming Soon
        View compassRow = view.findViewById(R.id.row_mini_app_compass);
        if (compassRow != null) {
            compassRow.setOnClickListener(v -> {
                showMiniAppComingSoon(this, "compass");
            });
        }

        // Sound Meter Mini App - Coming Soon
        View soundMeterRow = view.findViewById(R.id.row_mini_app_sound_meter);
        if (soundMeterRow != null) {
            soundMeterRow.setOnClickListener(v -> {
                showMiniAppComingSoon(this, "sound_meter");
            });
        }

        // Sensor Dashboard Mini App - Coming Soon
        View sensorDashboardRow = view.findViewById(R.id.row_mini_app_sensor_dashboard);
        if (sensorDashboardRow != null) {
            sensorDashboardRow.setOnClickListener(v -> {
                showMiniAppComingSoon(this, "sensor_dashboard");
            });
        }
        // Speedometer Mini App - Coming Soon
        View speedometerRow = view.findViewById(R.id.row_mini_app_speedometer);
        if (speedometerRow != null) speedometerRow.setOnClickListener(v -> showMiniAppComingSoon(this, "speedometer"));
        // Clinometer Mini App - Coming Soon
        View clinometerRow = view.findViewById(R.id.row_mini_app_clinometer);
        if (clinometerRow != null) clinometerRow.setOnClickListener(v -> showMiniAppComingSoon(this, "clinometer"));
        // QR Scanner Mini App - ready to use
        View qrScannerRow = view.findViewById(R.id.row_mini_app_qr_scanner);
        if (qrScannerRow != null) qrScannerRow.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), com.fadcam.ui.miniapps.QRScannerActivity.class);
            startActivity(intent);
            dismiss();
        });
        // Pedometer Mini App - Coming Soon
        View pedometerRow = view.findViewById(R.id.row_mini_app_pedometer);
        if (pedometerRow != null) pedometerRow.setOnClickListener(v -> showMiniAppComingSoon(this, "pedometer"));
        // Metal Detector Mini App - Coming Soon
        View metalDetectorRow = view.findViewById(R.id.row_mini_app_metal_detector);
        if (metalDetectorRow != null) metalDetectorRow.setOnClickListener(v -> showMiniAppComingSoon(this, "metal_detector"));
        // Parking Marker Mini App - Coming Soon
        View parkingMarkerRow = view.findViewById(R.id.row_mini_app_parking_marker);
        if (parkingMarkerRow != null) parkingMarkerRow.setOnClickListener(v -> showMiniAppComingSoon(this, "parking_marker"));
        View qrGeneratorRow = view.findViewById(R.id.row_mini_app_qr_generator);
        if (qrGeneratorRow != null) qrGeneratorRow.setOnClickListener(v -> showMiniAppComingSoon(this, "qr_generator"));

        setupUpdateCards(view);
    }

    private void showProfilesComingSoon() {
        try {
            ArrayList<OptionItem> items = new ArrayList<>();
            items.add(new OptionItem("profiles",
                    "Set up multiple profiles with different configurations and switch between them instantly.\n\n"
                    + "Example use cases:\n"
                    + "• Low storage mode — reduced resolution & bitrate\n"
                    + "• High quality mode — max resolution & FPS\n"
                    + "• Night mode — optimized exposure settings\n"
                    + "• Share profiles across devices", (String) null));
            PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
                    "Profiles", items, "profiles", "profile_coming_soon",
                    "Coming in a future update", true);
            picker.show(getParentFragmentManager(), "profiles_coming_soon");
        } catch (Exception e) {
            FLog.w("HomeSidebar", "Failed to show profiles info", e);
        }
    }

    public static void showMiniAppComingSoon(Fragment fragment, String appId) {
        try {
            String title = null;
            String desc = null;
            
            switch (appId) {
                case "compass":
                    title = fragment.getString(R.string.mini_app_compass_title);
                    desc = fragment.getString(R.string.mini_app_compass_desc);
                    break;
                case "sound_meter":
                    title = fragment.getString(R.string.mini_app_sound_meter_title);
                    desc = fragment.getString(R.string.mini_app_sound_meter_desc);
                    break;
                case "sensor_dashboard":
                    title = fragment.getString(R.string.mini_app_sensor_dashboard_title);
                    desc = fragment.getString(R.string.mini_app_sensor_dashboard_desc);
                    break;
                case "speedometer":
                    title = fragment.getString(R.string.mini_app_speedometer_title);
                    desc = fragment.getString(R.string.mini_app_speedometer_desc);
                    break;
                case "clinometer":
                    title = fragment.getString(R.string.mini_app_clinometer_title);
                    desc = fragment.getString(R.string.mini_app_clinometer_desc);
                    break;
                case "qr_scanner":
                    title = fragment.getString(R.string.mini_app_qr_scanner_title);
                    desc = fragment.getString(R.string.mini_app_qr_scanner_desc);
                    break;
                case "pedometer":
                    title = fragment.getString(R.string.mini_app_pedometer_title);
                    desc = fragment.getString(R.string.mini_app_pedometer_desc);
                    break;
                case "metal_detector":
                    title = fragment.getString(R.string.mini_app_metal_detector_title);
                    desc = fragment.getString(R.string.mini_app_metal_detector_desc);
                    break;
                case "parking_marker":
                    title = fragment.getString(R.string.mini_app_parking_marker_title);
                    desc = fragment.getString(R.string.mini_app_parking_marker_desc);
                    break;
                case "qr_generator":
                    title = fragment.getString(R.string.mini_app_qr_generator_title);
                    desc = fragment.getString(R.string.mini_app_qr_generator_desc);
                    break;
            }
            
            if (title == null) return;
            
            ArrayList<OptionItem> items = new ArrayList<>();
            items.add(new OptionItem(appId, desc, (String) null));
            
            PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
                title,
                items,
                appId,
                "mini_app_" + appId,
                fragment.getString(R.string.mini_app_coming_soon_desc),
                true
            );
            picker.show(fragment.getParentFragmentManager(), "mini_app_coming_soon_" + appId);
        } catch (Exception e) {
            FLog.w("HomeSidebar", "Failed to show mini app coming soon", e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Set the sheet edge to START (left side) after the view is created
        Dialog dialog = getDialog();
        if (dialog instanceof SideSheetDialog) {
            try {
                ((SideSheetDialog) dialog).setSheetEdge(
                    android.view.Gravity.START
                );
            } catch (Exception ignored) {
                // Fallback if setSheetEdge fails
            }
        }

        // Clear any default container backgrounds from the SideSheet host views to avoid gray edges around rounded corners
        View root = getView();
        if (root != null) {
            View p = (View) root.getParent();
            int guard = 0;
            while (p != null && guard < 5) {
                // climb a few levels safely
                try {
                    if (p.getBackground() != null) {
                        p.setBackgroundColor(
                            android.graphics.Color.TRANSPARENT
                        );
                    }
                } catch (Exception ignored) {}
                if (!(p.getParent() instanceof View)) break;
                p = (View) p.getParent();
                guard++;
            }
        }
    }

    private void openWhatsNew() {
        // WhatsNewActivity uses WebView which is not supported on Wear OS — skip on watch.
        try {
            if (com.fadcam.utils.RuntimeCompat.isWatchDevice(requireContext())) return;
        } catch (Exception ignored) {}
        android.content.Intent intent = new android.content.Intent(requireContext(), com.fadcam.ui.WhatsNewActivity.class);
        startActivity(intent);
    }

    private void openTipsPicker() {
        // Use the new TipsCarouselFragment for better tips display
        TipsCarouselFragment tipsCarousel = TipsCarouselFragment.newInstance();
        tipsCarousel.show(getParentFragmentManager(), "tips_carousel");
    }

    @Override
    public int getTheme() {
        return R.style.CustomSideSheetDialogTheme;
    }

    private void setupUpdateCards(@NonNull View view) {
        android.widget.LinearLayout section = view.findViewById(R.id.update_available_section);
        if (section == null) return;
        com.fadcam.services.UpdateCheckService.UpdateCheckResult r =
                com.fadcam.services.UpdateCheckService.getLastResult();
        if (r == null || !r.hasAnyUpdate()) { section.setVisibility(View.GONE); return; }
        section.setVisibility(View.VISIBLE);

        String cur = getAppVersion();

        if (r.hasStable) setupCard(view, R.id.card_update_stable, R.id.tv_stable_version,
                cur, r.stableVersion, () -> {
                    UpdateAvailableBottomSheet.newInstance(r.stableVersion, "", r.stableUrl)
                            .show(getParentFragmentManager(), "UpdateSheet");
                    dismiss();
                });
        else hideCard(view, R.id.card_update_stable);

        if (r.hasBeta) setupCard(view, R.id.card_update_beta, R.id.tv_beta_version,
                cur, r.betaVersion, () -> {
                    BetaUpdateBottomSheet.newInstance(r.betaVersion, r.betaUrl, cur)
                            .show(getParentFragmentManager(), "BetaSheet");
                    dismiss();
                });
        else hideCard(view, R.id.card_update_beta);

        if (r.hasPro) setupCard(view, R.id.card_update_pro, R.id.tv_pro_version,
                cur, r.proVersion, () -> {
                    ProUpdateBottomSheet.newInstance(r.proVersion, r.proUrl, cur)
                            .show(getParentFragmentManager(), "ProSheet");
                    dismiss();
                });
        else hideCard(view, R.id.card_update_pro);
    }

    private void setupCard(View v, int cardId, int verId, String cur, String latest, Runnable onClick) {
        View c = v.findViewById(cardId);
        android.widget.TextView t = v.findViewById(verId);
        if (c == null || t == null) return;
        String text = "v" + cur + "  >>  v" + latest;
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int curEnd = ("v" + cur).length();
        
        // Determine colors based on card type
        int currentColor = 0xFFFF5252; // Default red for current version
        int newColor = 0xFF66BB6A;     // Default green for new version
        
        if (cardId == R.id.card_update_pro) {
            currentColor = 0xFFFFFFFF;  // White for Pro current version
            newColor = 0xFFFFD700;      // Gold for Pro new version
        }
        
        sp.setSpan(new android.text.style.ForegroundColorSpan(currentColor), 0, curEnd, 0);
        sp.setSpan(new android.text.style.ForegroundColorSpan(newColor), curEnd, text.length(), 0);
        t.setText(sp);
        c.setVisibility(View.VISIBLE);
        c.setOnClickListener(ignored -> onClick.run());
        if (c instanceof android.view.ViewGroup && ((android.view.ViewGroup)c).getChildCount() > 0) {
            com.fadcam.utils.ShimmerEffectHelper.applyShimmerEffect(((android.view.ViewGroup)c).getChildAt(0));
        }
    }

    private void hideCard(View v, int cardId) { View c = v.findViewById(cardId); if (c != null) c.setVisibility(View.GONE); }

    private String getAppVersion() {
        try { return requireActivity().getPackageManager().getPackageInfo(requireActivity().getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }
}
