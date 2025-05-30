package com.guardanis.applock.utils;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.lang.ref.WeakReference;

public class LifeCycleUtils {

    public static AppLockActivityLifeCycleCallbacks attach(Activity activity, AppLockActivityLifeCycleCallbacks.Delegate delegate) {
        AppLockActivityLifeCycleCallbacks callbacks = new AppLockActivityLifeCycleCallbacks(activity, delegate);

        activity.getApplication()
                .registerActivityLifecycleCallbacks(callbacks);

        return callbacks;
    }

    public static class AppLockActivityLifeCycleCallbacks implements Application.ActivityLifecycleCallbacks {

        public interface Delegate {
            public void onActivityResumed();
            public void onActivityPaused();
        }

        protected WeakReference<Activity> openedActivity;
        protected WeakReference<Delegate> delegate;

        public AppLockActivityLifeCycleCallbacks(Activity activity, Delegate delegate) {
            this.openedActivity = new WeakReference<Activity>(activity);
            this.delegate = new WeakReference<Delegate>(delegate);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle bundle) { }

        @Override
        public void onActivityStarted(Activity activity) { }

        @Override
        public void onActivityStopped(Activity activity) { }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle bundle) { }

        @Override
        public void onActivityResumed(Activity activity) {
            Activity opened = this.openedActivity.get();
            Delegate delegate = this.delegate.get();

            if (opened == null || delegate == null || opened != activity)
                return;

            delegate.onActivityResumed();
        }

        @Override
        public void onActivityPaused(Activity activity) {
            Activity opened = this.openedActivity.get();
            Delegate delegate = this.delegate.get();

            if (opened == null || delegate == null || opened != activity)
                return;

            delegate.onActivityPaused();
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            Activity opened = this.openedActivity.get();

            if (opened == null || opened != activity)
                return;

            opened.getApplication()
                    .unregisterActivityLifecycleCallbacks(this);
        }
    }
}
