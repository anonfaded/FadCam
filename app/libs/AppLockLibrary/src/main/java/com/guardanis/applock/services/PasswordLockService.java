package com.guardanis.applock.services;

import android.content.Context;

import com.guardanis.applock.AppLock;
import com.guardanis.applock.utils.CryptoUtils;

public class PasswordLockService extends LockService {

    public interface AuthenticationDelegate {
        public void onNoPassword();
        public void onPasswordDoesNotMatch();
        public void onPasswordMatches();
    }

    private static final String PREF_SAVED_LOCKED_PASSWORD_CHARS = "pin__saved_locked_password_chars";

    @Override
    public boolean isEnrollmentEligible(Context context) {
        // Always allow password enrollment
        return true;
    }

    public void authenticate(Context context, String password, AuthenticationDelegate eventListener) {
        if (!isEnrolled(context)) {
            eventListener.onNoPassword();
            return;
        }

        if (!getEnrolledPassword(context).equals(CryptoUtils.encryptSha1(password))) {
            eventListener.onPasswordDoesNotMatch();
            return;
        }

        eventListener.onPasswordMatches();
    }

    @Override
    public boolean isEnrolled(Context context) {
        return AppLock.getInstance(context)
                .getPreferences()
                .getString(PREF_SAVED_LOCKED_PASSWORD_CHARS, null) != null;
    }

    private String getEnrolledPassword(Context context) {
        return AppLock.getInstance(context)
                .getPreferences()
                .getString(PREF_SAVED_LOCKED_PASSWORD_CHARS, null);
    }

    public void enroll(Context context, String password) {
        AppLock.getInstance(context)
                .getPreferences()
                .edit()
                .putString(PREF_SAVED_LOCKED_PASSWORD_CHARS, CryptoUtils.encryptSha1(password))
                .commit();
    }

    @Override
    public void invalidateEnrollments(Context context) {
        AppLock.getInstance(context)
                .getPreferences()
                .edit()
                .remove(PREF_SAVED_LOCKED_PASSWORD_CHARS)
                .commit();
    }

    @Override
    public void cancelPendingAuthentications(Context context) {
        // There are none
    }
} 