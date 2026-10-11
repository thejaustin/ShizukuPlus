package af.shizuku.manager.starter

/**
 * Whether an ADB start from [StarterActivity] has to ask before connecting, once an earlier
 * authorisation dialog went unanswered (see af.shizuku.manager.adb.AdbAuthWait.isUnanswered).
 * Not every route to that screen is a fresh tap in the manager, so such a start asks first,
 * unless the launch was itself one ([StarterActivity.EXTRA_USER_GESTURE]) or the user has
 * already confirmed or retried on this screen. Pure so the lifecycle is testable.
 */
internal class UnansweredConfirmation {
    private var confirmed = false

    /** The user tapped Start in the manager, answered the question, or pressed Retry. */
    fun confirm() {
        confirmed = true
    }

    /** False means hold the start and ask; root and system starts raise no adbd dialog. */
    fun mayStart(
        adb: Boolean,
        unanswered: Boolean,
    ): Boolean = !adb || confirmed || !unanswered

}
