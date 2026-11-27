package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Base Fragment class that provides common functionality for all fragments,
 * including handling of back button press consistently.
 */
public abstract class BaseFragment extends Fragment {

    /**
     * Applies the user's tab-switch fade length to the animations the activity attaches to
     * this fragment's transactions.
     *
     * <p>{@link androidx.fragment.app.FragmentTransaction#setCustomAnimations} only accepts
     * resource IDs, so the duration baked into {@code fade_in.xml}/{@code fade_out.xml} could
     * not be tuned at runtime. Overriding {@code onCreateAnimation} lets us load that same
     * resource and then set the duration from the preference, which is the only supported way
     * to change a fragment animation's speed without duplicating a resource per speed.</p>
     *
     * <p>A duration of 0 returns no animation at all, so tab switches become instant.</p>
     */
    @Override
    public android.view.animation.Animation onCreateAnimation(
            int transit, boolean enter, int nextAnim) {
        if (nextAnim == 0) {
            return super.onCreateAnimation(transit, enter, nextAnim);
        }
        try {
            com.fadcam.SharedPreferencesManager prefs =
                    com.fadcam.SharedPreferencesManager.getInstance(requireContext());
            long durationMs = prefs.getTabTransitionDurationMs();
            android.view.animation.Animation anim =
                    android.view.animation.AnimationUtils.loadAnimation(requireContext(), nextAnim);
            if (anim == null) {
                return super.onCreateAnimation(transit, enter, nextAnim);
            }
            // Returning null here would NOT disable the animation: FragmentManager treats a
            // null result as "use the default animation", so the fade still ran at its XML
            // duration. A zero-length animation is the only way to actually switch instantly.
            anim.setDuration(durationMs);
            anim.setStartOffset(0L);
            return anim;
        } catch (Exception e) {
            // A preference read must never break a tab switch — fall back to the XML default.
            FLog.w("BaseFragment", "onCreateAnimation fallback: " + e.getMessage());
            return super.onCreateAnimation(transit, enter, nextAnim);
        }
    }
    
    /**
     * Override this method to handle back presses in your fragment.
     * Return true if the back press was handled, false to let the activity handle it.
     */
    protected boolean onBackPressed() {
        return false;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Add a callback for handling back presses
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), 
                new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FLog.d("OverlayDebug","BaseFragment handleOnBackPressed fragment="+getClass().getSimpleName());
                // First give the fragment a chance to handle the back press
                if (!onBackPressed()) {
                    // Check if overlay (settings/trash) is visible — let MainActivity handle it
                    try {
                        if (requireActivity() instanceof com.fadcam.MainActivity) {
                            com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) requireActivity();
                            android.view.View overlay = mainActivity.findViewById(R.id.overlay_fragment_container);
                            if (overlay != null && overlay.getVisibility() == android.view.View.VISIBLE) {
                                // Overlay is visible — disable this callback and let MainActivity's handler take over
                                setEnabled(false);
                                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                                return;
                            }
                            if (mainActivity.getCurrentFragmentPosition() != 0) {
                                mainActivity.switchFragment(0, true);
                            } else {
                                // If we're already on the home tab, disable this callback and let the system handle it
                                setEnabled(false);
                                // Use the dispatcher directly instead of the deprecated method
                                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                            }
                        }
                    } catch (Exception e) {
                        // If anything goes wrong, disable this callback and let the system handle it
                        setEnabled(false);
                        // Use the dispatcher directly instead of the deprecated method
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Ensure overlay container visibility updates if this was last fragment
        if(getActivity() instanceof com.fadcam.MainActivity){
            FLog.d("OverlayDebug","BaseFragment onDestroyView fragment="+getClass().getSimpleName()+" backStackBefore="+getActivity().getSupportFragmentManager().getBackStackEntryCount());
            ((com.fadcam.MainActivity) getActivity()).hideOverlayIfNoFragments();
        }
    }
} 