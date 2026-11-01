package af.shizuku.manager.utils

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** [CardBorder] for Compose cards: pass as a Card's `border`; null with borders off. */
@Composable
fun cardBorder(): BorderStroke? = if (CardBorder.isEnabled()) BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant) else null
