package af.shizuku.manager.automation.locale

/**
 * Intent contract used by Locale-compatible automation apps (Tasker, MacroDroid, etc.) to
 * discover and invoke this app's plugin action/condition. Actions and extras are the
 * long-standing de-facto standard defined by com.twofortyfouram.locale (not a dependency here,
 * just the string contract), so the literal values below must match exactly.
 */
object LocalePluginContract {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"

    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    // Ordered-broadcast result codes a QUERY_CONDITION receiver must set synchronously.
    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17

    // Key inside EXTRA_BUNDLE identifying which action to fire.
    const val BUNDLE_KEY_ACTION = "shizuku_plus_action"
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
}
