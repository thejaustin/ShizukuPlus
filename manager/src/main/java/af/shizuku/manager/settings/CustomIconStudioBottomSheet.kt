package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.settings.IconStudioRenderer.Glyph
import af.shizuku.manager.settings.IconStudioRenderer.GlyphColor
import af.shizuku.manager.settings.IconStudioRenderer.Shape
import af.shizuku.manager.settings.IconStudioRenderer.Source
import af.shizuku.manager.settings.IconStudioRenderer.State
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.widget.AppSwitch
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CustomIconStudioBottomSheet : BottomSheetDialogFragment() {
    private var initialUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUri = arguments?.getParcelable(ARG_URI)
    }

    override fun onStart() {
        super.onStart()
        // The studio is a full editor; open it expanded instead of half-collapsed.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PreferenceComposeTheme {
                    StudioContent(
                        initialUri = initialUri,
                        onPinShortcut = { bitmap, appName ->
                            pinShortcut(bitmap, appName)
                            dismiss()
                        },
                        onSavePng = { bitmap, appName -> savePngToStorage(bitmap, appName) },
                    )
                }
            }
        }

    private fun pinShortcut(
        bitmap: Bitmap,
        appName: String,
    ) {
        val context = requireContext()
        val finalName = appName.trim().ifBlank { getString(R.string.app_name) }
        try {
            FileOutputStream(File(context.filesDir, "custom_launcher_icon.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            ShizukuSettings.setCustomAppName(finalName)

            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                val intent =
                    Intent(context, af.shizuku.manager.MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                val info =
                    ShortcutInfoCompat
                        .Builder(context, "custom_shizuku_shortcut")
                        .setIcon(IconCompat.createWithBitmap(bitmap))
                        .setShortLabel(finalName)
                        .setLongLabel(finalName)
                        .setIntent(intent)
                        .build()
                // An already-pinned shortcut with this id is updated in place, not duplicated.
                ShortcutManagerCompat.updateShortcuts(context, listOf(info))
                ShortcutManagerCompat.requestPinShortcut(context, info, null)
                Toast.makeText(context, R.string.custom_icon_studio_pinned_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.custom_icon_studio_pinned_error, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to pin custom icon shortcut")
            Toast.makeText(context, R.string.custom_icon_studio_pinned_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePngToStorage(
        bitmap: Bitmap,
        appName: String,
    ) {
        val context = requireContext()
        val safeName =
            appName
                .trim()
                .replace("+", "_plus")
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .lowercase()
                .ifBlank { "shizukuplus" }
        val fileName = "${safeName}_icon.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage: MediaStore is the supported way to write into Downloads.
                val resolver = context.contentResolver
                val values =
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/ShizukuPlus/Icons",
                        )
                    }
                val uri =
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw java.io.IOException("MediaStore insert returned null")
                try {
                    val written =
                        resolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        } ?: false
                    if (!written) throw java.io.IOException("PNG compress failed")
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                Toast.makeText(context, R.string.custom_icon_studio_saved_success, Toast.LENGTH_LONG).show()
            } else {
                // No storage permission on older releases: keep it in the app's own external
                // folder (visible over USB / in file managers under Android/data).
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: throw java.io.IOException("no external files dir")
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Toast.makeText(context, getString(R.string.custom_icon_studio_saved_to, file.absolutePath), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to save icon PNG")
            Toast.makeText(context, R.string.custom_icon_studio_saved_error, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_URI = "arg_image_uri"

        fun newInstance(uri: Uri? = null): CustomIconStudioBottomSheet =
            CustomIconStudioBottomSheet().apply {
                if (uri != null) {
                    arguments = Bundle().apply { putParcelable(ARG_URI, uri) }
                }
            }
    }
}

private val CURATED_COLORS =
    listOf(
        0xFF000000.toInt(),
        0xFFFFFFFF.toInt(),
        0xFF1E88E5.toInt(),
        0xFF3949AB.toInt(),
        0xFF8E24AA.toInt(),
        0xFFD81B60.toInt(),
        0xFFF4511E.toInt(),
        0xFFFFB300.toInt(),
        0xFF43A047.toInt(),
        0xFF00897B.toInt(),
        0xFF546E7A.toInt(),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioContent(
    initialUri: Uri?,
    onPinShortcut: (Bitmap, String) -> Unit,
    onSavePng: (Bitmap, String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ShizukuSettings.getPreferences() }

    var state by remember { mutableStateOf(IconStudioRenderer.load(prefs)) }
    var appName by remember { mutableStateOf(ShizukuSettings.getCustomAppName(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var darkPreview by remember { mutableStateOf(true) }
    var image by remember { mutableStateOf<Bitmap?>(null) }

    fun update(transform: State.() -> State) {
        state = state.transform()
    }

    fun importUri(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { IconStudioRenderer.importImage(context, uri) }
            if (bmp != null) {
                image = bmp
                update { copy(source = Source.IMAGE) }
            } else {
                Toast.makeText(context, R.string.custom_icon_studio_image_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importUri(uri)
        }

    LaunchedEffect(Unit) {
        if (initialUri != null) {
            importUri(initialUri)
        } else {
            image = withContext(Dispatchers.IO) { IconStudioRenderer.loadImportedImage(context) }
            if (image == null && state.source == Source.IMAGE) update { copy(source = Source.PRESET) }
        }
    }
    // Remember the last design (debounced so slider drags don't hammer SharedPreferences).
    LaunchedEffect(state) {
        delay(400)
        IconStudioRenderer.save(prefs, state)
    }

    val scheme = MaterialTheme.colorScheme
    val palette =
        IconStudioRenderer.Palette(
            accent = scheme.primary.toArgb(),
            accentContainer = scheme.primaryContainer.toArgb(),
            onAccentContainer = scheme.onPrimaryContainer.toArgb(),
        )

    val presetBitmap by produceState<Bitmap?>(null, state.glyph) {
        value = withContext(Dispatchers.Default) { IconStudioRenderer.glyphBitmap(context, state.glyph) }
    }
    val thumbnails by produceState<Map<Glyph, Bitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { Glyph.entries.associateWith { IconStudioRenderer.glyphBitmap(context, it, 128) } }
    }
    // Render off the main thread; a new state cancels the stale render. The previous bitmap stays
    // on screen meanwhile, so dragging a slider never flashes a spinner.
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state, palette, presetBitmap, image) {
        val bmp =
            withContext(Dispatchers.Default) {
                IconStudioRenderer.render(state, palette, presetBitmap, image)
            }
        rendered = bmp
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .background(scheme.outlineVariant, RoundedCornerShape(2.dp)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_custom_icon_studio_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    HapticUtils.tap(view)
                    state = State()
                }) {
                    Icon(painterResource(R.drawable.ic_restore_24), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.custom_icon_studio_reset))
                }
            }

            Spacer(Modifier.height(12.dp))
            PreviewCard(rendered, appName, state.shape, darkPreview) { darkPreview = it }
            Spacer(Modifier.height(16.dp))

            NameField(appName) { appName = it }
            Spacer(Modifier.height(12.dp))

            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf(
                    R.string.custom_icon_studio_tab_icon,
                    R.string.custom_icon_studio_shape,
                    R.string.custom_icon_studio_tab_colors,
                    R.string.custom_icon_studio_tab_adjust,
                ).forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = {
                            HapticUtils.segmentTick(view)
                            tab = i
                        },
                        text = { Text(stringResource(label), maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "studioTab",
            ) { page ->
                Column {
                    when (page) {
                        0 -> IconPage(state, thumbnails, ::update, onPickPhoto = { gallery.launch("image/*") })
                        1 -> ShapePage(state, ::update)
                        2 -> ColorsPage(state, palette, ::update)
                        else -> AdjustPage(state, ::update)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { rendered?.let { onSavePng(it, appName) } },
                enabled = rendered != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_install_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.custom_icon_studio_save_png_short), maxLines = 2)
            }
            Button(
                onClick = {
                    IconStudioRenderer.save(prefs, state)
                    rendered?.let { onPinShortcut(it, appName) }
                },
                enabled = rendered != null,
                modifier = Modifier.weight(1.4f),
            ) {
                Icon(painterResource(R.drawable.ic_sparkle_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.custom_icon_studio_pin_shortcut), maxLines = 2)
            }
        }
    }
}

@Composable
private fun PreviewCard(
    icon: Bitmap?,
    appName: String,
    shape: Shape,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
) {
    val view = LocalView.current
    val wallpaper =
        if (dark) {
            listOf(Color(0xFF1B2440), Color(0xFF2B1C3A), Color(0xFF101418))
        } else {
            listOf(Color(0xFFDDE7FF), Color(0xFFF4E1F0), Color(0xFFF7F3EA))
        }
    val labelColor = if (dark) Color.White else Color(0xFF1B1B1F)
    val ghost = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(wallpaper)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(128.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            // A home-screen row so the icon is judged at real size, next to neighbours.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(4) { i ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                        if (i == 1 && icon != null) {
                            Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(54.dp))
                        } else {
                            ShapeSwatch(shape, ghost, Modifier.size(54.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        if (i == 1) {
                            Text(
                                text = appName.ifBlank { stringResource(R.string.app_name) },
                                color = labelColor,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                            )
                        } else {
                            Box(Modifier.size(width = 40.dp, height = 8.dp).background(ghost, RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
        // Wallpaper tone toggle
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(role = Role.Switch) {
                        HapticUtils.tap(view)
                        onDarkChange(!dark)
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(if (dark) R.string.custom_icon_studio_preview_dark else R.string.custom_icon_studio_preview_light),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameField(
    appName: String,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = appName,
        onValueChange = { onChange(it.take(30)) },
        label = { Text(stringResource(R.string.custom_icon_studio_app_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        shape = RoundedCornerShape(16.dp),
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Brand names: intentionally not translated.
        listOf("Shizuku+", "Shizuku", "S+").forEach { s ->
            SuggestionChip(onClick = { onChange(s) }, label = { Text(s) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPage(
    state: State,
    thumbnails: Map<Glyph, Bitmap>,
    update: (State.() -> State) -> Unit,
    onPickPhoto: () -> Unit,
) {
    val view = LocalView.current
    val focus = LocalFocusManager.current
    SectionLabel(R.string.custom_icon_studio_glyph_preset)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph.entries.forEachIndexed { i, g ->
            val desc = stringResource(R.string.custom_icon_studio_preset_cd, i + 1)
            SelectableTile(
                selected = state.source == Source.PRESET && state.glyph == g,
                description = desc,
                onClick = {
                    HapticUtils.tap(view)
                    update { copy(source = Source.PRESET, glyph = g) }
                },
            ) {
                thumbnails[g]?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        SelectableTile(
            selected = state.source == Source.IMAGE,
            description = stringResource(R.string.custom_icon_studio_source_photo),
            onClick = {
                HapticUtils.tap(view)
                onPickPhoto()
            },
        ) {
            Icon(painterResource(R.drawable.ic_folder_24), contentDescription = null, modifier = Modifier.size(26.dp))
        }
        SelectableTile(
            selected = state.source == Source.TEXT,
            description = stringResource(R.string.custom_icon_studio_source_text),
            onClick = {
                HapticUtils.tap(view)
                update { copy(source = Source.TEXT) }
            },
        ) {
            Text("Aa", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
    }

    AnimatedVisibility(visible = state.source == Source.TEXT) {
        Column {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.text,
                onValueChange = { v -> update { copy(text = v.take(3)) } },
                label = { Text(stringResource(R.string.custom_icon_studio_monogram)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
    AnimatedVisibility(visible = state.source == Source.IMAGE) {
        Column {
            Spacer(Modifier.height(16.dp))
            Segmented(
                options = listOf(R.string.custom_icon_studio_photo_fill, R.string.custom_icon_studio_photo_fit),
                selected = if (state.imageFill) 0 else 1,
            ) { i -> update { copy(imageFill = i == 0) } }
            TextButton(onClick = onPickPhoto) {
                Text(stringResource(R.string.custom_icon_studio_pick_gallery))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShapePage(
    state: State,
    update: (State.() -> State) -> Unit,
) {
    val view = LocalView.current
    val names =
        mapOf(
            Shape.SQUIRCLE to R.string.custom_icon_studio_shape_squircle,
            Shape.CIRCLE to R.string.custom_icon_studio_shape_circle,
            Shape.ONEUI to R.string.custom_icon_studio_shape_oneui,
            Shape.ROUNDED to R.string.custom_icon_studio_shape_rounded,
            Shape.COOKIE to R.string.custom_icon_studio_shape_cookie,
            Shape.TEARDROP to R.string.custom_icon_studio_shape_teardrop,
            Shape.HEXAGON to R.string.custom_icon_studio_shape_hexagon,
            Shape.SQUARE to R.string.custom_icon_studio_shape_square,
        )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Shape.entries.forEach { s ->
            val selected = state.shape == s
            val tint by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "shapeTint",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .width(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.RadioButton) {
                            HapticUtils.tap(view)
                            update { copy(shape = s) }
                        }.padding(vertical = 6.dp),
            ) {
                ShapeSwatch(s, tint, Modifier.size(48.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(names.getValue(s)),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsPage(
    state: State,
    palette: IconStudioRenderer.Palette,
    update: (State.() -> State) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val themeColors =
        listOf(
            scheme.primary.toArgb(),
            scheme.secondaryContainer.toArgb(),
            scheme.tertiaryContainer.toArgb(),
            scheme.tertiary.toArgb(),
        )

    SectionLabel(R.string.custom_icon_studio_bg)
    Segmented(
        options = listOf(R.string.custom_icon_studio_bg_solid, R.string.custom_icon_studio_bg_gradient),
        selected = if (state.gradient) 1 else 0,
    ) { i -> update { copy(gradient = i == 1, transparentBg = false) } }
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorDot(
            color = palette.accentContainer,
            selected = !state.transparentBg && state.bgColor == 0,
            description = stringResource(R.string.custom_icon_studio_bg_theme),
            badge = true,
        ) { update { copy(bgColor = 0, transparentBg = false) } }
        (themeColors + CURATED_COLORS).distinct().forEach { c ->
            ColorDot(c, selected = !state.transparentBg && state.bgColor == c, description = null) {
                update { copy(bgColor = c, transparentBg = false) }
            }
        }
        ColorDot(
            color = null,
            selected = state.transparentBg,
            description = stringResource(R.string.custom_icon_studio_bg_none),
        ) { update { copy(transparentBg = true, gradient = false) } }
    }
    Spacer(Modifier.height(10.dp))
    HueSlider(label = R.string.custom_icon_studio_custom_color) { c -> update { copy(bgColor = c, transparentBg = false) } }

    AnimatedVisibility(visible = state.gradient && !state.transparentBg) {
        Column {
            Spacer(Modifier.height(12.dp))
            SectionLabel(R.string.custom_icon_studio_gradient_end)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorDot(palette.accent, selected = state.bgColor2 == 0, description = stringResource(R.string.custom_icon_studio_bg_theme), badge = true) {
                    update { copy(bgColor2 = 0) }
                }
                (themeColors + CURATED_COLORS).distinct().forEach { c ->
                    ColorDot(c, selected = state.bgColor2 == c, description = null) { update { copy(bgColor2 = c) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            HueSlider(label = R.string.custom_icon_studio_custom_color) { c -> update { copy(bgColor2 = c) } }
            LabeledSlider(R.string.custom_icon_studio_gradient_angle, state.gradientAngle, 0f..360f, "${state.gradientAngle.toInt()}°") {
                update { copy(gradientAngle = it) }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel(R.string.custom_icon_studio_glyph_color)
    val glyphLabels =
        listOf(
            GlyphColor.AUTO to R.string.custom_icon_studio_gc_auto,
            GlyphColor.ORIGINAL to R.string.custom_icon_studio_gc_original,
            GlyphColor.WHITE to R.string.custom_icon_studio_gc_white,
            GlyphColor.BLACK to R.string.custom_icon_studio_gc_black,
            GlyphColor.ACCENT to R.string.custom_icon_studio_gc_accent,
        )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        glyphLabels.forEach { (gc, label) ->
            FilterChip(
                selected = state.glyphColor == gc,
                onClick = { update { copy(glyphColor = gc) } },
                label = { Text(stringResource(label)) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel(R.string.custom_icon_studio_border)
    LabeledSlider(
        R.string.custom_icon_studio_border_width,
        state.borderWidth,
        0f..8f,
        if (state.borderWidth < 0.5f) stringResource(R.string.custom_icon_studio_off) else "${"%.1f".format(state.borderWidth)}%",
    ) { update { copy(borderWidth = if (it < 0.5f) 0f else it) } }
    AnimatedVisibility(visible = state.borderWidth > 0f) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), palette.accent, 0xFFFFD54F.toInt()) + themeColors).distinct().forEach { c ->
                ColorDot(c, selected = state.borderColor == c, description = null) { update { copy(borderColor = c) } }
            }
        }
    }
}

@Composable
private fun AdjustPage(
    state: State,
    update: (State.() -> State) -> Unit,
) {
    LabeledSlider(R.string.custom_icon_studio_size, state.glyphScale, 30f..100f, "${state.glyphScale.toInt()}%") {
        update { copy(glyphScale = it) }
    }
    LabeledSlider(R.string.custom_icon_studio_rotation, state.rotation, -180f..180f, "${state.rotation.toInt()}°") {
        // Snap to upright so "back to 0" is easy to hit with a finger.
        update { copy(rotation = if (kotlin.math.abs(it) < 4f) 0f else it) }
    }
    LabeledSlider(R.string.custom_icon_studio_nudge_x, state.offsetX, -25f..25f, "${state.offsetX.toInt()}") {
        update { copy(offsetX = if (kotlin.math.abs(it) < 1.5f) 0f else it) }
    }
    LabeledSlider(R.string.custom_icon_studio_nudge_y, state.offsetY, -25f..25f, "${state.offsetY.toInt()}") {
        update { copy(offsetY = if (kotlin.math.abs(it) < 1.5f) 0f else it) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.custom_icon_studio_shadow),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = state.shadow, onCheckedChange = { v -> update { copy(shadow = v) } })
    }
}

// ----- Building blocks -----

@Composable
private fun SectionLabel(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SelectableTile(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh, label = "tileBg")
    val ring by animateDpAsState(if (selected) 2.dp else 0.dp, label = "tileRing")
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bg)
                .border(ring, scheme.primary, RoundedCornerShape(18.dp))
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = description },
    ) { content() }
}

@Composable
private fun ColorDot(
    color: Int?,
    selected: Boolean,
    description: String?,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, label = "dotRing")
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(
                    if (color != null) {
                        Modifier.background(Color(color))
                    } else {
                        // "No background": checkerboard-ish diagonal so it reads as transparent.
                        Modifier.background(
                            Brush.linearGradient(
                                0f to Color.LightGray,
                                0.5f to Color.LightGray,
                                0.5f to Color.White,
                                1f to Color.White,
                            ),
                        )
                    },
                ).border(ring, ringColor, CircleShape)
                .clickable(role = Role.RadioButton) {
                    HapticUtils.tap(view)
                    onClick()
                }.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        if (badge) {
            Icon(
                painterResource(R.drawable.ic_palette_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HueSlider(
    label: Int,
    onColor: (Int) -> Unit,
) {
    var hue by remember { mutableStateOf(210f) }
    val rainbow = remember { (0..6).map { Color.hsv(it * 60f % 360f, 0.75f, 0.85f) } }
    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(12.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(rainbow)),
        )
        Slider(
            value = hue,
            onValueChange = {
                hue = it
                onColor(Color.hsv(it, 0.75f, 0.85f).toArgb())
            },
            valueRange = 0f..359f,
            colors =
                SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.hsv(hue, 0.75f, 0.85f),
                ),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

@Composable
private fun Segmented(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val view = LocalView.current
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, res ->
            SegmentedButton(
                selected = selected == i,
                onClick = {
                    HapticUtils.segmentTick(view)
                    onSelect(i)
                },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(stringResource(res), maxLines = 2) }
        }
    }
}

@Composable
private fun ShapeSwatch(
    shape: Shape,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val path = IconStudioRenderer.shapePath(shape, RectF(0f, 0f, size.width, size.height))
        drawPath(path.asComposePath(), color)
    }
}
