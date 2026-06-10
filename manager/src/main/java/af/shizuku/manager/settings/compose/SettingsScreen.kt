package af.shizuku.manager.settings.compose

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import af.shizuku.manager.R
import af.shizuku.manager.settings.SettingsSearchEngine
import android.widget.FrameLayout
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    title: String,
    onNavigateUp: () -> Unit,
    onNavigateToSetting: (SettingsSearchEngine.SettingItem) -> Unit,
    searchResults: List<SettingsSearchEngine.SettingItem>,
    onSearchQueryChanged: (String) -> Unit,
    onContainerCreated: () -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
        onSearchQueryChanged("")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { 
                                searchQuery = it
                                onSearchQueryChanged(it)
                            },
                            placeholder = { Text("Search settings…") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { /* Handle search if needed */ })
                        )
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    } else {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearchActive) {
                            isSearchActive = false
                            searchQuery = ""
                            onSearchQueryChanged("")
                        } else {
                            onNavigateUp()
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back_24),
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search_24),
                                contentDescription = "Search"
                            )
                        }
                    } else if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { 
                            searchQuery = ""
                            onSearchQueryChanged("")
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close_24),
                                contentDescription = "Clear"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Fragment Container for Preferences
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        id = R.id.fragment_container
                        clipToPadding = false
                        post { onContainerCreated() }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
            )

            // Search Overlay
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (searchQuery.isNotBlank()) {
                        if (searchResults.isEmpty()) {
                            Text(
                                text = "No settings found",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(searchResults) { item ->
                                    SearchResultItem(item = item, onClick = {
                                        isSearchActive = false
                                        searchQuery = ""
                                        onSearchQueryChanged("")
                                        onNavigateToSetting(item)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultItem(
    item: SettingsSearchEngine.SettingItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = item.category ?: "Settings",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            if (!item.summary.isNullOrEmpty()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
