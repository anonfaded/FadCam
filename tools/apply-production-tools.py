from pathlib import Path

marker = 'PRODUCTION_TOOLS_WIRED_V1'

p = Path('app/src/main/java/com/fadcam/ui/SettingsHomeFragment.java')
s = p.read_text()
if marker not in s:
    replacements = {
'''bindRow(root, R.id.group_thermal_guardian, () -> {\n            android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show();\n        });''': '''bindRow(root, R.id.group_thermal_guardian, () -> openProductionTool("thermal_guardian"));''',
'''bindRow(root, R.id.group_audio_vision, () -> {\n            android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show();\n        });''': '''bindRow(root, R.id.group_audio_vision, () -> openProductionTool("audio_vision"));''',
'''bindRow(root, R.id.group_scheduled_recording, () -> {\n            android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show();\n        });''': '''bindRow(root, R.id.group_scheduled_recording, () -> openProductionTool("scheduled_recording"));''',
'''bindRow(root, R.id.group_profiles, () -> {\n            android.widget.Toast.makeText(requireContext(), R.string.mini_app_coming_soon_desc, android.widget.Toast.LENGTH_SHORT).show();\n        });''': '''bindRow(root, R.id.group_profiles, () -> openProductionTool("profiles"));''',
    }
    for old, new in replacements.items():
        if old not in s:
            raise SystemExit('Missing SettingsHome replacement')
        s = s.replace(old, new, 1)
    s = s.replace('R.string.mini_app_coming_soon,', '0,')
    hook = '''    private void openProductionTool(String feature) {\n        try {\n            OverlayNavUtil.show(requireActivity(),\n                    ProductionToolsFragment.newInstance(feature),\n                    "production_tool_" + feature);\n        } catch (Exception e) {\n            FLog.w("SettingsHome", "Failed to open production tool: " + feature, e);\n        }\n    }\n\n'''
    idx = s.find('    private void bindRow(View root, int id, Runnable action)')
    if idx < 0:
        raise SystemExit('bindRow marker not found')
    s = s[:idx] + hook + s[idx:]
    s = s.replace('public class SettingsHomeFragment extends Fragment {', 'public class SettingsHomeFragment extends Fragment {\n    // ' + marker, 1)
    p.write_text(s)

p = Path('app/src/main/java/com/fadcam/ui/HomeSidebarFragment.java')
s = p.read_text()
if marker not in s:
    old = '    public static void showMiniAppComingSoon(Fragment fragment, String appId) {\n        try {'
    new = '''    public static void showMiniAppComingSoon(Fragment fragment, String appId) {\n        // ''' + marker + '''\n        if ("compass".equals(appId) || "sound_meter".equals(appId) ||\n                "sensor_dashboard".equals(appId) || "speedometer".equals(appId) ||\n                "clinometer".equals(appId) || "pedometer".equals(appId) ||\n                "metal_detector".equals(appId) || "parking_marker".equals(appId) ||\n                "qr_generator".equals(appId)) {\n            try {\n                OverlayNavUtil.show(fragment.requireActivity(),\n                        ProductionToolsFragment.newInstance(appId),\n                        "production_tool_" + appId);\n                return;\n            } catch (Exception e) {\n                FLog.w("HomeSidebar", "Failed to open production tool: " + appId, e);\n            }\n        }\n        try {'''
    if old not in s:
        raise SystemExit('HomeSidebar method marker not found')
    s = s.replace(old, new, 1)
    old2 = '    private void showProfilesComingSoon() {\n        try {'
    new2 = '''    private void showProfilesComingSoon() {\n        try {\n            OverlayNavUtil.show(requireActivity(), ProductionToolsFragment.newInstance("profiles"), "production_tool_profiles");\n            return;\n        } catch (Exception e) {\n            FLog.w("HomeSidebar", "Failed to open Profiles", e);\n        }\n        try {'''
    if old2 not in s:
        raise SystemExit('Profiles method marker not found')
    s = s.replace(old2, new2, 1)
    p.write_text(s)

p = Path('app/src/main/AndroidManifest.xml')
s = p.read_text()
if 'SCHEDULE_EXACT_ALARM' not in s:
    s = s.replace('<uses-permission android:name="android.permission.WAKE_LOCK" />', '<uses-permission android:name="android.permission.WAKE_LOCK" />\n    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />', 1)
if 'ProductionScheduleReceiver' not in s:
    s = s.replace('    </application>', '        <receiver android:name=".ui.ProductionScheduleReceiver" android:exported="false" />\n\n    </application>', 1)
p.write_text(s)

p = Path('app/src/main/res/layout/fragment_settings_home.xml')
s = p.read_text()
for ident in ['thermal_guardian_coming_soon_badge', 'audio_vision_coming_soon_badge', 'scheduled_recording_coming_soon_badge', 'profiles_coming_soon_badge']:
    token = f'android:id="@+id/{ident}"'
    pos = s.find(token)
    if pos >= 0:
        end = s.find('/>', pos)
        block = s[pos:end]
        if 'android:visibility=' not in block:
            s = s[:end] + '\n                            android:visibility="gone"' + s[end:]
p.write_text(s)

p = Path('README.md')
s = p.read_text()
if 'Production tools (now live)' not in s:
    needle = '## 🌐 Streaming\n'
    section = '''## 🧰 Production tools (now live)\n\nThe Settings **Coming Soon** tools are now implemented and wired into the recording workflow:\n\n- Thermal Guardian — live battery-temperature monitoring with a configurable recording safety cutoff\n- Audio Vision — microphone threshold detection with automatic recording start\n- Scheduled Recording — one-shot exact alarms with automatic recording stop\n- Profiles — save/load/delete producer recording presets\n- Sound Meter — live microphone dB meter\n- Sensor Dashboard — accelerometer, magnetometer, gyroscope and step-sensor availability\n- Speedometer — GPS speed in km/h\n- Clinometer — live device tilt angle\n- Compass — sensor-fused heading\n- Pedometer — hardware step counter\n- Metal Detector — magnetic-field strength meter\n- Parking Marker — save the current GPS position and open navigation\n- QR Generator — generate production links/text as QR codes\n\nThe rolling APK link above is updated only by the verification workflow after the release build, tests, APK integrity, signature and alignment gates pass.\n\n'''
    if needle not in s:
        raise SystemExit('README insertion point not found')
    p.write_text(s.replace(needle, section + needle, 1))
