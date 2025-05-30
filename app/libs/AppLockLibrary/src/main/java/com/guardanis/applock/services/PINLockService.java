package com.guardanis.applock.services;

import android.content.Context;

import com.guardanis.applock.AppLock;
import com.guardanis.applock.utils.CryptoUtils;

public class PINLockService extends LockService {

    public interface AuthenticationDelegate {
        public void onNoPIN();
        public void onPINDoesNotMatch();
        public void onPINMatches();
    }

    private static final String PREF_SAVED_LOCKED_PASSWORD = "pin__saved_locked_password";

    @Override
    public boolean isEnrollmentEligible(Context context) {
        // I couldn't find a good reason to allow the disabling of the PIN as an option.
        return true;
    }

    public void authenticate(Context context, String pin, AuthenticationDelegate eventListener) {
        if (!isEnrolled(context)) {
            eventListener.onNoPIN();
            return;
        }

        if (!getEnrolledPIN(context).equals(CryptoUtils.encryptSha1(pin))) {
            eventListener.onPINDoesNotMatch();
            return;
        }

        eventListener.onPINMatches();
    }

    @Override
    public boolean isEnrolled(Context context) {
        return AppLock.getInstance(context)
                .getPreferences()
                .getString(PREF_SAVED_LOCKED_PASSWORD, null) != null;
    }

    private String getEnrolledPIN(Context context) {
        return AppLock.getInstance(context)
                .getPreferences()
                .getString(PREF_SAVED_LOCKED_PASSWORD, null);
    }

    public void enroll(Context context, String pin) {
        AppLock.getInstance(context)
                .getPreferences()
                .edit()
                .putString(PREF_SAVED_LOCKED_PASSWORD, CryptoUtils.encryptSha1(pin))
                .commit();
    }

    @Override
    public void invalidateEnrollments(Context context) {
        AppLock.getInstance(context)
                .getPreferences()
                .edit()
                .remove(PREF_SAVED_LOCKED_PASSWORD)
                .commit();
    }

    @Override
    public void cancelPendingAuthentications(Context context) {
        // There are none
    }
}
