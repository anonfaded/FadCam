package com.guardanis.applock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.fadcam.R;
import com.guardanis.applock.activities.UnlockActivity;
import com.guardanis.applock.services.FingerprintLockService;
import com.guardanis.applock.services.LockService;
import com.guardanis.applock.services.PINLockService;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.os.CancellationSignal;

public class AppLock {

    public interface UnlockDelegate {
        public void onUnlockSuccessful();
        public void onResolutionRequired(int errorCode);
        public void onAuthenticationHelp(int code, String message);
        public void onFailureLimitExceeded(String message);
    }

    private static AppLock instance;
    public static AppLock getInstance(Context context) {
        if (instance == null)
            instance = new AppLock(context);

        return instance;
    }

    public static final int REQUEST_CODE_UNLOCK = 9371;
    public static final int REQUEST_CODE_LOCK_CREATION = 9372;
    public static final int REQUEST_CODE_FINGERPRINT_PERMISSION = 9373;

    private static final String PREFS = "pin__preferences";

    private static final String PREF_UNLOCK_FAILURE_TIME = "pin__unlock_failure_time";
    private static final String PREF_UNLOCK_SUCCESS_TIME = "pin__unlock_success_time";

    public static final int ERROR_CODE_FINGERPRINTS_MISSING_HARDWARE = 1;
    public static final int ERROR_CODE_FINGERPRINTS_PERMISSION_REQUIRED = 2;
    public static final int ERROR_CODE_FINGERPRINTS_EMPTY = 3;
    public static final int ERROR_CODE_FINGERPRINTS_NOT_LOCALLY_ENROLLED = 4;
    public static final int ERROR_CODE_SDK_VERSION_MINIMUM = 5;

    protected Context context;

    protected HashMap<Class, LockService> lockServices = new HashMap<Class, LockService>();

    protected int unlockAttemptsCount = 1;

    protected AppLock(Context context){
        this.context = context.getApplicationContext();

        this.lockServices.put(PINLockService.class, new PINLockService());
        this.lockServices.put(FingerprintLockService.class, new FingerprintLockService());
    }

    /**
     * @return true if the user has enrolled in either PIN or Fingerprint locking
     */
    public static boolean isEnrolled(Context context) {
        AppLock helper = getInstance(context);

        for (LockService service : helper.lockServices.values()) {
            if (service.isEnrolled(context))
                return true;
        }

        return false;
    }

    /**
     * @return true if the user is enrolled in locking and the last successful unlock happened more than the default lock duration ago
     */
    public static boolean isUnlockRequired(Context context) {
        int minutes = context.getResources()
                .getInteger(R.integer.applock__activity_lock_reenable_minutes);

        return isUnlockRequired(context, TimeUnit.MINUTES.toMillis(minutes));
    }

    /**
     * @return true if the user is enrolled in locking and the last successful unlock happened more than lastSuccessValidMs ago
     */
    public static boolean isUnlockRequired(Context context, long lastSuccessValidMs) {
        return isEnrolled(context) && lastSuccessValidMs < System.currentTimeMillis() - getUnlockSuccessTime(context);
    }

    public void attemptFingerprintUnlock(final UnlockDelegate eventListener) {
        if (handleFailureBlocking(eventListener))
            return;

        FingerprintLockService.AuthenticationDelegate delegate = new FingerprintLockService.AuthenticationDelegate() {
            @Override
            public void onResolutionRequired(int errorCode) {
                eventListener.onResolutionRequired(errorCode);
            }

            @Override
            public void onAuthenticationHelp(int code, CharSequence message) {
                eventListener.onAuthenticationHelp(code, String.valueOf(message));
            }

            @Override
            public void onAuthenticating(CancellationSignal cancellationSignal) {
                // Handled internally
            }

            @Override
            public void onAuthenticationSuccess(FingerprintManagerCompat.AuthenticationResult result) {
                onUnlockSuccessful(eventListener);
            }

            @Override
            public void onAuthenticationFailed(String message) {
                handleUnlockFailure(message, eventListener);
            }
        };

        getLockService(FingerprintLockService.class)
                    .authenticate(context, delegate);
    }

    public void attemptPINUnlock(String pin, final UnlockDelegate eventListener) {
        if (handleFailureBlocking(eventListener))
            return;

        PINLockService.AuthenticationDelegate delegate = new PINLockService.AuthenticationDelegate() {
            @Override
            public void onNoPIN() {
                onUnlockFailed(context.getString(R.string.applock__unlock_error_no_matching_pin_found));
            }

            @Override
            public void onPINDoesNotMatch() {
                onUnlockFailed(context.getString(R.string.applock__unlock_error_match_failed));
            }

            @Override
            public void onPINMatches() {
                onUnlockSuccessful(eventListener);
            }

            private void onUnlockFailed(String message) {
                handleUnlockFailure(message, eventListener);
            }
        };

        getLockService(PINLockService.class)
                .authenticate(context, pin, delegate);
    }

    /**
     * @return true if failure blocking is enabled
     */
    private boolean handleFailureBlocking(final UnlockDelegate eventListener) {
        if (isUnlockFailureBlockEnabled()) {
            unlockAttemptsCount++;

            if(getFailureDelayMs() < System.currentTimeMillis() - getUnlockFailureBlockStart())
                resetUnlockFailure();
            else{
                String message = String.format(
                        context.getString(R.string.applock__unlock_error_retry_limit_exceeded),
                        formatTimeRemaining());

                if (eventListener != null)
                    eventListener.onFailureLimitExceeded(message);

                return true;
            }
        }

        return false;
    }

    protected void handleUnlockFailure(String message, UnlockDelegate eventListener) {
        if (eventListener != null)
            eventListener.onFailureLimitExceeded(message);
    }

    public SharedPreferences getPreferences(){
        return context.getSharedPreferences(PREFS, 0);
    }

    protected void onFailureExceedsLimit(){
        getPreferences()
                .edit()
                .putLong(PREF_UNLOCK_FAILURE_TIME, System.currentTimeMillis())
                .commit();
    }

    public boolean isUnlockFailureBlockEnabled() {
        return false;
    }

    protected long getUnlockFailureBlockStart() {
        return getPreferences()
                .getLong(PREF_UNLOCK_FAILURE_TIME, 0);
    }

    protected void onUnlockSuccessful(UnlockDelegate eventListener) {
        getPreferences()
                .edit()
                .putLong(PREF_UNLOCK_SUCCESS_TIME, System.currentTimeMillis())
                .commit();

        resetUnlockFailure();

        if (eventListener != null)
            eventListener.onUnlockSuccessful();
    }

    protected static long getUnlockSuccessTime(Context context) {
        return AppLock.getInstance(context)
                .getPreferences()
                .getLong(PREF_UNLOCK_SUCCESS_TIME, 0);
    }

    /**
     * Reset the last login time to require the user to re-authenticate with
     * AppLock the next time they are eligible to unlock.
     */
    public void setAuthenticationRequired() {
        getPreferences()
                .edit()
                .putLong(PREF_UNLOCK_SUCCESS_TIME, 0)
                .commit();
    }

    protected void resetUnlockFailure() {
        unlockAttemptsCount = 1;

        getPreferences()
                .edit()
                .putLong(PREF_UNLOCK_FAILURE_TIME, 0)
                .commit();
    }

    protected String formatTimeRemaining() {
        long millis = getFailureDelayMs() - (System.currentTimeMillis() - getUnlockFailureBlockStart());
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis));

        if(TimeUnit.MILLISECONDS.toMinutes(millis) < 1)
            return String.format("%d seconds", seconds);
        else
            return String.format("%d minutes, %d seconds", TimeUnit.MILLISECONDS.toMinutes(millis), seconds);
    }

    protected long getFailureDelayMs(){
        return TimeUnit.MINUTES.toMillis(context.getResources()
                .getInteger(R.integer.applock__failure_retry_delay));
    }

    /**
     * This will remove all PIN and/or Fingerprint enrollment data.
     * Users will need to re-enroll in AppLock after this call.
     */
    public void invalidateEnrollments() {
        resetUnlockFailure();
        setAuthenticationRequired();

        for (LockService service : lockServices.values())
            service.invalidateEnrollments(context);
    }

    public void cancelPendingAuthentications() {
        for (LockService service : lockServices.values())
            service.cancelPendingAuthentications(context);
    }

    public <T extends LockService> T getLockService(Class<T> named) {
        return (T) lockServices.get(named);
    }

    public static void onActivityResumed(Activity activity) {
        if(isUnlockRequired(activity)){
            boolean unlockActivityReturnAllowed = activity.getResources()
                    .getBoolean(R.bool.applock__unlock_activity_return_allowed);

            Intent intent = new Intent(activity, UnlockActivity.class)
                    .putExtra(UnlockActivity.INTENT_ALLOW_UNLOCKED_EXIT, unlockActivityReturnAllowed);

            activity.startActivityForResult(intent, REQUEST_CODE_UNLOCK);
        }
    }

    /**
     * Check if an action-based unlock is required and navigates to the UnlockActivity if true.
     * @return true if unlock is required.
     */
    public static boolean unlockIfRequired(Activity activity) {
        if(isUnlockRequired(activity)){
            Intent intent = new Intent(activity, UnlockActivity.class)
                    .putExtra(UnlockActivity.INTENT_ALLOW_UNLOCKED_EXIT, true);

            activity.startActivityForResult(intent, REQUEST_CODE_UNLOCK);

            return true;
        }
        else
            return false;
    }
}
