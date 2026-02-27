package com.fadcam.forensics.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.ui.OverlayNavUtil;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Intelligence Briefing — presents a situation report with threat assessment,
 * detection breakdown, interactive activity timeline, zone analysis,
 * and auto-generated key observations from forensic AI event data.
 * <p>
 * Supports selectable time ranges (24H, 7D, 30D, ALL) and interactive bar
 * chart with per-hour breakdown on tap.
 */
public class ForensicsInsightsFragment extends Fragment {

    private static final String ARG_EMBEDDED = "arg_embedded";

    // ── Time ranges ──────────────────────────────────────────────────────
    private enum TimeRange {
        H24(24L * 60L * 60L * 1000L, 1000),
        D7(7L * 24L * 60L * 60L * 1000L, 3000),
        D30(30L * 24L * 60L * 60L * 1000L, 5000),
        ALL(0L, 10000);

        final long durationMs;
        final int queryLimit;

        TimeRange(long durationMs, int queryLimit) {
            this.durationMs = durationMs;
            this.queryLimit = queryLimit;
        }
    }

    // ── Threat levels ────────────────────────────────────────────────────
    private enum ThreatLevel {
        LOW(0xFF4CAF50, "LOW",
                "Minimal activity detected",
                "Low event volume, no significant patterns."),
        GUARDED(0xFF2196F3, "GUARDED",
                "Normal operational activity",
                "Routine detections within expected range."),
        ELEVATED(0xFFFFC107, "ELEVATED",
                "Increased detection volume",
                "Above-average activity warrants monitoring."),
        HIGH(0xFFFF9800, "HIGH",
                "Significant activity flagged",
                "High personnel presence or priority events."),
        SEVERE(0xFFF44336, "SEVERE",
                "Critical activity level",
                "Extreme event volume or priority concentration.");

        final int color;
        final String label;
        final String description;
        final String basis;

        ThreatLevel(int color, String label, String description, String basis) {
            this.color = color;
            this.label = label;
            this.description = description;
            this.basis = basis;
        }
    }

    // ── View references ──────────────────────────────────────────────────
    private TextView sitrepTime;
    private TextView totalCount;
    private TextView timeRangeLabel;
    private TextView threatBadge;
    private TextView threatDesc;
    private TextView threatBasis;
    private TextView countPersonnel;
    private TextView countVehicles;
    private TextView countAnimals;
    private TextView countObjects;
    private View cellPersonnel;
    private View cellVehicles;
    private View cellAnimals;
    private View cellObjects;
    private LinearLayout containerBars;
    private TextView peakInfo;
    private TextView timelineDetail;
    private TextView timelineTitle;
    private View selectedBar;
    private GradientDrawable selectedBarOrigBg;
    private final FrameLayout[][] zoneCells = new FrameLayout[3][3];
    private final TextView[][] zoneCountTexts = new TextView[3][3];
    private TextView observations;
    private View scrollContent;
    private View emptyState;
    private ProgressBar loadingProgress;

    // Time range chips
    private Chip chip24h;
    private Chip chip7d;
    private Chip chip30d;
    private Chip chipAll;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean embeddedMode;
    private boolean dataLoaded;
    private boolean isLoading;
    private TimeRange selectedRange = TimeRange.H24;

    // Theme colours
    private int accentColor;

    // Retained for bar-tap detail display
    private int[][] hourlyTypeCounts;

    // ── Factory ──────────────────────────────────────────────────────────

    public static ForensicsInsightsFragment newEmbeddedInstance() {
        ForensicsInsightsFragment fragment = new ForensicsInsightsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_EMBEDDED, true);
        fragment.setArguments(args);
        return fragment;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_forensics_insights, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        embeddedMode = getArguments() != null && getArguments().getBoolean(ARG_EMBEDDED, false);
        accentColor = resolveThemeColor(R.attr.colorButton);

        // Header
        View header = view.findViewById(R.id.header_bar);
        if (header != null && embeddedMode) header.setVisibility(View.GONE);
        View backButton = view.findViewById(R.id.back_button);
        if (backButton != null) {
            if (embeddedMode) {
                backButton.setVisibility(View.GONE);
            } else {
                backButton.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
            }
        }

        // State containers
        scrollContent = view.findViewById(R.id.scroll_content);
        emptyState = view.findViewById(R.id.empty_state);
        loadingProgress = view.findViewById(R.id.progress_loading);

        // SITREP
        sitrepTime = view.findViewById(R.id.text_sitrep);

        // Time range chips
        chip24h = view.findViewById(R.id.chip_24h);
        chip7d = view.findViewById(R.id.chip_7d);
        chip30d = view.findViewById(R.id.chip_30d);
        chipAll = view.findViewById(R.id.chip_all);
        setupChips();

        // Total hero
        totalCount = view.findViewById(R.id.text_total_count);
        timeRangeLabel = view.findViewById(R.id.text_time_range);

        // Threat
        threatBadge = view.findViewById(R.id.text_threat_badge);
        threatDesc = view.findViewById(R.id.text_threat_desc);
        threatBasis = view.findViewById(R.id.text_threat_basis);

        // Detection breakdown
        countPersonnel = view.findViewById(R.id.count_personnel);
        countVehicles = view.findViewById(R.id.count_vehicles);
        countAnimals = view.findViewById(R.id.count_animals);
        countObjects = view.findViewById(R.id.count_objects);
        cellPersonnel = view.findViewById(R.id.cell_personnel);
        cellVehicles = view.findViewById(R.id.cell_vehicles);
        cellAnimals = view.findViewById(R.id.cell_animals);
        cellObjects = view.findViewById(R.id.cell_objects);
        tintDetectionCells();

        // Timeline
        containerBars = view.findViewById(R.id.container_bars);
        peakInfo = view.findViewById(R.id.text_peak_info);
        timelineDetail = view.findViewById(R.id.text_timeline_detail);
        timelineTitle = view.findViewById(R.id.text_timeline_title);
        updateTimelineTitle();

        // Zone grid
        zoneCells[0][0] = view.findViewById(R.id.zone_nw);
        zoneCells[0][1] = view.findViewById(R.id.zone_n);
        zoneCells[0][2] = view.findViewById(R.id.zone_ne);
        zoneCells[1][0] = view.findViewById(R.id.zone_w);
        zoneCells[1][1] = view.findViewById(R.id.zone_c);
        zoneCells[1][2] = view.findViewById(R.id.zone_e);
        zoneCells[2][0] = view.findViewById(R.id.zone_sw);
        zoneCells[2][1] = view.findViewById(R.id.zone_s);
        zoneCells[2][2] = view.findViewById(R.id.zone_se);
        zoneCountTexts[0][0] = view.findViewById(R.id.zone_count_nw);
        zoneCountTexts[0][1] = view.findViewById(R.id.zone_count_n);
        zoneCountTexts[0][2] = view.findViewById(R.id.zone_count_ne);
        zoneCountTexts[1][0] = view.findViewById(R.id.zone_count_w);
        zoneCountTexts[1][1] = view.findViewById(R.id.zone_count_c);
        zoneCountTexts[1][2] = view.findViewById(R.id.zone_count_e);
        zoneCountTexts[2][0] = view.findViewById(R.id.zone_count_sw);
        zoneCountTexts[2][1] = view.findViewById(R.id.zone_count_s);
        zoneCountTexts[2][2] = view.findViewById(R.id.zone_count_se);

        // Observations
        observations = view.findViewById(R.id.text_observations);

        loadIntelligence();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && !dataLoaded && !isLoading) {
            loadIntelligence();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }

    // ── Time range chips ─────────────────────────────────────────────────

    private void setupChips() {
        ChipGroup chipGroup = null;
        if (chip24h != null) chipGroup = (ChipGroup) chip24h.getParent();
        if (chipGroup != null) {
            chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                if (id == R.id.chip_24h) selectRange(TimeRange.H24);
                else if (id == R.id.chip_7d) selectRange(TimeRange.D7);
                else if (id == R.id.chip_30d) selectRange(TimeRange.D30);
                else if (id == R.id.chip_all) selectRange(TimeRange.ALL);
            });
        }
        updateChipVisuals();
    }

    private void selectRange(TimeRange range) {
        if (range == selectedRange) return;
        selectedRange = range;
        updateChipVisuals();
        updateTimelineTitle();
        dataLoaded = false;
        loadIntelligence();
    }

    private void updateTimelineTitle() {
        if (timelineTitle == null) return;
        String rangeLabel;
        switch (selectedRange) {
            case D7:  rangeLabel = "7 DAYS"; break;
            case D30: rangeLabel = "30 DAYS"; break;
            case ALL: rangeLabel = "ALL TIME"; break;
            default:  rangeLabel = "24H"; break;
        }
        timelineTitle.setText("ACTIVITY TIMELINE \u2022 " + rangeLabel);
    }

    private void updateChipVisuals() {
        Chip[] chips = {chip24h, chip7d, chip30d, chipAll};
        TimeRange[] ranges = {TimeRange.H24, TimeRange.D7, TimeRange.D30, TimeRange.ALL};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            chips[i].setChecked(ranges[i] == selectedRange);
        }
    }

    // ── Data loading & computation ───────────────────────────────────────

    private void loadIntelligence() {
        if (isLoading) return;
        isLoading = true;

        if (scrollContent != null) scrollContent.setVisibility(View.GONE);
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (loadingProgress != null) loadingProgress.setVisibility(View.VISIBLE);
        if (timelineDetail != null) timelineDetail.setVisibility(View.GONE);

        final long now = System.currentTimeMillis();
        final long since = selectedRange == TimeRange.ALL ? 0 : (now - selectedRange.durationMs);
        final int limit = selectedRange.queryLimit;
        final Context appContext = requireContext().getApplicationContext();

        executor.execute(() -> {
            try {
                ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
                int total = db.aiEventDao().countSince(since);
                int people = db.aiEventDao().countByTypeSince("PERSON", since);
                int vehicle = db.aiEventDao().countByTypeSince("VEHICLE", since);
                int pet = db.aiEventDao().countByTypeSince("PET", since);
                int object = db.aiEventDao().countByTypeSince("OBJECT", since);
                List<AiEventEntity> events = db.aiEventDao().getRecentForHeatmap(since, limit);

                // Per-hour counts and per-hour-per-type breakdown
                int highPriority = 0;
                int highConfidence = 0;
                long firstDetection = Long.MAX_VALUE;
                long lastDetection = Long.MIN_VALUE;
                int[] hourlyCounts = new int[24];
                int[][] hourlyTypes = new int[24][4]; // [hour][PERSON=0,VEHICLE=1,PET=2,OBJECT=3]
                int[][] zones = new int[3][3];

                Calendar cal = Calendar.getInstance();
                for (AiEventEntity e : events) {
                    if (e.priority >= 2) highPriority++;
                    if (e.confidence >= 0.75f || e.peakConfidence >= 0.75f) highConfidence++;
                    if (e.detectedAtEpochMs < firstDetection) firstDetection = e.detectedAtEpochMs;
                    if (e.detectedAtEpochMs > lastDetection) lastDetection = e.detectedAtEpochMs;
                    cal.setTimeInMillis(e.detectedAtEpochMs);
                    int hour = cal.get(Calendar.HOUR_OF_DAY);
                    hourlyCounts[hour]++;
                    int ti = typeIndex(e.eventType);
                    if (ti >= 0) hourlyTypes[hour][ti]++;
                    float[] bbox = parseBbox(e.bboxNorm);
                    int col = Math.min(2, (int) (bbox[0] * 3));
                    int row = Math.min(2, (int) (bbox[1] * 3));
                    zones[row][col]++;
                }

                // Peak hour
                int peakHour = 0;
                int peakCount = 0;
                for (int h = 0; h < 24; h++) {
                    if (hourlyCounts[h] > peakCount) {
                        peakCount = hourlyCounts[h];
                        peakHour = h;
                    }
                }

                // Max zone
                int maxZoneCount = 0;
                int maxZoneRow = 0;
                int maxZoneCol = 0;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        if (zones[r][c] > maxZoneCount) {
                            maxZoneCount = zones[r][c];
                            maxZoneRow = r;
                            maxZoneCol = c;
                        }
                    }
                }

                // Longest quiet streak
                int longestQuiet = 0;
                int currentQuiet = 0;
                int quietStartBest = -1;
                int quietStart = -1;
                for (int h = 0; h < 24; h++) {
                    if (hourlyCounts[h] == 0) {
                        if (currentQuiet == 0) quietStart = h;
                        currentQuiet++;
                        if (currentQuiet > longestQuiet) {
                            longestQuiet = currentQuiet;
                            quietStartBest = quietStart;
                        }
                    } else {
                        currentQuiet = 0;
                    }
                }

                // Active hours count
                int activeHours = 0;
                for (int c : hourlyCounts) if (c > 0) activeHours++;

                // Threat level
                float personRatio = total > 0 ? (float) people / total : 0f;
                ThreatLevel threat = computeThreatLevel(total, personRatio, highPriority);

                // Observations
                String obs = generateObservations(
                        total, people, vehicle, pet, object,
                        highPriority, highConfidence,
                        firstDetection, lastDetection,
                        peakHour, peakCount, activeHours,
                        maxZoneRow, maxZoneCol, maxZoneCount,
                        longestQuiet, quietStartBest,
                        zones, events.size(), now);

                // SITREP — show generation time + selected range
                String rangeTag;
                switch (selectedRange) {
                    case D7:  rangeTag = "LAST 7 DAYS"; break;
                    case D30: rangeTag = "LAST 30 DAYS"; break;
                    case ALL: rangeTag = "ALL TIME"; break;
                    default:  rangeTag = "LAST 24 HOURS"; break;
                }
                String sitrep = new SimpleDateFormat("h:mm a \u2022 dd MMM yyyy", Locale.US)
                        .format(new Date(now)).toUpperCase(Locale.US)
                        + " \u2022 " + rangeTag;

                // Time range label
                String rangeSummary;
                switch (selectedRange) {
                    case D7:
                        rangeSummary = getString(R.string.intel_range_summary_7d);
                        break;
                    case D30:
                        rangeSummary = getString(R.string.intel_range_summary_30d);
                        break;
                    case ALL:
                        rangeSummary = getString(R.string.intel_range_summary_all);
                        break;
                    default:
                        rangeSummary = getString(R.string.intel_range_summary_24h);
                        break;
                }

                // Capture finals
                final int fTotal = total;
                final int fPeople = people;
                final int fVehicle = vehicle;
                final int fPet = pet;
                final int fObject = object;
                final ThreatLevel fThreat = threat;
                final int[] fHourly = hourlyCounts;
                final int[][] fHourlyTypes = hourlyTypes;
                final int fPeakHour = peakHour;
                final int fPeakCount = peakCount;
                final int[][] fZones = zones;
                final String fObs = obs;
                final String fSitrep = sitrep;
                final String fRange = rangeSummary;

                if (!isAdded()) {
                    isLoading = false;
                    return;
                }
                requireActivity().runOnUiThread(() -> bindUI(
                        fTotal, fPeople, fVehicle, fPet, fObject,
                        fThreat, fHourly, fHourlyTypes, fPeakHour, fPeakCount,
                        fZones, fObs, fSitrep, fRange));
            } catch (Exception e) {
                android.util.Log.e("IntelBriefing", "Failed to compile intelligence", e);
                isLoading = false;
            }
        });
    }

    private void bindUI(int total, int people, int vehicle, int pet, int object,
                        ThreatLevel threat, int[] hourly, int[][] hourlyTypes,
                        int peakHour, int peakCount,
                        int[][] zones, String obs, String sitrep, String rangeSummary) {
        if (!isAdded()) {
            isLoading = false;
            return;
        }
        if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);

        if (total == 0) {
            if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
            if (scrollContent != null) scrollContent.setVisibility(View.GONE);
        } else {
            if (emptyState != null) emptyState.setVisibility(View.GONE);
            if (scrollContent != null) scrollContent.setVisibility(View.VISIBLE);

            if (sitrepTime != null) sitrepTime.setText(sitrep);
            if (totalCount != null) totalCount.setText(String.valueOf(total));
            if (timeRangeLabel != null) timeRangeLabel.setText(rangeSummary);

            applyThreatBadge(threat);

            if (countPersonnel != null) countPersonnel.setText(String.valueOf(people));
            if (countVehicles != null) countVehicles.setText(String.valueOf(vehicle));
            if (countAnimals != null) countAnimals.setText(String.valueOf(pet));
            if (countObjects != null) countObjects.setText(String.valueOf(object));

            if (peakInfo != null) {
                peakInfo.setText(String.format(Locale.US,
                        "Peak: %s \u2013 %s (%d events)",
                        formatHourAmPm(peakHour), formatHourEndAmPm(peakHour), peakCount));
            }
            this.hourlyTypeCounts = hourlyTypes;
            populateTimeline(hourly, peakHour);
            populateZones(zones, total);

            if (observations != null) observations.setText(obs);
        }
        isLoading = false;
        dataLoaded = true;
    }

    // ── Threat computation ───────────────────────────────────────────────

    private ThreatLevel computeThreatLevel(int total, float personRatio, int highPriority) {
        if (total > 100 && (personRatio > 0.5f || highPriority > 5)) return ThreatLevel.SEVERE;
        if (total > 50 && (personRatio > 0.4f || highPriority > 3)) return ThreatLevel.HIGH;
        if (total > 20 || highPriority > 1) return ThreatLevel.ELEVATED;
        if (total > 5) return ThreatLevel.GUARDED;
        return ThreatLevel.LOW;
    }

    private void applyThreatBadge(ThreatLevel level) {
        if (threatBadge == null) return;
        threatBadge.setText(level.label);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(4));
        bg.setColor(level.color);
        threatBadge.setBackground(bg);
        if (threatDesc != null) threatDesc.setText(level.description);
        if (threatBasis != null) threatBasis.setText(level.basis);
    }

    // ── Detection cell tinting ───────────────────────────────────────────

    private void tintDetectionCells() {
        View[] cells = {cellPersonnel, cellVehicles, cellAnimals, cellObjects};
        // Resolve colorTopBar from the current theme for consistent card-in-card look
        int cellColor;
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(R.attr.colorTopBar, tv, true);
            cellColor = tv.data;
        } catch (Exception e) {
            cellColor = 0xFF1A1A2E; // fallback
        }
        for (View cell : cells) {
            if (cell == null) continue;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dpToPx(8));
            bg.setColor(cellColor);
            cell.setBackground(bg);
        }
    }

    // ── Activity timeline (interactive 24-hour bar chart) ────────────────

    private void populateTimeline(int[] hourlyCounts, int peakHour) {
        if (containerBars == null) return;
        containerBars.removeAllViews();

        int maxCount = 1;
        for (int c : hourlyCounts) maxCount = Math.max(maxCount, c);

        int containerHeight = containerBars.getHeight();
        if (containerHeight <= 0) containerHeight = dpToPx(80);

        for (int h = 0; h < 24; h++) {
            View bar = new View(requireContext());
            int barHeight = hourlyCounts[h] == 0
                    ? dpToPx(2)
                    : Math.max(dpToPx(4), (int) (((float) hourlyCounts[h] / maxCount) * containerHeight));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, barHeight);
            lp.weight = 1f;
            lp.setMargins(dpToPx(1), 0, dpToPx(1), 0);
            bar.setLayoutParams(lp);

            GradientDrawable barBg = new GradientDrawable();
            float r = dpToPx(2);
            barBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            if (h == peakHour && hourlyCounts[h] > 0) {
                barBg.setColor(0xFFF44336);
            } else if (hourlyCounts[h] > 0) {
                barBg.setColor(accentColor);
            } else {
                barBg.setColor(0x33FFFFFF);
            }
            bar.setBackground(barBg);

            // Interactive: tap bar to see hour breakdown
            final int hour = h;
            final int count = hourlyCounts[h];
            bar.setClickable(true);
            bar.setTag(R.id.container_bars, barBg); // store original bg for restoration
            bar.setOnClickListener(v -> showHourDetail(v, hour, count));
            containerBars.addView(bar);
        }
    }

    /**
     * Shows a detail panel below the bar chart with per-type breakdown for the tapped hour.
     * Highlights the tapped bar with a bright dotted outline and restores the previous bar.
     */
    private void showHourDetail(View tappedBar, int hour, int count) {
        // Restore previous bar's background
        if (selectedBar != null && selectedBarOrigBg != null) {
            selectedBar.setBackground(selectedBarOrigBg);
        }

        // Highlight tapped bar with dotted outline
        GradientDrawable origBg = (GradientDrawable) tappedBar.getTag(R.id.container_bars);
        selectedBarOrigBg = origBg;
        selectedBar = tappedBar;

        if (origBg != null) {
            GradientDrawable highlighted = new GradientDrawable();
            float r = dpToPx(2);
            highlighted.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            highlighted.setColor(origBg.getColor() != null ? origBg.getColor().getDefaultColor() : accentColor);
            highlighted.setStroke(dpToPx(2), 0xFFFFFFFF, dpToPx(3), dpToPx(2)); // white dotted outline
            tappedBar.setBackground(highlighted);
        }

        if (timelineDetail == null) return;
        if (count == 0) {
            timelineDetail.setText(String.format(Locale.US,
                    "%s \u2013 %s: No detections",
                    formatHourAmPm(hour), formatHourEndAmPm(hour)));
        } else {
            int p = 0, v = 0, a = 0, o = 0;
            if (hourlyTypeCounts != null) {
                p = hourlyTypeCounts[hour][0];
                v = hourlyTypeCounts[hour][1];
                a = hourlyTypeCounts[hour][2];
                o = hourlyTypeCounts[hour][3];
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%s \u2013 %s: %d detection(s)\n",
                    formatHourAmPm(hour), formatHourEndAmPm(hour), count));
            sb.append("   Person: ").append(p);
            sb.append(" \u00b7 Vehicle: ").append(v);
            sb.append(" \u00b7 Animal: ").append(a);
            sb.append(" \u00b7 Object: ").append(o);
            timelineDetail.setText(sb.toString());
        }
        timelineDetail.setVisibility(View.VISIBLE);
    }

    // ── Zone analysis (3×3 sector grid) ──────────────────────────────────

    private void populateZones(int[][] zoneCounts, int totalEvents) {
        int maxZone = 1;
        for (int r1 = 0; r1 < 3; r1++)
            for (int c1 = 0; c1 < 3; c1++)
                maxZone = Math.max(maxZone, zoneCounts[r1][c1]);

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (zoneCells[r][c] == null || zoneCountTexts[r][c] == null) continue;
                int count = zoneCounts[r][c];
                float intensity = (float) count / maxZone;

                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dpToPx(4));
                if (count == 0) {
                    bg.setColor(0xFF1A1A2E);
                } else {
                    int red = (int) (50 + intensity * 205);
                    int green = (int) (50 + (1f - intensity) * 100);
                    int blue = (int) (50 + (1f - intensity) * 50);
                    bg.setColor(Color.rgb(red, green, blue));
                }
                zoneCells[r][c].setBackground(bg);

                // Show count + percentage
                if (totalEvents > 0 && count > 0) {
                    int pct = (int) ((count * 100f) / totalEvents);
                    zoneCountTexts[r][c].setText(count + "\n" + pct + "%");
                } else {
                    zoneCountTexts[r][c].setText(String.valueOf(count));
                }
                zoneCountTexts[r][c].setTextColor(count > 0 ? 0xFFFFFFFF : 0x80FFFFFF);
            }
        }
    }

    // ── Auto-generated intelligence observations ─────────────────────────

    private String generateObservations(
            int total, int people, int vehicle, int pet, int object,
            int highPriority, int highConfidence,
            long firstMs, long lastMs,
            int peakHour, int peakCount, int activeHours,
            int maxZoneRow, int maxZoneCol, int maxZoneCount,
            int longestQuiet, int quietStart,
            int[][] zones, int sampleSize, long now) {

        StringBuilder sb = new StringBuilder();
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.US);

        // Peak activity
        sb.append("\u25B8 Peak activity: ")
                .append(formatHourAmPm(peakHour)).append(" \u2013 ").append(formatHourEndAmPm(peakHour))
                .append(" (").append(peakCount).append(" events)\n");

        // Active hours
        sb.append("\u25B8 Activity detected across ").append(activeHours).append(" of 24 hours\n");

        // Dominant type + full breakdown
        String[] types = {"PERSONNEL", "VEHICLE", "ANIMAL", "OBJECT"};
        int[] counts = {people, vehicle, pet, object};
        int maxIdx = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[maxIdx]) maxIdx = i;
        }
        if (total > 0) {
            int pct = (int) ((counts[maxIdx] * 100f) / total);
            sb.append("\u25B8 Dominant type: ").append(types[maxIdx])
                    .append(" (").append(pct).append("% of total)\n");
            // Full breakdown
            sb.append("   Person: ").append(people)
                    .append(" \u00b7 Vehicle: ").append(vehicle)
                    .append(" \u00b7 Animal: ").append(pet)
                    .append(" \u00b7 Object: ").append(object).append('\n');
        }

        // High priority
        if (highPriority > 0) {
            sb.append("\u25B8 ").append(highPriority)
                    .append(" high-priority event(s) flagged (priority \u22652)\n");
        } else {
            sb.append("\u25B8 No high-priority events flagged\n");
        }

        // High confidence
        if (highConfidence > 0 && total > 0) {
            int confPct = (int) ((highConfidence * 100f) / total);
            sb.append("\u25B8 ").append(highConfidence)
                    .append(" high-confidence detection(s) (\u226575%): ")
                    .append(confPct).append("% of total\n");
        }

        // First / last detection
        if (firstMs != Long.MAX_VALUE && lastMs != Long.MIN_VALUE) {
            sb.append("\u25B8 First detection: ").append(timeFmt.format(new Date(firstMs)))
                    .append(" \u2022 Last: ").append(timeFmt.format(new Date(lastMs))).append('\n');
            // Duration of monitoring
            long spanMs = lastMs - firstMs;
            long spanHours = spanMs / (60L * 60L * 1000L);
            long spanMins = (spanMs % (60L * 60L * 1000L)) / (60L * 1000L);
            if (spanHours > 0) {
                sb.append("   Active monitoring span: ").append(spanHours)
                        .append("h ").append(spanMins).append("m\n");
            }
        }

        // Hotspot zone
        String[][] zoneNames = {
                {"Top-Left", "Top", "Top-Right"},
                {"Left", "Center", "Right"},
                {"Bottom-Left", "Bottom", "Bottom-Right"}
        };
        if (maxZoneCount > 0 && total > 0) {
            int zonePct = (int) ((maxZoneCount * 100f) / total);
            sb.append("\u25B8 Hotspot: ").append(zoneNames[maxZoneRow][maxZoneCol])
                    .append(" sector (").append(maxZoneCount).append(" events, ")
                    .append(zonePct).append("% of total)\n");
        }

        // Quiet period
        if (longestQuiet >= 2 && quietStart >= 0) {
            sb.append("\u25B8 Longest gap: ")
                    .append(formatHourAmPm(quietStart)).append(" \u2013 ")
                    .append(formatHourEndAmPm((quietStart + longestQuiet - 1) % 24))
                    .append(" (").append(longestQuiet).append("h quiet)\n");
        }

        // Sample note
        if (sampleSize < total) {
            sb.append("\u25B8 Analysis based on ").append(sampleSize)
                    .append(" of ").append(total).append(" events (sampled)\n");
        }

        return sb.toString().trim();
    }

    // ── Time formatting helpers ──────────────────────────────────────────

    /** Formats hour (0-23) as "12:00 AM", "1:00 PM", etc. */
    private String formatHourAmPm(int hour) {
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        String amPm = hour < 12 ? "AM" : "PM";
        return h12 + ":00 " + amPm;
    }

    /** Formats end of hour (e.g., hour 14 → "2:59 PM"). */
    private String formatHourEndAmPm(int hour) {
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        String amPm = hour < 12 ? "AM" : "PM";
        return h12 + ":59 " + amPm;
    }

    // ── Util ─────────────────────────────────────────────────────────────

    private int typeIndex(String eventType) {
        if ("PERSON".equals(eventType)) return 0;
        if ("VEHICLE".equals(eventType)) return 1;
        if ("PET".equals(eventType)) return 2;
        if ("OBJECT".equals(eventType)) return 3;
        return -1;
    }

    private float[] parseBbox(String raw) {
        float[] def = {0.5f, 0.5f, 0.08f, 0.08f};
        if (raw == null || raw.isEmpty()) return def;
        try {
            String[] parts = raw.split(",");
            if (parts.length < 4) return def;
            return new float[]{
                    clamp01(Float.parseFloat(parts[0])),
                    clamp01(Float.parseFloat(parts[1])),
                    clamp01(Float.parseFloat(parts[2])),
                    clamp01(Float.parseFloat(parts[3]))
            };
        } catch (Exception ignored) {
            return def;
        }
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private int resolveThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
