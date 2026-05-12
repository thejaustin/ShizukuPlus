package af.shizuku.manager.shell;

import android.content.pm.PackageManager;
import af.shizuku.manager.utils.Logger;
import android.os.Handler;
import android.os.IBinder;

import rikka.rish.Rish;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

public class Shell extends Rish {

    private static final Logger LOGGER = new Logger("Shell");

    @Override
    public void requestPermission(Runnable onGrantedRunnable) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGrantedRunnable.run();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            LOGGER.e("Permission denied");
            System.exit(1);
        } else {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Shizuku.removeRequestPermissionResultListener(this);

                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGrantedRunnable.run();
                    } else {
                        LOGGER.e("Permission denied");
                        System.exit(1);
                    }
                }
            });
            Shizuku.requestPermission(0);
        }
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        timber.log.Timber.plant(new timber.log.Timber.DebugTree());
        RishConfig.init(binder, ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);
        Shizuku.onBinderReceived(binder, packageName);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            int version = Shizuku.getVersion();
            if (version < 12) {
                LOGGER.e("Rish requires server 12 (running " + version + ")");
                System.exit(1);
            }
            new Shell().start(args);
        });
    }
}
