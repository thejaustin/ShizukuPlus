package af.shizuku.manager.settings

import af.shizuku.manager.R
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Material 3 Expressive integer single-choice preference rendered as an inline
 * segmented button row (e.g., for Dark Theme: Off, On, Follow System).
 *
 * Persists an [Int] value directly.
 */
@SuppressLint("RestrictedApi")
class IntegerConnectedButtonGroupPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        var entries: Array<CharSequence> = emptyArray()
        var entryValues: IntArray = intArrayOf()

        private val selected = mutableIntStateOf(1)

        var value: Int
            get() = selected.intValue
            set(newValue) {
                val changed = selected.intValue != newValue
                selected.intValue = newValue
                if (changed) {
                    persistInt(newValue)
                    notifyDependencyChange(shouldDisableDependents())
                }
            }

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false

            val a =
                context.obtainStyledAttributes(
                    attrs,
                    R.styleable.ListPreference,
                    0,
                    0,
                )
            TypedArrayUtils
                .getTextArray(
                    a,
                    R.styleable.ListPreference_entries,
                    R.styleable.ListPreference_android_entries,
                )?.let { entries = it }

            val resId =
                TypedArrayUtils.getResourceId(
                    a,
                    R.styleable.ListPreference_entryValues,
                    R.styleable.ListPreference_android_entryValues,
                    0,
                )
            if (resId != 0) {
                entryValues = context.resources.getIntArray(resId)
            }
            a.recycle()
        }

        override fun onGetDefaultValue(
            a: TypedArray,
            index: Int,
        ): Any? = a.getInt(index, 1)

        override fun onSetInitialValue(defaultValue: Any?) {
            value = getPersistedInt((defaultValue as? Int) ?: 1)
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                (holder.itemView as? ComposeView)
                    ?: (holder.findViewById(R.id.preference_compose_view) as? ComposeView)
            if (cv == null) {
                timber.log.Timber.w(
                    "IntegerConnectedButtonGroupPreference: ComposeView not found in holder",
                )
                return
            }

            val labels = entries.map { it.toString() }
            val values = entryValues.toList()
            val enabled = isEnabled
            val prefIcon = icon

            cv.setPreferenceContent {
                val view = LocalView.current
                ComposePreferenceRow(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    icon = prefIcon,
                ) {
                    val activeIndex = values.indexOf(selected.intValue).coerceAtLeast(0)
                    ConnectedButtonGroup(
                        labels = labels,
                        selectedIndex = activeIndex,
                        enabled = enabled,
                        onSelect = { index ->
                            val newValue = values.getOrNull(index) ?: return@ConnectedButtonGroup
                            if (newValue != selected.intValue && callChangeListener(newValue)) {
                                value = newValue
                            }
                        },
                    )
                }
            }
        }
    }
