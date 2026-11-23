package com.fadcam.ui.utils;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

/**
 * Installs one hook that hides the system bars on every dialog window opened by an activity.
 *
 * <p>Each dialog is its own window, so an immersive activity does not make its sheets immersive
 * too. Installing this on the activity's fragment manager covers both sheets shown from the
 * activity and sheets shown from a fragment's child manager.</p>
 */
public final class ImmersiveDialogs {

    private ImmersiveDialogs() {
    }

    public static void install(@NonNull FragmentActivity activity) {
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(
                            @NonNull FragmentManager fm,
                            @NonNull Fragment f,
                            @NonNull View v,
                            @Nullable Bundle savedInstanceState) {
                        apply(f);
                    }

                    @Override
                    public void onFragmentResumed(
                            @NonNull FragmentManager fm,
                            @NonNull Fragment f) {
                        apply(f);
                    }
                }, true);
    }

    private static void apply(@NonNull Fragment fragment) {
        if (!(fragment instanceof DialogFragment)) return;
        Dialog dialog = ((DialogFragment) fragment).getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            ImmersiveMode.applyToWindow(dialog.getWindow());
        }
    }
}
