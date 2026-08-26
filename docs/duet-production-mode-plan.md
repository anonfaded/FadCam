# Duet Production Mode Plan

## Modes
- PROGRAM: video/online TV/local media full-screen; no duet resources.
- CAMERA: host/guest full-screen; no duet resources unless explicitly requested.
- DUET: secondary source enabled for interview/commentary/PiP/split.

## Rules
- Duet UI is collapsed by default.
- Selecting PROGRAM or CAMERA collapses and releases/stops secondary resources.
- Selecting INTERVIEW, COMMENTARY, DUET_PIP, or DUET_SPLIT expands Duet controls.
- Scene transitions must run through one validated state transition on the main thread.
- Hidden duet controls must not keep a camera/player/WebView active.

## Verification matrix
PROGRAM -> DUET -> PROGRAM
PROGRAM -> COMMENTARY -> PROGRAM
PROGRAM -> INTERVIEW -> PROGRAM
CAMERA -> DUET -> CAMERA
DUET_PIP -> DUET_SPLIT -> PROGRAM
Open/close Scenes repeatedly in every mode.
Test while recording and streaming.
