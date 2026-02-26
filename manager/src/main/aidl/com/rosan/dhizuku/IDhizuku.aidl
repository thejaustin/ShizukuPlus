package com.rosan.dhizuku;

import android.os.Bundle;

interface IDhizuku {

    int getVersion() = 1;

    IBinder getBinder() = 2;

    boolean isPermissionGranted() = 3;

    Bundle transact(int code, in Bundle data) = 4;
}
