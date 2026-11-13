package com.fadcam.ui.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/** The mockup's mini apps must behave like the real thing, not just look like it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class BundlePhoneSurfaceViewTest {

    /** Key indices in CALC_KEYS: {"7","8","9","÷","4","5","6","×","1","2","3","−","C","0","=","+"}. */
    private static final int KEY_ZERO = 13;
    private static final int KEY_DIVIDE = 3;
    private static final int KEY_EQUALS = 14;
    private static final int KEY_CLEAR = 12;
    private static final int KEY_FIVE = 5;

    private BundlePhoneSurfaceView surface() {
        return new BundlePhoneSurfaceView(RuntimeEnvironment.getApplication(),
                BundlePhoneSurfaceView.SIDE_FRONT);
    }

    @Test
    public void divisionByZeroShowsAnErrorInsteadOfSilentlyShowingZero() throws Exception {
        BundlePhoneSurfaceView view = surface();
        view.openPreview(2);
        view.calculatorPress(KEY_ZERO);
        view.calculatorPress(KEY_DIVIDE);
        view.calculatorPress(KEY_ZERO);
        view.calculatorPress(KEY_EQUALS);
        assertEquals("Error", calcDisplay(view));

        // A fresh digit starts a new number, exactly like a calculator after an error.
        view.calculatorPress(KEY_CLEAR);
        view.calculatorPress(KEY_FIVE);
        assertEquals("5", calcDisplay(view));
    }

    @Test
    public void recentsHasNoPhantomAppBeforeAnythingIsOpened() {
        BundlePhoneSurfaceView view = surface();
        assertEquals("no app has been opened yet", 0, view.getLastPreviewApp());
        assertFalse("an empty recents card must not be tappable", view.recentsCardAt(0f, 0f));
    }

    @Test
    public void rangeSelectionFollowsItsStartNotItsEnd() throws Exception {
        BundlePhoneSurfaceView view = surface();
        view.openPreview(1);
        view.setNote("one two three");
        view.setNoteSelection(0, 13);   // select all, e.g. from the keyboard
        assertEquals("a range must keep its beginning in view, not jump to its end",
                0, intField(view, "noteFollowOffset"));
        view.setNoteSelection(7, 7);    // plain caret
        assertEquals(7, intField(view, "noteFollowOffset"));
    }

    private int intField(BundlePhoneSurfaceView view, String name) throws Exception {
        Field field = BundlePhoneSurfaceView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(view);
    }

    @Test
    public void noteDraftOutlivesTheViewThatTypedIt() throws Exception {
        ProBundleShowcaseView first = new ProBundleShowcaseView(RuntimeEnvironment.getApplication(), null);
        noteInput(first).setText("Keep this line");
        assertEquals("saved immediately, in the app's own settings store",
                "Keep this line", com.fadcam.SharedPreferencesManager
                        .getInstance(RuntimeEnvironment.getApplication()).getProShowcaseNote());

        // A fresh showcase = the next visit (or the next process): the draft is loaded back in.
        ProBundleShowcaseView second = new ProBundleShowcaseView(RuntimeEnvironment.getApplication(), null);
        assertEquals("Keep this line", noteInput(second).getText().toString());
        assertEquals("Keep this line", noteText(second));
    }

    private android.widget.EditText noteInput(ProBundleShowcaseView view) throws Exception {
        Field field = ProBundleShowcaseView.class.getDeclaredField("noteInput");
        field.setAccessible(true);
        return (android.widget.EditText) field.get(view);
    }

    private String noteText(ProBundleShowcaseView view) throws Exception {
        Field surfaceField = ProBundleShowcaseView.class.getDeclaredField("frontSurface");
        surfaceField.setAccessible(true);
        BundlePhoneSurfaceView surface = (BundlePhoneSurfaceView) surfaceField.get(view);
        Field noteField = BundlePhoneSurfaceView.class.getDeclaredField("noteText");
        noteField.setAccessible(true);
        return (String) noteField.get(surface);
    }

    private String calcDisplay(BundlePhoneSurfaceView view) throws Exception {
        Field field = BundlePhoneSurfaceView.class.getDeclaredField("calcDisplay");
        field.setAccessible(true);
        return (String) field.get(view);
    }
}
