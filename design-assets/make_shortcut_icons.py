#!/usr/bin/env python3

# This script makes png icons with text labels left aligned. For examples, check the Start Back, Start Front, Start Current and FadShot icons in drawable folder.

# Requirements: 192x192px png icons (will be layed on top of a matte black bg)

from PIL import Image, ImageDraw, ImageFont
import os

DRAWABLE_DIR = "/Users/faded/Documents/repos/github/FadCam/app/src/main/res/drawable"
DOWNLOADS_DIR = os.path.expanduser("~/Downloads/fadcam_shortcuts_icons")

ICONS = {
    "fadshot_shortcut": {
        "png": "photo_camera_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (255, 255, 255),  # white
        "label": "PHOTO"
    },
    "fadshot_front_shortcut": {
        "png": "photo_camera_front_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (255, 255, 255),  # white
        "label": "SELFIE"
    },
    "start_back_shortcut": {
        "png": "video_camera_back_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (52, 152, 219),  # blue
        "label": "BACK"
    },
    "start_front_shortcut": {
        "png": "video_camera_front_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (155, 89, 182),  # purple
        "label": "FRONT"
    },
    "start_current_shortcut": {
        "png": "videocam_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (46, 204, 113),  # green
        "label": "CURRENT"
    },
    "stop_shortcut": {
        "png": "back_hand_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (231, 76, 60),  # red
        "label": "STOP"
    },
    "flashlight_shortcut": {
        "png": "flashlight_on_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (241, 196, 15),  # orange/yellow
        "label": "TORCH"
    },
    "start_dual_shortcut": {
        "png": "switch_camera_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (0, 188, 212),  # cyan
        "label": "DUAL"
    },
    "fadrec_screenshot_shortcut": {
        "png": "mobile_camera_110dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png",
        "color": (241, 196, 15),  # orange/yellow
        "label": "FADREC"
    }
}

SIZE = 192
BG_COLOR = (20, 20, 20)  # #1a1a1a matte black
ICON_SIZE = 110
LABEL_FONT_SIZE = 24  # Bigger font
LEFT_PADDING = 16
LABEL_BOTTOM_OFFSET = 45  # Distance from bottom (higher number = closer to icon)

os.chdir(DRAWABLE_DIR)

print("Creating sharp icons with text labels...")
print()

for icon_name, config in ICONS.items():
    png_path = os.path.join(DOWNLOADS_DIR, config['png'])
    output_path = f"{icon_name}.png"
    color_rgb = config['color']
    label = config['label']
    
    # Create matte black RGB background
    bg = Image.new('RGB', (SIZE, SIZE), BG_COLOR)
    
    # Load icon as RGBA
    icon = Image.open(png_path).convert('RGBA')
    
    # Colorize the icon
    pixels = icon.load()
    for y in range(icon.height):
        for x in range(icon.width):
            r, g, b, a = pixels[x, y]
            # If not fully transparent, apply the color with original alpha
            if a > 0:
                pixels[x, y] = (*color_rgb, a)
    
    # Composite colored icon onto background
    icon_x = (SIZE - ICON_SIZE) // 2
    icon_y = 32
    bg.paste(icon, (icon_x, icon_y), icon)
    
    # Add text label
    draw = ImageDraw.Draw(bg)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", LABEL_FONT_SIZE)
    except:
        try:
            font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", LABEL_FONT_SIZE)
        except:
            font = ImageFont.load_default()
    
    # Calculate text position (left-aligned, near bottom)
    text_x = LEFT_PADDING
    text_y = SIZE - LABEL_BOTTOM_OFFSET
    
    # Draw label
    draw.text((text_x, text_y), label, fill=color_rgb, font=font)
    
    # Save as RGB PNG
    bg.save(output_path, 'PNG')
    
    print(f"✓ Created {output_path} - {label} ({color_rgb})")

print()
print("✅ All icons created with labels!")
print()

# Verify
import subprocess
subprocess.run(['ls', '-lh', 'fadshot_shortcut.png', 'fadshot_front_shortcut.png', 'start_back_shortcut.png', 'start_front_shortcut.png', 'start_current_shortcut.png', 'stop_shortcut.png', 'flashlight_shortcut.png', 'start_dual_shortcut.png', 'fadrec_screenshot_shortcut.png'])
print()
subprocess.run(['file', 'fadshot_shortcut.png', 'fadshot_front_shortcut.png', 'start_back_shortcut.png', 'start_front_shortcut.png', 'start_current_shortcut.png', 'stop_shortcut.png', 'flashlight_shortcut.png', 'start_dual_shortcut.png', 'fadrec_screenshot_shortcut.png'])
