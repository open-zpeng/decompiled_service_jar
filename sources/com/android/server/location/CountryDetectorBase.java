package com.android.server.location;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.os.Handler;

/* loaded from: classes.dex */
public abstract class CountryDetectorBase {
    protected final Context mContext;
    protected Country mDetectedCountry;
    protected final Handler mHandler = new Handler();
    protected CountryListener mListener;

    public abstract Country detectCountry();

    public abstract void stop();

    public CountryDetectorBase(Context ctx) {
        this.mContext = ctx;
    }

    public void setCountryListener(CountryListener listener) {
        this.mListener = listener;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void notifyListener(Country country) {
        CountryListener countryListener = this.mListener;
        if (countryListener != null) {
            countryListener.onCountryDetected(country);
        }
    }
}
