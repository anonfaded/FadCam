package com.guardanis.applock.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.guardanis.applock.AppLock;
import com.fadcam.R;
import com.guardanis.applock.utils.CipherGenerator;

import javax.crypto.Cipher;

import androidx.core.content.ContextCompat;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.os.CancellationSignal;

public class FingerprintLockService extends LockService {

    public interface AuthenticationDelegate {
        public void onResolutionRequired(int errorCode);
        public void onAuthenticationHelp(int code, CharSequence message);
        public void onAuthenticating(CancellationSignal cancellationSignal);
        public void onAuthenticationSuccess(FingerprintManagerCompat.AuthenticationResult result);
        public void onAuthenticationFailed(String message);
    }

    private static final String PREF_ENROLLMENT_ALLOWED = "pin__fingerprint_enrollment_allowed";

    protected CancellationSignal fingerprintCancellationSignal;

    @Override
    public boolean isEnrollmentEligible(Context context) {
        return Build.VERSION_CODES.M <= Build.VERSION.SDK_INT
                && context.getResources().getBoolean(R.bool.applock__fingerprint_service_enabled)
                && isHardwarePresent(context);
    }

    public void enroll(Context context, AuthenticationDelegate delegate) {
        authenticate(context, false, delegate);
    }

    public void authenticate(Context context, AuthenticationDelegate delegate) {
        authenticate(context, true, delegate);
    }

    protected void authenticate(Context context, boolean localEnrollmentRequired, AuthenticationDelegate delegate) {
        int errorCode = getRequiredResolutionErrorCode(context, localEnrollmentRequired);

        if (-1 < errorCode) {
            delegate.onResolutionRequired(errorCode);

            return;
        }

        // Should be handled by getRequiredResolutionErrorCode(Context, boolean)
        if (Build.VERSION.SDK_INT <  Build.VERSION_CODES.M)
            return;

        attemptFingerprintManagerAuthentication(context, delegate);
    }

    /**
     * @return the resolvable error code or -1 if there are no issues requiring a resolution
     */
    protected int getRequiredResolutionErrorCode(Context context, boolean localEnrollmentRequired) {
        FingerprintManagerCompat manager = FingerprintManagerCompat.from(context);

        if (Build.VERSION.SDK_INT <  Build.VERSION_CODES.M)
            return AppLock.ERROR_CODE_SDK_VERSION_MINIMUM;

        if (localEnrollmentRequired && !isEnrolled(context))
            return AppLock.ERROR_CODE_FINGERPRINTS_NOT_LOCALLY_ENROLLED;

        if (!isHardwarePresent(context))
            return AppLock.ERROR_CODE_FINGERPRINTS_MISSING_HARDWARE;

        if (!manager.hasEnrolledFingerprints())
            return AppLock.ERROR_CODE_FINGERPRINTS_EMPTY;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.USE_FINGERPRINT) != PackageManager.PERMISSION_GRANTED)
            return AppLock.ERROR_CODE_FINGERPRINTS_PERMISSION_REQUIRED;

        return -1;
    }

    protected void attemptFingerprintManagerAuthentication(final Context context, final AuthenticationDelegate delegate) {
        this.fingerprintCancellationSignal = new CancellationSignal();

        FingerprintManagerCompat.AuthenticationCallback callback = new FingerprintManagerCompat.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errMsgId, CharSequence errString) {
                super.onAuthenticationError(errMsgId, errString);

                delegate.onResolutionRequired(R.string.applock__fingerprint_error_unknown);
            }

            @Override
            public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
                super.onAuthenticationHelp(helpMsgId, helpString);

                delegate.onAuthenticationHelp(helpMsgId, helpString);
            }

            @Override
            public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);

                notifyEnrolled(context);

                delegate.onAuthenticationSuccess(result);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();

                delegate.onAuthenticationFailed(context.getString(R.string.applock__fingerprint_error_unrecognized));
            }
        };

        delegate.onAuthenticating(fingerprintCancellationSignal);

        try {
            Cipher cipher = generateAuthCipher(context, false, 0);
            FingerprintManagerCompat.CryptoObject cryptoObject = new FingerprintManagerCompat.CryptoObject(cipher);

            FingerprintManagerCompat manager = FingerprintManagerCompat.from(context);
            manager.authenticate(cryptoObject, 0, fingerprintCancellationSignal, callback, null);
        }
        catch (Exception e) {
            e.printStackTrace();

            delegate.onResolutionRequired(AppLock.ERROR_CODE_FINGERPRINTS_MISSING_HARDWARE);
        }
    }

    public boolean isHardwarePresent(Context context) {
        return FingerprintManagerCompat.from(context)
                .isHardwareDetected();
    }

    @Override
    public boolean isEnrolled(Context context) {
        return AppLock.getInstance(context)
                .getPreferences()
                .getBoolean(PREF_ENROLLMENT_ALLOWED, false);
    }

    protected void notifyEnrolled(Context context) {
        AppLock.getInstance(context)
                .getPreferences()
                .edit()
                .putBoolean(PREF_ENROLLMENT_ALLOWED, true)
                .commit();
    }

    @SuppressLint("NewApi")
    protected Cipher generateAuthCipher(Context context, boolean forceRegenerate, int attempts) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            throw new RuntimeException("generateAuthCipher() not supported before Api 23");

        return new CipherGenerator()
            .generateAuthCipher(context, forceRegenerate, attempts);
    }

    @Override
    public void invalidateEnrollments(Context context) {
        AppLock.getInstance(context)
                .getPreferences()
                .edit()
                .putBoolean(PREF_ENROLLMENT_ALLOWED, false)
                .commit();
    }

    @Override
    public void cancelPendingAuthentications(Context context) {
        if (fingerprintCancellationSignal != null) {
            this.fingerprintCancellationSignal.cancel();
            this.fingerprintCancellationSignal = null;
        }
    }
}
