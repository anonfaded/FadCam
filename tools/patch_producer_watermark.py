from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def edit(path, replacements):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    original = text
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"PATCH ANCHOR NOT FOUND: {path}: {old[:120]!r}")
        text = text.replace(old, new, 1)
    if text == original:
        raise SystemExit(f"NO CHANGE MADE: {path}")
    p.write_text(text, encoding="utf-8")


# 1) Persist producer logo URI and safe presentation settings.
edit("app/src/main/java/com/fadcam/Constants.java", [
    (
        '    public static final String PREF_WATERMARK_CUSTOM_TEXT = "watermark_custom_text";\n',
        '    public static final String PREF_WATERMARK_CUSTOM_TEXT = "watermark_custom_text";\n'
        '    public static final String PREF_WATERMARK_LOGO_URI = "watermark_logo_uri";\n'
        '    public static final String PREF_WATERMARK_LOGO_SIZE = "watermark_logo_size"; // small|medium|large\n'
        '    public static final String PREF_WATERMARK_LOGO_OPACITY = "watermark_logo_opacity"; // 0..255\n'
    ),
])

# 2) SharedPreferences accessors used by UI and the rendering pipeline.
edit("app/src/main/java/com/fadcam/SharedPreferencesManager.java", [
    (
        '    public void setWatermarkCustomText(String text) {\n        sharedPreferences.edit()\n            .putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, text != null ? text : "")\n            .apply();\n    }\n',
        '    public void setWatermarkCustomText(String text) {\n        sharedPreferences.edit()\n            .putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, text != null ? text : "")\n            .apply();\n    }\n\n'
        '    /** Returns the producer-selected watermark logo content URI, or null when unset. */\n'
        '    public String getWatermarkLogoUri() {\n'
        '        return sharedPreferences.getString(Constants.PREF_WATERMARK_LOGO_URI, null);\n'
        '    }\n\n'
        '    /** Persists the producer-selected watermark logo content URI. */\n'
        '    public void setWatermarkLogoUri(String uriString) {\n'
        '        SharedPreferences.Editor editor = sharedPreferences.edit();\n'
        '        if (uriString == null || uriString.trim().isEmpty()) {\n'
        '            editor.remove(Constants.PREF_WATERMARK_LOGO_URI);\n'
        '        } else {\n'
        '            editor.putString(Constants.PREF_WATERMARK_LOGO_URI, uriString);\n'
        '        }\n'
        '        editor.apply();\n'
        '    }\n\n'
        '    /** Logo size preset used by the burned-in renderer. */\n'
        '    public String getWatermarkLogoSize() {\n'
        '        String size = sharedPreferences.getString(Constants.PREF_WATERMARK_LOGO_SIZE, "medium");\n'
        '        if (!"small".equals(size) && !"medium".equals(size) && !"large".equals(size)) return "medium";\n'
        '        return size;\n'
        '    }\n\n'
        '    public void setWatermarkLogoSize(String size) {\n'
        '        String safe = ("small".equals(size) || "large".equals(size)) ? size : "medium";\n'
        '        sharedPreferences.edit().putString(Constants.PREF_WATERMARK_LOGO_SIZE, safe).apply();\n'
        '    }\n\n'
        '    /** Logo opacity, clamped to a visible 10..255 range. */\n'
        '    public int getWatermarkLogoOpacity() {\n'
        '        int opacity = sharedPreferences.getInt(Constants.PREF_WATERMARK_LOGO_OPACITY, 255);\n'
        '        return Math.max(10, Math.min(255, opacity));\n'
        '    }\n\n'
        '    public void setWatermarkLogoOpacity(int opacity) {\n'
        '        sharedPreferences.edit().putInt(Constants.PREF_WATERMARK_LOGO_OPACITY, Math.max(10, Math.min(255, opacity))).apply();\n'
        '    }\n'
    ),
])

# 3) Extend the provider contract without breaking existing implementations.
edit("app/src/main/java/com/fadcam/opengl/WatermarkInfoProvider.java", [
    (
        '    String getWatermarkText();\n',
        '    String getWatermarkText();\n\n'
        '    /** Optional producer logo URI. Implementations may return null when no logo is configured. */\n'
        '    default String getWatermarkLogoUri() {\n'
        '        return null;\n'
        '    }\n'
    ),
])

# 4) Make the real burned-in watermark pipeline consume the producer logo.
edit("app/src/main/java/com/fadcam/watermark/WatermarkManager.java", [
    (
        '        finalText += getExtendedSensorData();\n',
        '        finalText += getExtendedSensorData();\n\n'
        '        // Producer branding is part of the encoded watermark, not merely the settings preview.\n'
        '        // The GL renderer resolves the persisted content URI on its own render context.\n'
        '        if (getWatermarkLogoUri() != null && !getWatermarkLogoUri().isEmpty()\n'
        '                && !"no_watermark".equals(watermarkOption)) {\n'
        '            finalText = "<CUSTOM_LOGO> " + finalText;\n'
        '        }\n'
    ),
    (
        '    // ── Timestamp helpers ─────────────────────────────────────────────\n',
        '    @Override\n'
        '    public String getWatermarkLogoUri() {\n'
        '        return prefs.getWatermarkLogoUri();\n'
        '    }\n\n'
        '    // ── Timestamp helpers ─────────────────────────────────────────────\n'
    ),
])

# 5) Pass the provider logo URI to GL whenever the text is refreshed.
edit("app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java", [
    (
        '                        String initial = watermarkInfoProvider.getWatermarkText();\n                        glRenderer.setWatermarkText(initial != null ? initial : "");\n',
        '                        String initial = watermarkInfoProvider.getWatermarkText();\n'
        '                        glRenderer.setCustomLogoUri(watermarkInfoProvider.getWatermarkLogoUri());\n'
        '                        glRenderer.setWatermarkText(initial != null ? initial : "");\n'
    ),
    (
        '        final String text = watermarkInfoProvider.getWatermarkText();\n',
        '        final String text = watermarkInfoProvider.getWatermarkText();\n'
        '        final String logoUri = watermarkInfoProvider.getWatermarkLogoUri();\n'
    ),
    (
        '                    glRenderer.updateWatermarkTextOnGlThread(text != null ? text : "");\n',
        '                    glRenderer.setCustomLogoUri(logoUri);\n'
        '                    glRenderer.updateWatermarkTextOnGlThread(text != null ? text : "");\n'
    ),
])

# 6) Render the selected logo into the actual encoded video frames.
edit("app/src/main/java/com/fadcam/opengl/GLWatermarkRenderer.java", [
    (
        '    private String watermarkText = "";\n',
        '    private String watermarkText = "";\n'
        '    private String customLogoUri = null;\n'
        '    private String loadedCustomLogoUri = null;\n'
        '    private Bitmap customLogoBitmap = null;\n'
    ),
    (
        '    public void setWatermarkText(String text) {\n        applyWatermarkAndOverlayPayload(text);\n    }\n',
        '    public void setWatermarkText(String text) {\n        applyWatermarkAndOverlayPayload(text);\n    }\n\n'
        '    /** Sets the producer logo content URI. The bitmap is loaded lazily on the GL thread. */\n'
        '    public void setCustomLogoUri(@Nullable String uri) {\n'
        '        String normalized = (uri == null || uri.trim().isEmpty()) ? null : uri.trim();\n'
        '        if (java.util.Objects.equals(customLogoUri, normalized)) return;\n'
        '        customLogoUri = normalized;\n'
        '        if (customLogoBitmap != null) {\n'
        '            try { customLogoBitmap.recycle(); } catch (Exception ignored) {}\n'
        '            customLogoBitmap = null;\n'
        '        }\n'
        '        loadedCustomLogoUri = null;\n'
        '    }\n'
    ),
    (
        '        boolean watermarkChanged = !watermarkOnlyText.equals(this.watermarkText);\n',
        '        boolean watermarkChanged = !watermarkOnlyText.equals(this.watermarkText);\n'
        '        // Logo changes must also invalidate the rasterized watermark texture.\n'
        '        if (customLogoUri != null && !customLogoUri.equals(loadedCustomLogoUri)) watermarkChanged = true;\n'
    ),
    (
        '            boolean hasIcon = l != null && (l.contains("<ICON>") || l.contains("<FADCAM_ICON>"));\n',
        '            boolean hasIcon = l != null && (l.contains("<ICON>") || l.contains("<FADCAM_ICON>") || l.contains("<CUSTOM_LOGO>"));\n'
    ),
    (
        '            float lineW = watermarkPaint.measureText(line.replace("<ICON>", "").replace("<FADCAM_ICON>", ""));\n',
        '            float lineW = watermarkPaint.measureText(line.replace("<ICON>", "").replace("<FADCAM_ICON>", "").replace("<CUSTOM_LOGO>", ""));\n'
        '            if (line.contains("<CUSTOM_LOGO>")) {\n'
        '                lineW += measureCustomLogoWidth(iconLineH) + (padding * 0.25f);\n'
        '            }\n'
    ),
    (
        '        if (flippedWatermarkBitmap == null\n',
        '        loadedCustomLogoUri = customLogoUri;\n'
        '        if (flippedWatermarkBitmap == null\n'
    ),
    (
        '    private float measureIconWidth(String drawableName, float targetH) {\n',
        '    private float measureCustomLogoWidth(float targetH) {\n'
        '        Bitmap bmp = getCustomLogoBitmap();\n'
        '        if (bmp == null || bmp.getHeight() <= 0) return 0f;\n'
        '        return targetH * ((float) bmp.getWidth() / (float) bmp.getHeight());\n'
        '    }\n\n'
        '    private Bitmap getCustomLogoBitmap() {\n'
        '        if (customLogoUri == null || customLogoUri.isEmpty()) return null;\n'
        '        if (customLogoBitmap != null && customLogoUri.equals(loadedCustomLogoUri)) return customLogoBitmap;\n'
        '        try {\n'
        '            android.net.Uri uri = android.net.Uri.parse(customLogoUri);\n'
        '            java.io.InputStream in = context.getContentResolver().openInputStream(uri);\n'
        '            if (in == null) return null;\n'
        '            Bitmap decoded;\n'
        '            try { decoded = BitmapFactory.decodeStream(in); } finally { in.close(); }\n'
        '            if (decoded == null) return null;\n'
        '            int max = 1024;\n'
        '            float scale = Math.min(1f, Math.min(max / (float) decoded.getWidth(), max / (float) decoded.getHeight()));\n'
        '            customLogoBitmap = scale < 1f\n'
        '                    ? Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)), Math.max(1, Math.round(decoded.getHeight() * scale)), true)\n'
        '                    : decoded;\n'
        '            if (customLogoBitmap != decoded) decoded.recycle();\n'
        '            loadedCustomLogoUri = customLogoUri;\n'
        '            return customLogoBitmap;\n'
        '        } catch (Exception e) {\n'
        '            FLog.w(TAG, "Unable to load producer watermark logo: " + e.getMessage());\n'
        '            loadedCustomLogoUri = customLogoUri;\n'
        '            return null;\n'
        '        }\n'
        '    }\n\n'
        '    private float measureIconWidth(String drawableName, float targetH) {\n'
    ),
    (
        '            if (line.contains("<ICON>") || line.contains("<FADCAM_ICON>")) {\n',
        '            if (line.contains("<ICON>") || line.contains("<FADCAM_ICON>") || line.contains("<CUSTOM_LOGO>")) {\n'
    ),
    (
        '            int idx1 = remaining.indexOf("<ICON>");\n            int idx2 = remaining.indexOf("<FADCAM_ICON>");\n',
        '            int idx1 = remaining.indexOf("<ICON>");\n            int idx2 = remaining.indexOf("<FADCAM_ICON>");\n            int idx3 = remaining.indexOf("<CUSTOM_LOGO>");\n'
    ),
    (
        '            boolean hasFadcam = idx2 >= 0;\n            if (!hasIcon && !hasFadcam) {\n',
        '            boolean hasFadcam = idx2 >= 0;\n            boolean hasCustomLogo = idx3 >= 0;\n            if (!hasIcon && !hasFadcam && !hasCustomLogo) {\n'
    ),
    (
        '            int first = hasIcon && hasFadcam ? Math.min(idx1, idx2)\n                    : (hasIcon ? idx1 : idx2);\n            boolean firstIsFadrec = hasIcon && (!hasFadcam || idx1 <= idx2);\n',
        '            int first = Integer.MAX_VALUE;\n            if (hasIcon) first = Math.min(first, idx1);\n            if (hasFadcam) first = Math.min(first, idx2);\n            if (hasCustomLogo) first = Math.min(first, idx3);\n            boolean firstIsFadrec = hasIcon && idx1 == first;\n            boolean firstIsFadcam = hasFadcam && idx2 == first;\n'
    ),
    (
        '            String token = firstIsFadrec ? "<ICON>" : "<FADCAM_ICON>";\n            tokens.add(token);\n            tokenIsFadrec.add(firstIsFadrec);\n',
        '            String token = firstIsFadrec ? "<ICON>" : (firstIsFadcam ? "<FADCAM_ICON>" : "<CUSTOM_LOGO>");\n            tokens.add(token);\n            tokenIsFadrec.add(firstIsFadrec);\n'
    ),
    (
        '                String drawableName = isFadrec ? "fadrec" : "menu_icon_unknown";\n                android.graphics.Bitmap iconBmp = getIconBitmap(drawableName);\n',
        '                android.graphics.Bitmap iconBmp = isFadrec ? getIconBitmap("fadrec")\n'
        '                        : ("<FADCAM_ICON>".equals(tok) ? getIconBitmap("menu_icon_unknown") : getCustomLogoBitmap());\n'
    ),
    (
        '                    currentX += iconW + (padding * 0.25f);\n',
        '                    currentX += iconW + (padding * 0.25f);\n'
    ),
    (
        '    public void release() {\n',
        '    public void release() {\n'
        '        if (customLogoBitmap != null) {\n'
        '            try { customLogoBitmap.recycle(); } catch (Exception ignored) {}\n'
        '            customLogoBitmap = null;\n'
        '        }\n'
        '        customLogoUri = null;\n'
        '        loadedCustomLogoUri = null;\n'
    ),
])

# 7) Add a producer-facing logo picker to Watermark settings.
edit("app/src/main/java/com/fadcam/ui/WatermarkSettingsFragment.java", [
    (
        '    private ActivityResultLauncher<String> permissionLauncher;\n',
        '    private ActivityResultLauncher<String> permissionLauncher;\n'
        '    private ActivityResultLauncher<android.content.Intent> logoPickerLauncher;\n'
    ),
    (
        '        valueLocationInterval = view.findViewById(R.id.value_location_interval);\n',
        '        valueLocationInterval = view.findViewById(R.id.value_location_interval);\n'
        '        valueProducerLogo = view.findViewById(R.id.value_producer_logo);\n'
    ),
    (
        '    private View locationRow;\n',
        '    private View locationRow;\n'
        '    private TextView valueProducerLogo;\n'
    ),
    (
        '        View rowCustomText = view.findViewById(R.id.row_custom_text);\n',
        '        View rowProducerLogo = view.findViewById(R.id.row_producer_logo);\n'
        '        if (rowProducerLogo != null) rowProducerLogo.setOnClickListener(v -> showProducerLogoPicker());\n'
        '\n'
        '        View rowCustomText = view.findViewById(R.id.row_custom_text);\n'
    ),
    (
        '        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {\n',
        '        logoPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {\n'
        '            if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;\n'
        '            android.net.Uri uri = result.getData().getData();\n'
        '            if (uri == null) return;\n'
        '            try {\n'
        '                final int takeFlags = result.getData().getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;\n'
        '                if (takeFlags != 0) requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);\n'
        '            } catch (Exception ignored) {\n'
        '                // Some providers do not expose persistable permissions; the current grant is still usable.\n'
        '            }\n'
        '            prefs.setWatermarkLogoUri(uri.toString());\n'
        '            refreshProducerLogoValue();\n'
        '            updatePreview();\n'
        '            Toast.makeText(requireContext(), "Producer logo applied to recordings", Toast.LENGTH_SHORT).show();\n'
        '        });\n\n'
        '        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {\n'
    ),
    (
        '    refreshCustomTextValue();\n',
        '    refreshCustomTextValue();\n    refreshProducerLogoValue();\n'
    ),
    (
        '    private void showWatermarkStyleBottomSheet(){\n',
        '    private void refreshProducerLogoValue() {\n'
        '        if (valueProducerLogo == null || prefs == null) return;\n'
        '        valueProducerLogo.setText((prefs.getWatermarkLogoUri() == null || prefs.getWatermarkLogoUri().isEmpty())\n'
        '                ? "Not set" : "Custom logo ready");\n'
        '    }\n\n'
        '    private void showProducerLogoPicker() {\n'
        '        String current = prefs.getWatermarkLogoUri();\n'
        '        if (current != null && !current.isEmpty()) {\n'
        '            new AlertDialog.Builder(requireContext())\n'
        '                    .setTitle("Producer watermark logo")\n'
        '                    .setItems(new String[]{"Replace logo", "Remove logo", "Cancel"}, (dialog, which) -> {\n'
        '                        if (which == 0) launchProducerLogoPicker();\n'
        '                        else if (which == 1) {\n'
        '                            prefs.setWatermarkLogoUri(null);\n'
        '                            refreshProducerLogoValue();\n'
        '                            updatePreview();\n'
        '                        }\n'
        '                    }).show();\n'
        '        } else {\n'
        '            launchProducerLogoPicker();\n'
        '        }\n'
        '    }\n\n'
        '    private void launchProducerLogoPicker() {\n'
        '        if (logoPickerLauncher == null) return;\n'
        '        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);\n'
        '        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);\n'
        '        intent.setType("image/*");\n'
        '        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION\n'
        '                | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);\n'
        '        logoPickerLauncher.launch(intent);\n'
        '    }\n\n'
        '    private void showWatermarkStyleBottomSheet(){\n'
    ),
])

# 8) Add the row to the existing watermark settings card.
edit("app/src/main/res/layout/fragment_settings_watermark.xml", [
    (
        '                <View style="@style/SettingsDivider" />\n\n                <View style="@style/SettingsDivider" />\n\n                <!-- Timezone Toggle Row -->\n',
        '                <View style="@style/SettingsDivider" />\n\n'
        '                <!-- Producer Branding Logo Row -->\n'
        '                <LinearLayout\n'
        '                    android:id="@+id/row_producer_logo"\n'
        '                    style="@style/SettingsGroupRow">\n'
        '                    <ImageView\n'
        '                        android:layout_width="24dp"\n'
        '                        android:layout_height="24dp"\n'
        '                        android:layout_marginEnd="16dp"\n'
        '                        android:src="@drawable/ic_draw_edit"\n'
        '                        android:tint="@android:color/darker_gray"/>\n'
        '                    <LinearLayout\n'
        '                        android:layout_width="0dp"\n'
        '                        android:layout_height="wrap_content"\n'
        '                        android:layout_weight="1"\n'
        '                        android:orientation="vertical">\n'
        '                        <TextView\n'
        '                            android:layout_width="wrap_content"\n'
        '                            android:layout_height="wrap_content"\n'
        '                            android:text="@string/watermark_producer_logo_title"\n'
        '                            android:textStyle="bold"\n'
        '                            android:textSize="15sp"\n'
        '                            android:textColor="?attr/colorHeading"/>\n'
        '                        <TextView\n'
        '                            android:id="@+id/value_producer_logo"\n'
        '                            android:layout_width="wrap_content"\n'
        '                            android:layout_height="wrap_content"\n'
        '                            android:text="@string/watermark_producer_logo_not_set"\n'
        '                            android:textColor="@android:color/darker_gray"\n'
        '                            android:textSize="12sp"\n'
        '                            android:maxLines="1"\n'
        '                            android:ellipsize="end"/>\n'
        '                    </LinearLayout>\n'
        '                    <ImageView\n'
        '                        android:layout_width="14dp"\n'
        '                        android:layout_height="14dp"\n'
        '                        android:src="@drawable/ic_arrow_right"\n'
        '                        android:tint="@android:color/darker_gray"/>\n'
        '                </LinearLayout>\n'
        '                <View style="@style/SettingsDivider" />\n\n'
        '                <!-- Timezone Toggle Row -->\n'
    ),
])

# 9) Strings required by the new settings row.
strings = ROOT / "app/src/main/res/values/strings.xml"
text = strings.read_text(encoding="utf-8")
anchor = '</resources>'
if anchor not in text:
    raise SystemExit("PATCH ANCHOR NOT FOUND: strings.xml </resources>")
if 'name="watermark_producer_logo_title"' in text:
    raise SystemExit("Producer watermark strings already exist")
text = text.replace(
    anchor,
    '    <string name="watermark_producer_logo_title">Producer Watermark Logo</string>\n'
    '    <string name="watermark_producer_logo_not_set">Not set</string>\n'
    '    <string name="watermark_producer_logo_ready">Custom logo ready</string>\n'
    '</resources>',
    1,
)
strings.write_text(text, encoding="utf-8")

print("Producer watermark branding patch applied successfully.")
