package com.fadcam;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import org.junit.Test;

public class RecordingToggleActivityTest {
    @Test
    public void finishWithoutUiFinishesActivityWithoutBackgroundingTask() {
        RecordingToggleActivity activity = mock(RecordingToggleActivity.class);
        doCallRealMethod().when(activity).finishWithoutUi();

        activity.finishWithoutUi();

        verify(activity).finish();
    }
}
