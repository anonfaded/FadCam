package com.fadcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * Renders the attribution screen for real so a broken asset, a bad layout id or a
 * renamed string fails the build instead of shipping an empty page.
 */
@RunWith(RobolectricTestRunner.class)
public class OpenSourceLicensesFragmentTest {

    private FragmentActivity launch() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class)
                .setup()
                .get();
        Fragment fragment = new OpenSourceLicensesFragment();
        activity.getSupportFragmentManager()
                .beginTransaction()
                .add(android.R.id.content, fragment)
                .commitNow();
        return activity;
    }

    @Test
    public void groupsAreRenderedWithCountsAndExpand() {
        FragmentActivity activity = launch();
        View root = activity.findViewById(R.id.licenses_list);
        assertNotNull("licenses_list missing from the layout", root);

        LinearLayout list = (LinearLayout) root;
        // Each group contributes a header plus its body.
        assertTrue("no groups rendered", list.getChildCount() >= 2);

        TextView summary = activity.findViewById(R.id.licenses_summary);
        assertNotNull(summary);
        String text = summary.getText().toString();
        assertTrue("summary did not interpolate: " + text, text.matches(".*\\d+.*"));

        // The first header must be Apache-2.0: it dominates this dependency graph.
        View header = list.getChildAt(0);
        TextView name = header.findViewById(R.id.license_name);
        assertNotNull(name);
        assertEquals("Apache License 2.0", name.getText().toString());

        // Expanding reveals the bundled notice rather than a link.
        View body = list.getChildAt(1);
        assertEquals("body should start collapsed", View.GONE, body.getVisibility());
        header.performClick();
        assertEquals("body did not expand", View.VISIBLE, body.getVisibility());

        boolean foundText = false;
        for (int i = 0; i < ((LinearLayout) body).getChildCount(); i++) {
            View child = ((LinearLayout) body).getChildAt(i);
            TextView candidate = child.findViewById(R.id.license_text);
            if (candidate != null && candidate.getText().length() > 1000) {
                foundText = true;
                break;
            }
        }
        assertTrue("bundled Apache text not shown on expand", foundText);
    }

    @Test
    public void everyGroupHasALibraryCount() {
        FragmentActivity activity = launch();
        LinearLayout list = activity.findViewById(R.id.licenses_list);
        assertNotNull(list);

        int headers = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            TextView name = child.findViewById(R.id.license_name);
            if (name == null) {
                continue;
            }
            headers++;
            TextView count = child.findViewById(R.id.license_count);
            assertNotNull("group " + name.getText() + " has no count", count);
            assertTrue("count text empty for " + name.getText(),
                    count.getText().length() > 0);
        }
        assertTrue("expected at least one license group", headers >= 1);
    }
}
