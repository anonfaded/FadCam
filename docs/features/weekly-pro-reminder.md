# FadCam Pro reminder (free builds)

## What it is

A weekly reminder for people using a free FadCam build. It shows a small in-app reminder
card that invites them to look at FadCam Pro. Pro and Pro+ builds never show it.

## When it appears

| Time | What happens |
|---|---|
| First 3 full days | Nothing. |
| After 3 days **and** one recording | The first reminder can appear on Home. |
| Every 7 days after CONTINUE | The next reminder can appear. |

The first reminder needs both things: 3 full days since install and at least one successful
recording. After that, the clock starts again each time the user presses CONTINUE.

## One cycle has two parts

1. A notification in the phone's notification tray.
2. The in-app reminder card on Home.

If the app is opened first, Home posts the notification before showing the card. Showing the
card does not remove the notification; the user can tap it or swipe it away.

Tapping the notification opens the Pro screen, but it does **not** finish the cycle. The card
still appears on the next safe Home visit. Only **CONTINUE** in the card finishes the cycle.

After CONTINUE, a short follow-up card appears when the user comes back to Home. Only
**GOT IT** closes that card.

There is no "never show again" button. Buying Pro is the only permanent way to stop it.

## Design mode (debug builds only)

Debug builds show the reminder card on every safe Home visit so the design can be worked on.
Design mode does not post notifications and does not start the 7-day clock. It is on by
default in debug builds. The setting is `pro_reminder_design_mode`; set it to `false` to
test the real timing. Release builds ignore it.

## Where it lives

- Main logic: `app/src/main/java/com/fadcam/pro/ProReminderManager.java`
- Alarm: `ProReminderScheduler.java`, `ProReminderAlarmReceiver.java`
- Notification: `ProReminderNotification.java`
- Home entry point: `app/src/main/java/com/fadcam/ui/HomeFragment.java`
- Reminder cards: `ProReminderDialogFragment.java`, `ProReminderAfterDialogFragment.java`
- Card layouts: `app/src/main/res/layout/dialog_pro_reminder.xml`,
  `dialog_pro_reminder_after.xml`
- Look: `values/styles.xml` (card text styles), `values/pro_screen.xml` (card colors),
  `DossierPaperView.java`, `ProBundleShowcaseView.java`
- Text: `values/strings.xml` and the 12 `values-*` folders

## Testing

- Debug package: `com.fadcam.beta`.
- Install: `./gradlew :app:installDefaultDebug`.
- Design mode is on by default. To test the real notification, set
  `pro_reminder_design_mode=false`, make a cycle due, and open Home.
- The reminder never shows while recording, previewing, taking a photo, or when another
  dialog/sheet is open. It also stays away on watches, TVs and cars.
