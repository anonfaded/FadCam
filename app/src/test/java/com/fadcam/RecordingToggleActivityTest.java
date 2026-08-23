package com.fadcam;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Test;

public class RecordingToggleActivityTest {
    @Test
    public void finishWithoutUiFinishesWithoutBackgroundingTask() {
        RecordingToggleActivity activity = mock(RecordingToggleActivity.class);
        doCallRealMethod().when(activity).finishWithoutUi();

        activity.finishWithoutUi();

        verify(activity, never()).moveTaskToBack(true);
        verify(activity).finish();
    }
}

