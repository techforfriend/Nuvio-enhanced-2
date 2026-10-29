package com.nuvio.app.features.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.search.SearchRepository

/**
 * Phase 4 TV Search: a text field (TV on-screen keyboard) plus result rails
 * from [SearchRepository] — same section model as Home, so [TvHomeRail] is
 * reused. BACK exits to the previous screen.
 */
@Composable
fun TvSearchScreen(
    onOpenDetails: (MetaPreview) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    val searchState by SearchRepository.uiState.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val searchFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        searchFieldFocusRequester.requestFocus()
    }

    fun submit() {
        if (query.isNotBlank()) {
            submittedQuery = query
            SearchRepository.search(query, addonsState.addons)
        }
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020404)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 16.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Type to search") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFieldFocusRequester),
                    )
                    Spacer(Modifier.width(12.dp))
                    TvControlButton(label = "Search", onClick = { submit() })
                }
            }
            if (searchState.isLoading) {
                item {
                    Text(
                        text = "Searching…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
                    )
                }
            } else if (submittedQuery != null && searchState.sections.isEmpty()) {
                item {
                    Text(
                        text = searchState.errorMessage ?: "No results found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
                    )
                }
            } else {
                items(searchState.sections, key = { it.key }) { section ->
                    TvHomeRail(
                        title = section.title,
                        subtitle = section.subtitle,
                        items = section.items,
                        onItemClick = onOpenDetails,
                    )
                }
            }
        }
    }
}
