package af.shizuku.manager.legacy

import android.os.Bundle
import android.widget.Toast
import af.shizuku.manager.app.AppActivity
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.ShizukuSettings

class ShellRequestHandlerActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authToken = intent.getStringExtra("auth")
        val expectedToken = ShizukuSettings.getAuthToken()
        if (authToken.isNullOrEmpty() || authToken != expectedToken) {
            finish()
            return
        }

        ShellBinderRequestHandler.handleRequest(this, intent)
        finish()
    }
}
