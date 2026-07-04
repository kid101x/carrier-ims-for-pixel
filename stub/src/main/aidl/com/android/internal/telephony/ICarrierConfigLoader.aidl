package com.android.internal.telephony;

import android.os.PersistableBundle;

interface ICarrierConfigLoader {
    PersistableBundle getConfigForSubId(int subId, String callingPackage);
    void overrideConfig(int subId, in PersistableBundle values);
}
