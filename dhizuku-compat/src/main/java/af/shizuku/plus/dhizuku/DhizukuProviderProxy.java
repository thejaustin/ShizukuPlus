package af.shizuku.plus.dhizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * Answers at com.rosan.dhizuku.server.provider (the authority Dhizuku-API 2.4 clients hardcode)
 * by forwarding call() to the Shizuku+ manager's own Dhizuku provider.
 *
 * Nothing privileged happens here: the bundle that comes back holds the manager's binder, and the
 * client then talks to the manager directly, so the manager's per-app authorization (by calling
 * uid) still decides what each app may do.
 */
public class DhizukuProviderProxy extends ContentProvider {
    // Read by the manager's DhizukuProvider; keep the two in sync.
    private static final String EXTRA_CLIENT_UID = "af.shizuku.plus.dhizuku.client_uid";


    private static final String[] MANAGER_AUTHORITIES = {
        "af.shizuku.plus.api.dhizuku_server.provider",
        "moe.shizuku.privileged.api.dhizuku_server.provider", // Drop-In flavor
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // The manager sees this app as the caller, so pass on who really asked. It only believes
        // this from us (same signing key), and uses it to tell which apps may ask for permission.
        Bundle forwarded = extras != null ? new Bundle(extras) : new Bundle();
        forwarded.putInt(EXTRA_CLIENT_UID, Binder.getCallingUid());
        for (String authority : MANAGER_AUTHORITIES) {
            try {
                Bundle reply =
                        getContext().getContentResolver().call(Uri.parse("content://" + authority), method, arg, forwarded);
                if (reply != null) return reply;
            } catch (Exception ignored) {
                // Not installed (or not that flavor); try the next one.
            }
        }
        return null;
    }

    // Dhizuku only uses call(); stubs satisfy ContentProvider's abstract contract.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
