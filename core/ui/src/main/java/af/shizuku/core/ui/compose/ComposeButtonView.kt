package af.shizuku.core.ui.compose

import android.content.Context
import android.util.AttributeSet
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class ComposeButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    var text: CharSequence by mutableStateOf("")
    private var onClickListener: OnClickListener? = null
    var isEnabledState by mutableStateOf(true)

    init {
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, intArrayOf(android.R.attr.text, android.R.attr.enabled))
            text = typedArray.getString(0) ?: ""
            isEnabledState = typedArray.getBoolean(1, true)
            typedArray.recycle()
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
        onClickListener = l
    }
    
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isEnabledState = enabled
    }

    fun setText(resId: Int) {
        text = context.getString(resId)
    }

    fun setText(newText: CharSequence?) {
        text = newText ?: ""
    }

    fun setIconTintResource(resId: Int) {
        // stub to avoid compilation error
    }

    @Composable
    override fun Content() {
        Button(
            size = ButtonSize.Medium,
            onClick = { onClickListener?.onClick(this@ComposeButtonView) },
            enabled = isEnabledState
        ) {
            Text(text.toString())
        }
    }
}
