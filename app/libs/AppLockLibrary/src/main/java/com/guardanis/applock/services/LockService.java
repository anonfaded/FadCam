package com.guardanis.applock.services;

import android.content.Context;

public abstract class LockService {

    public abstract boolean isEnrolled(Context context);
    public abstract boolean isEnrollmentEligible(Context context);
    public abstract void cancelPendingAuthentications(Context context);
    public abstract void invalidateEnrollments(Context context);
}
