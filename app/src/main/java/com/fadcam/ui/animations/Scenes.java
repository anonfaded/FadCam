package com.fadcam.ui.animations;

import android.content.Context;

import com.fadcam.ui.animations.scenes.BackgroundScene;
import com.fadcam.ui.animations.scenes.BodycamScene;
import com.fadcam.ui.animations.scenes.DashcamScene;
import com.fadcam.ui.animations.scenes.EditorScene;
import com.fadcam.ui.animations.scenes.FlashlightScene;
import com.fadcam.ui.animations.scenes.ForensicScene;
import com.fadcam.ui.animations.scenes.MotionScene;
import com.fadcam.ui.animations.scenes.QrScene;
import com.fadcam.ui.animations.scenes.RemoteScene;
import com.fadcam.ui.animations.scenes.ScreenRecordScene;

/**
 * Registry of the built-in scenes.
 *
 * <p>Hosts that only need one scene can instantiate it directly; the registry
 * exists so XML-driven previews and tests can map a stable integer to a scene
 * without a switch of their own.</p>
 */
public final class Scenes {

    public static final int BACKGROUND = 0;
    public static final int SCREEN = 1;
    public static final int EDITOR = 2;
    public static final int MOTION = 3;
    public static final int REMOTE = 4;
    public static final int FLASHLIGHT = 6;
    public static final int QR = 7;
    public static final int BODYCAM = 8;
    public static final int DASHCAM = 9;
    public static final int FORENSIC = 10;

    private Scenes() {
    }

    /** Creates a fresh scene; stateful scenes must not be shared between views. */
    public static Scene create(Context context, int type) {
        switch (type) {
            case SCREEN:
                return new ScreenRecordScene();
            case EDITOR:
                return new EditorScene();
            case MOTION:
                return new MotionScene();
            case FORENSIC:
                return new ForensicScene(context);
            case REMOTE:
                return new RemoteScene();
            case BODYCAM:
                return new BodycamScene();
            case DASHCAM:
                return new DashcamScene();
            case FLASHLIGHT:
                return new FlashlightScene(context);
            case QR:
                return new QrScene(context);
            case BACKGROUND:
            default:
                return new BackgroundScene();
        }
    }
}
