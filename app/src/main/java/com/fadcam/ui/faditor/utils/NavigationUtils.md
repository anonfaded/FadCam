# NavigationUtils - Enhanced Fragment Navigation

## Overview

The enhanced NavigationUtils class provides smooth fragment transitions with Material 3 motion patterns and seamless auto-save integration for the Faditor Mini video editor.

## Features

### Material 3 Motion Patterns
- **Shared Axis Transitions**: Smooth slide and fade animations following Material Design guidelines
- **Proper Timing**: Uses Material 3 recommended durations (300ms enter, 200ms exit, 150ms fade)
- **Interpolators**: DecelerateInterpolator for enter, AccelerateInterpolator for exit

### Navigation Stack Management
- **State Tracking**: Prevents multiple concurrent navigation operations
- **Stack Depth Control**: Prevents navigation stack overflow
- **Proper Back Handling**: Integrates with fragment back button behavior

### Auto-Save Integration
- **Immediate Save on Navigation**: Triggers auto-save before navigation transitions
- **Lifecycle Integration**: Connects auto-save manager with fragment lifecycle
- **Seamless Experience**: Users never lose work during navigation

## Usage

### Opening Editor
```java
// From project browser to full-screen editor
NavigationUtils.openEditor(currentFragment, projectId);
```

### Returning to Browser
```java
// From editor back to project browser
NavigationUtils.returnToBrowser(currentFragment);
```

### Back Button Handling
```java
@Override
protected boolean onBackPressed() {
    return NavigationUtils.handleBackPress(this);
}
```

### Auto-Save Integration
```java
// In fragment setup
NavigationUtils.integrateAutoSave(this, autoSaveManager);
```

## Requirements Fulfilled

- **10.4**: Proper back button behavior and navigation state
- **10.5**: Clear back/leave functionality with auto-save
- **12.2**: Immediate save on navigation and app lifecycle events  
- **12.7**: Auto-save integration for seamless navigation

## Material 3 Motion Implementation

The navigation uses Material 3's shared axis transition pattern:

1. **Enter Transition**: Content slides in from right with fade-in
2. **Exit Transition**: Content slides out to left with fade-out
3. **Timing**: Follows Material 3 motion tokens for natural feel
4. **Easing**: Uses proper interpolators for smooth motion

## Error Handling

- **Navigation State Protection**: Prevents multiple concurrent operations
- **Graceful Degradation**: Falls back to basic transitions if animations fail
- **Auto-Save Safety**: Ensures data is saved even if navigation is interrupted