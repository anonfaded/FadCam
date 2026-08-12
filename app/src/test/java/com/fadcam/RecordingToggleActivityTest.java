package com.fadcam;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.mockito.InOrder;

public class RecordingToggleActivityTest {
    @Test
    public void finishWithoutUiBackgroundsTaskBeforeFinishing() {
        RecordingToggleActivity activity = mock(RecordingToggleActivity.class);
        doCallRealMethod().when(activity).finishWithoutUi();

        activity.finishWithoutUi();

        InOrder completionOrder = inOrder(activity);
        completionOrder.verify(activity).moveTaskToBack(true);
        completionOrder.verify(activity).finish();
    }
}
