package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * ShortcutsSettingsFragment
 * Full-screen settings screen listing Start/Stop/Torch shortcuts. Clicking the icon pins to home.
 * Row click uses the unified PickerBottomSheetFragment to confirm pin.
 */
public class ShortcutsSettingsFragment extends Fragment {

    private View widgetPreviewContainer;
    private Handler previewUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable previewUpdateRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_shortcuts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        View back = view.findViewById(R.id.back_button);
        if(back!=null){ back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity())); }

    TextView helper = view.findViewById(R.id.shortcuts_helper);
    if(helper!=null){ helper.setText(getString(R.string.shortcuts_helper_text)); }

    wireShortcutRow(view, R.id.cell_start, R.id.icon_start, R.drawable.start_shortcut,
                getString(R.string.start_recording),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.RecordingStartActivity"),
                "record_start");
    wireShortcutRow(view, R.id.cell_stop, R.id.icon_stop, R.drawable.stop_shortcut,
                getString(R.string.stop_recording),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.RecordingStopActivity"),
                "record_stop");
    wireShortcutRow(view, R.id.cell_torch, R.id.icon_torch, R.drawable.flashlight_shortcut,
                getString(R.string.torch_shortcut_short_label),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.TorchToggleActivity"),
                "torch_toggle");

    View preview = view.findViewById(R.id.clock_widget_preview);
    View titleRow = view.findViewById(R.id.clock_widget_title_row);
    View.OnClickListener widgetClick = v -> showClockWidgetSheet();
    if(preview!=null) preview.setOnClickListener(widgetClick);
    if(titleRow!=null) titleRow.setOnClickListener(widgetClick);
    
    // Initialize preview with current preferences
    updatePreview();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    private void wireShortcutRow(View root, int rowId, int iconId, int iconRes, String title, Intent intent, String shortcutId){
        LinearLayout row = root.findViewById(rowId);
        ImageView icon = root.findViewById(iconId);
        TextView label = root.findViewById(titleIdFor(rowId));
        if(label!=null) label.setText(title);

        // Icon click must also show confirmation (no direct pinning)
        if(icon!=null){ icon.setOnClickListener(v -> showConfirmSheet(title, shortcutId, iconRes, intent)); }

        if(row!=null){
            row.setOnClickListener(v -> showConfirmSheet(title, shortcutId, iconRes, intent));
        }
    }

    private int titleIdFor(int rowId){
        if(rowId == R.id.cell_start) return R.id.title_start;
        if(rowId == R.id.cell_stop) return R.id.title_stop;
        return R.id.title_torch;
    }

    private void requestPin(String shortcutId, String label, int iconRes, Intent intent){
        Context ctx = requireContext();
        if(!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)){
            android.widget.Toast.makeText(ctx, R.string.widgets_pin_unsupported, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, shortcutId)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithResource(ctx, iconRes))
                .setIntent(intent)
                .build();
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null);
    }

    private void showConfirmSheet(String title, String shortcutId, int iconRes, Intent intent){
        final String resultKey = "picker_result_pin_" + shortcutId;
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String selected = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if("pin".equals(selected)){
                    requestPin(shortcutId, title, iconRes, intent);
                }
            }
        });
    java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
    // Leading icon is the shortcut icon; trailing is external-link indicator
    items.add(new com.fadcam.ui.picker.OptionItem("pin", getString(R.string.shortcuts_add_to_home), (String) null, null, iconRes, R.drawable.ic_open_in_new));
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
        title, items, null, resultKey, getString(R.string.shortcuts_sheet_helper));
    // Hide selection check; we only show trailing external-link icon
    if(sheet.getArguments()!=null){ sheet.getArguments().putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true); }
        // Pass grid mode false, but we want to show the shortcut icon prominently in the title area by setting header icon view if supported
        // As a simple approach, we prefix title with an inline space and rely on icon in option; alternative is to extend picker layout (deferred)
        sheet.show(getParentFragmentManager(), "pin_sheet_"+shortcutId);
    }

    private void showClockWidgetSheet(){
        final String resultKey = "picker_result_pin_widget_clock";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String selected = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if("pin_widget".equals(selected)){
                    // Delay to allow bottom sheet to dismiss completely before showing system dialog
                    getView().postDelayed(() -> requestPinClockWidget(), 300);
                } else if("customize".equals(selected)){
                    showCustomizeSheet();
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                "pin_widget",
                getString(R.string.shortcuts_add_to_home),
                (String) null,
                null,
                R.drawable.ic_clock_widget,
                R.drawable.ic_open_in_new
        ));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "customize",
                "Customize",
                "Time format, date format, Arabic date",
                null,
                R.drawable.ic_settings,
                null
        ));
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.widget_clock_title),
                items,
                null,
                resultKey,
                getString(R.string.shortcuts_sheet_helper)
        );
        if(sheet.getArguments()!=null){
            sheet.getArguments().putBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "pin_sheet_widget_clock");
    }

    private void showCustomizeSheet(){
        final String resultKey = "picker_result_customize_widget";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String selected = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if("time_format".equals(selected)){
                    showTimeFormatSheet();
                } else if("date_format".equals(selected)){
                    showDateFormatSheet();
                } else if("arabic_date".equals(selected)){
                    toggleArabicDate();
                }
            }
        });
        
        com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        
        items.add(new com.fadcam.ui.picker.OptionItem(
                "time_format",
                "Time Format",
                prefs.is24HourFormat() ? "24-hour (15:30)" : "12-hour (3:30 PM)",
                null,
                R.drawable.ic_clock_widget,
                null
        ));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "date_format",
                "Date Format",
                "Choose date display style",
                null,
                R.drawable.ic_calendar,
                null
        ));
        items.add(new com.fadcam.ui.picker.OptionItem(
                "arabic_date",
                "Arabic Date",
                prefs.showArabicDate() ? "Enabled" : "Disabled",
                null,
                R.drawable.ic_language,
                null
        ));
        
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                "Customize Clock Widget",
                items,
                null,
                resultKey,
                "Customize how your clock widget displays time and date"
        );
        sheet.show(getParentFragmentManager(), "customize_widget");
    }

    private void showTimeFormatSheet(){
        final String resultKey = "picker_result_time_format";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String selected = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
                prefs.setTimeFormat("24h".equals(selected));
                updateAllWidgets();
                updatePreview();
                // Show customize sheet again after selection
                new Handler(Looper.getMainLooper()).postDelayed(() -> showCustomizeSheet(), 200);
            }
        });
        
        com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
        String current = prefs.is24HourFormat() ? "24h" : "12h";
        
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("12h", "12-hour format", "3:30 PM", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem("24h", "24-hour format", "15:30", null, null, null));
        
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                "Time Format", items, current, resultKey, null);
        sheet.show(getParentFragmentManager(), "time_format");
    }

    private void showDateFormatSheet(){
        final String resultKey = "picker_result_date_format";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String selected = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
                prefs.setDateFormat(selected);
                updateAllWidgets();
                updatePreview();
                // Show customize sheet again after selection
                new Handler(Looper.getMainLooper()).postDelayed(() -> showCustomizeSheet(), 200);
            }
        });
        
        com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
        String current = prefs.getDateFormat();
        
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(com.fadcam.widgets.WidgetPreferences.DATE_FORMAT_DAY_MONTH_YEAR, "13 Aug 2025", "day month year", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem(com.fadcam.widgets.WidgetPreferences.DATE_FORMAT_MONTH_DAY_YEAR, "Aug 13, 2025", "month day year", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem(com.fadcam.widgets.WidgetPreferences.DATE_FORMAT_DMY_NUMERIC, "13/08/2025", "day/month/year", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem(com.fadcam.widgets.WidgetPreferences.DATE_FORMAT_MDY_NUMERIC, "08/13/2025", "month/day/year", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem(com.fadcam.widgets.WidgetPreferences.DATE_FORMAT_FULL_DAY_NO_YEAR, "Wednesday, 13 Aug", "full day name", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem(com.fadcam.widgets.WidgetPreferences.DATE_FORMAT_SHORT_DAY_NO_YEAR, "Wed, 13 Aug", "short day name", null, null, null));
        
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                "Date Format", items, current, resultKey, null);
        sheet.show(getParentFragmentManager(), "date_format");
    }

    private void toggleArabicDate(){
        com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
        prefs.setShowArabicDate(!prefs.showArabicDate());
        updateAllWidgets();
        updatePreview();
        // Show customize sheet again after toggle
        new Handler(Looper.getMainLooper()).postDelayed(() -> showCustomizeSheet(), 200);
    }

    private void updateAllWidgets(){
        android.content.Context ctx = requireContext();
        android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(ctx);
        android.content.ComponentName provider = new android.content.ComponentName(ctx, com.fadcam.widgets.ClockWidgetProvider.class);
        int[] ids = awm.getAppWidgetIds(provider);
        if(ids.length > 0){
            android.content.Intent intent = new android.content.Intent(ctx, com.fadcam.widgets.ClockWidgetProvider.class);
            intent.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            ctx.sendBroadcast(intent);
        }
        updatePreview();
    }

    private void updatePreview(){
        // -------------- Fix Start for this method(updatePreview)-----------
        if(getView() == null) return;
        View preview = getView().findViewById(R.id.clock_widget_preview);
        if(preview == null) return;

        com.fadcam.widgets.WidgetPreferences prefs = new com.fadcam.widgets.WidgetPreferences(requireContext());
        
        // Update time and AM/PM
        java.text.SimpleDateFormat timeFormat;
        if(prefs.is24HourFormat()){
            timeFormat = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            android.widget.TextView timeView = preview.findViewById(R.id.clock_time_preview);
            android.widget.TextView ampmView = preview.findViewById(R.id.clock_ampm_preview);
            if(timeView != null) timeView.setText(timeFormat.format(new java.util.Date()));
            if(ampmView != null) ampmView.setVisibility(android.view.View.GONE);
        } else {
            timeFormat = new java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault());
            String ampm = new java.text.SimpleDateFormat("a", java.util.Locale.getDefault()).format(new java.util.Date());
            android.widget.TextView timeView = preview.findViewById(R.id.clock_time_preview);
            android.widget.TextView ampmView = preview.findViewById(R.id.clock_ampm_preview);
            if(timeView != null) timeView.setText(timeFormat.format(new java.util.Date()));
            if(ampmView != null) {
                ampmView.setText(ampm);
                ampmView.setVisibility(android.view.View.VISIBLE);
            }
        }
        
        // Update date
        String datePattern = prefs.getDateFormat();
        String date = new java.text.SimpleDateFormat(datePattern, java.util.Locale.getDefault()).format(new java.util.Date());
        android.widget.TextView dateView = preview.findViewById(R.id.clock_date_preview);
        if(dateView != null) dateView.setText(date);
        
        // Update Arabic date
        android.widget.TextView arabicDateView = preview.findViewById(R.id.clock_date_arabic_preview);
        if(arabicDateView != null){
            if(prefs.showArabicDate()){
                String arabicDate = com.fadcam.widgets.ArabicDateUtils.getArabicDate();
                arabicDateView.setText(arabicDate);
                arabicDateView.setVisibility(android.view.View.VISIBLE);
            } else {
                arabicDateView.setVisibility(android.view.View.GONE);
            }
        }
        // -------------- Fix Ended for this method(updatePreview)-----------
    }

    private void requestPinClockWidget(){
        android.content.Context ctx = requireContext();
        android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(ctx);
        android.content.ComponentName provider = new android.content.ComponentName(ctx, com.fadcam.widgets.ClockWidgetProvider.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && awm.isRequestPinAppWidgetSupported()) {
            // -------------- Fix Start for this method(requestPinClockWidget)-----------
            PendingIntent success = PendingIntent.getActivity(
                    ctx, 0, new Intent(ctx, com.fadcam.MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            awm.requestPinAppWidget(provider, null, success);
            // -------------- Fix Ended for this method(requestPinClockWidget)-----------
        } else {
            android.widget.Toast.makeText(ctx, R.string.widgets_pin_unsupported, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
