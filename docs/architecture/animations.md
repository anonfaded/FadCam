# Animation Components

Canvas previews are built from small, reusable scenes instead of one giant
custom view. One shared clock drives every preview on screen.

## Layout

```
app/src/main/java/com/fadcam/ui/animations/
├── AnimationClock.java   one Choreographer clock, shared by all views
├── AnimationView.java    thin host: scales the scene, gates lifecycle, theming
├── CanvasKit.java        paints, paths and drawing primitives
├── Scene.java            contract: design size + draw + reset
├── SceneFrame.java       per-frame progress, ambient phase, time, style
├── SceneStyle.java       palette and chrome switches
├── Scenes.java           scene-type registry for XML hosts
├── scenes/               one file per preview
└── parts/                reusable pieces: PhoneShell, LoungeRoom, RoadView, NightSky, Walkers
```

## Using a scene

XML:

```xml
<com.fadcam.ui.animations.AnimationView
    android:layout_width="match_parent"
    android:layout_height="160dp"
    app:sceneType="motion" />
```

Programmatic, including theming and a scene swap:

```java
AnimationView view = new AnimationView(context);
view.setScene(new DashcamScene());
view.setStyle(SceneStyle.defaults().toBuilder()
        .accent(0xFF7BD1E8)
        .build());
view.playOnce(0);
view.setAmbientEnabled(true);
```

Call `setActive(false)` when the host page is hidden and `resetDemo()` when it
should replay on the next visit. `setRevealDuration` and `setAmbientPeriod`
change pacing without touching the scene.

## Scene contract

- Draw only inside `designWidth() x designHeight()`; the host handles scaling.
- Never read the view size or wall clock. Use `SceneFrame.progress` for the
  one-shot reveal and `SceneFrame.phase` / `timeMs` for the ambient loop.
- Do not allocate in `draw()`. Reuse `CanvasKit` paints, rects and paths.
- Clear counters and timers in `reset()`.
- Take colours from `SceneFrame.style` where the palette should be themeable.

## Performance rules

- **One clock.** `AnimationClock` is a single VSYNC callback for every preview.
  Do not add per-view `ValueAnimator`s.
- **Pause with the app.** `AnimationClock` tracks activity start/stop, so
  previews stop when the app goes to the background or the screen turns off.
  It also stops when the last listener detaches.
- **Stop off-screen.** `AnimationView` unsubscribes when it is detached, when
  the window is hidden, when its page is inactive, or when it scrolls out of
  the viewport.
- **Honour reduced motion.** When `ANIMATOR_DURATION_SCALE` is zero the view
  draws the final frame and never subscribes.
- **Keep scenes cheap.** Prefer solid paths and small gradients over blurs,
  layers or per-frame bitmaps. The stage grid and corner ticks can be disabled
  through `SceneStyle` when a host does not need the dossier frame.

## Adding a scene

1. Implement `Scene` in `scenes/` and declare its design size.
2. Register it in `Scenes` and add an enum value to
   `res/values/attrs_animation_view.xml` if XML should create it.
3. Reuse `parts/` for phones, rooms, roads, night skies and walkers. If a new
   visual repeats in two scenes, extract it as a part instead of copying.
4. Add the new preview to `docs/home.md` only when it becomes a user-facing
   feature, not for every internal scene.

## Payoff icon

`PayoffFlowView` draws the whole payoff in one view: the soft floor glow, the
two stationary golden streams, the two slow particles, and then the icon.

The icon is a stable full-bleed 432px PNG (`drawable-nodpi/payoff_icon.png`)
with a Lite override, built from that edition's launcher icon. Do not use
`AdaptiveIconDrawable` here and do not crop the adaptive foreground by hand:
the two editions have different content boxes, which is what caused a gap on
Lite and an off-centre crop on Full.

The icon bitmap is drawn through a `BitmapShader` into the same rectangle and
corner radius used by the gold frame stroke, so the icon always fills the frame
edge to edge and cannot drift. A soft gold aura is baked around that rectangle
in the same static bitmap as the streams. The floor is a two-layer radial
gradient centred on the icon by its shader matrix, and the frame gradient is
vertical rather than diagonal so the frame does not look brighter on one side.
There are no feature chips, no platform and no per-visit reveal.
