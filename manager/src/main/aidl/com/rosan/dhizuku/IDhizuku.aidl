package com.rosan.dhizuku;

interface IDhizuku {

    int getVersion() = 1;

    IBinder getBinder() = 2;

    boolean isPermissionGranted() = 3;

    Bundle transact(int code, in Bundle data) = 4;
}
