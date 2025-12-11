# 👁️ Interactive Avatar Component

> **Created by [Faded](https://github.com/anonfaded) | December 9, 2025**

A standalone avatar with mouse-following eyes that change color based on proximity. Zero dependencies, pure vanilla JS.

![Pure Vanilla JS](https://img.shields.io/badge/Pure-Vanilla%20JS-yellow?style=flat-square)
![Zero Dependencies](https://img.shields.io/badge/Dependencies-0-green?style=flat-square)
![Size](https://img.shields.io/badge/Size-~15KB-blue?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-orange?style=flat-square)

## ⚡ Features

| Feature | Description |
|---------|-------------|
| 👁️ **Eye Tracking** | Smooth mouse following with realistic movement |
| 🎨 **Color Morphing** | White (near) → Red (far) based on distance |
| 😊 **Auto Blinking** | Random intervals (3-7s) for natural feel |
| 🌊 **3D Float** | Subtle floating animation with depth |
| 📱 **Touch Ready** | Full mobile/tablet support |
| ♿ **Accessible** | Respects `prefers-reduced-motion` |
| 🚀 **Optimized** | GPU-accelerated, 60fps, <15KB total |
| 📦 **Portable** | 3 files: JS + CSS + HTML demo |

## 🚀 Quick Setup

### Copy & Paste Integration

```html
<!-- 1. Add CSS to <head> -->
<link rel="stylesheet" href="path/to/avatar.css">

<!-- 2. Add JS before </body> -->
<script src="path/to/avatar.js"></script>

<!-- 3. Initialize -->
<script>
  document.addEventListener('DOMContentLoaded', () => new Avatar());
</script>
```

**That's it!** Avatar appears bottom-right, fully functional.

---

## 📚 API Reference

### Initialization
```javascript
const avatar = new Avatar();  // Creates and shows avatar
```

### Methods

| Method | Parameters | Description |
|--------|-----------|-------------|
| `toggle()` | `visible?` (boolean) | Show/hide avatar |
| `setPosition()` | `'bottom-right'` \| `'bottom-left'` \| `'top-right'` \| `'top-left'` | Change position |
| `enableDebugMode()` | - | Show tracking overlay |
| `destroy()` | - | Remove avatar & cleanup |

### Examples

```javascript
// Toggle visibility
avatar.toggle();        // Switch on/off
avatar.toggle(true);    // Force show
avatar.toggle(false);   // Force hide

// Reposition
avatar.setPosition('top-left');

// Debug tracking info
avatar.enableDebugMode();

// Cleanup (removes from DOM)
avatar.destroy();
```

---

## 🎨 How It Works

### Eye Color Logic
```
Distance < 100px  → White eyes (close to avatar)
Distance > 500px  → Red eyes (far from avatar)
100-500px range   → Gradient transition
```

Calculation happens in `updateEyeColors()` method in `avatar.js`.

### Eye Movement
- Uses `Math.atan2()` for angle calculation
- Smooth interpolation (lerp factor: 0.12) prevents jittery movement
- `requestAnimationFrame` for 60fps updates
- Independent left/right eye tracking for depth

### Blinking
- Random intervals: 3000-7000ms
- CSS transition: `cubic-bezier(0.4, 0, 0.2, 1)`
- Height animation: 16px → 1px → 16px (120ms each)

---

## 🔧 Customization

### Change Colors

**avatar.css**
```css
/* Avatar body gradient */
.avatar-head {
  background: linear-gradient(to bottom right, #1f2937, #000000, #1f2937);
}

/* Eye socket */
.eye-socket { background: #000000; }
```

**avatar.js** (eye color thresholds)
```javascript
// In updateEyeColors() method (line ~200)
const minDistance = 100;  // White threshold (decrease for closer trigger)
const maxDistance = 500;  // Red threshold (increase for longer range)
```

### Resize Avatar

```css
.avatar-head  { width: 8rem; height: 8rem; }  /* Default: 6rem */
.avatar-body  { width: 9rem; height: 5rem; }  /* Default: 7rem × 4rem */
.eye-socket   { width: 2rem; height: 1.2rem; } /* Default: 1.5rem × 1rem */
```

### Adjust Animation Speed

```css
/* Float animation */
.avatar-head {
  animation: float 3s ease-in-out infinite;  /* Slower: 5s, Faster: 2s */
}
```

```javascript
// Eye tracking responsiveness (avatar.js, line ~187)
const lerpFactor = 0.12;  // Higher = faster tracking (max: 1.0)
```

---

## 💡 Advanced Usage

### Conditional Loading
```javascript
if (window.innerWidth > 768) new Avatar(); // Desktop only
```

### Scroll-based Toggle
```javascript
const avatar = new Avatar();
window.addEventListener('scroll', () => {
  avatar.toggle(window.scrollY < 500); // Hide after scrolling 500px
});
```

### Framework Integration

**React**
```jsx
useEffect(() => {
  const avatar = new Avatar();
  return () => avatar.destroy();
}, []);
```

**Vue**
```javascript
mounted() {
  this.avatar = new Avatar();
},
beforeDestroy() {
  this.avatar.destroy();
}
```

**WordPress** (theme footer)
```php
<link rel="stylesheet" href="<?= get_template_directory_uri() ?>/avatar/avatar.css">
<script src="<?= get_template_directory_uri() ?>/avatar/avatar.js"></script>
<script>document.addEventListener('DOMContentLoaded', () => new Avatar());</script>
```

---

## 📱 Browser Support

✅ Chrome/Edge 90+ | ✅ Firefox 88+ | ✅ Safari 14+ | ✅ iOS Safari 14+ | ✅ Chrome Mobile

**IE11**: Not supported (uses ES6 classes, arrow functions, `const`/`let`)

---

## ⚙️ Technical Details

| Metric | Value |
|--------|-------|
| **Total Size** | ~15KB (unminified) |
| **JS Size** | ~10KB |
| **CSS Size** | ~5KB |
| **Performance** | 60fps constant |
| **Memory** | <5MB |
| **Event Listeners** | 3 (mousemove, touchmove, scroll) |

### Performance Optimizations
- `requestAnimationFrame` for smooth rendering
- `passive: true` listeners (non-blocking scroll)
- GPU acceleration via `transform` (not `top`/`left`)
- `will-change` hints for compositor
- Debounced scroll opacity changes

---

## 🐛 Troubleshooting

| Issue | Solution |
|-------|----------|
| Avatar not visible | Check z-index conflicts, verify files loaded in Network tab |
| Eyes not tracking | Open console, check for JS errors, ensure no `pointer-events: none` override |
| Poor performance | Disable debug mode, check for CSS animation conflicts |
| Scroll issues | Ensure no `overflow: hidden` on parent containers |

---

## 📂 File Structure

```
extracted_avatar/
├── avatar.js       # Core logic (385 lines)
├── avatar.css      # Styling (200 lines)
├── demo.html       # Interactive demo
└── README.md       # This file
```

---

## 📜 License

**MIT License** - Free for personal & commercial use. Attribution appreciated but not required.

---

## 🙏 Credits

Built by **[Faded](https://github.com/anonfaded)** | Extracted from [faded.dev](https://faded.dev)

---

**Questions?** Open an issue or fork the repo! Enjoy your avatar 👁️✨
