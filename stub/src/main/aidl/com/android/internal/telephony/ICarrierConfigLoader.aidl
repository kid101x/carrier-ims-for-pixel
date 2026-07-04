package com.android.internal.telephony;

import android.os.PersistableBundle;

interface ICarrierConfigLoader {
    PersistableBundle getConfigForSubId(int subId);
    void overrideConfig(int subId, in PersistableBundle values, boolean persistent);
}
