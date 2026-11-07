package ru.qmods.anixq.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Title

private val types = listOf(null to "Все типы", 16 to "Сериалы", 17 to "Фильмы", 20 to "OVA", 21 to "ONA", 19 to "Спешлы")

@Composable
fun SearchScreen(nav: Nav) {
    var text by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(CatalogSort.POPULAR) }
    var type by rememberSaveable { mutableStateOf<Int?>(null) }
    var ongoing by rememberSaveable { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Title>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(Store.recentSearches) }
    val focus = LocalFocusManager.current
    val query = CatalogQuery(sort = sort, type = type, status = if (ongoing) 1 else null, text = text.trim())

    LaunchedEffect(query) {
        if (query.text.isNotEmpty()) delay(350)
        loading = true; error = null
        try {
            val (list, m) = Api.catalog(query, 1)
            items = list; page = 1; more = m
        } catch (e: Exception) { error = e.message; items = emptyList() }
        loading = false
    }
    fun saveRecent(q: String) { if (q.isNotBlank()) { recent = (listOf(q) + recent.filter { it != q }).take(8); Store.recentSearches = recent } }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            text, { text = it },
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            placeholder = { Text("Название аниме") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Default.Close, "Очистить") } },
            singleLine = true, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { saveRecent(text.trim()); focus.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Pal.card, focusedContainerColor = Pal.card, unfocusedBorderColor = Pal.card),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (text.isBlank()) items(recent) { r -> AssistChip(onClick = { text = r }, label = { Text(r) }, leadingIcon = { Icon(Icons.Default.History, null) }) }
            items(CatalogSort.entries) { s -> Chip(sort == s, s.title) { sort = s } }
            item { Chip(ongoing, "Онгоинги") { ongoing = !ongoing } }
            items(types) { (id, name) -> Chip(type == id, name) { type = id } }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.id }) { t ->
                PosterCard(t, width = 200.dp) { saveRecent(text.trim()); nav.open(t) }
            }
            if (items.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Centered { Text(error ?: if (text.isBlank()) "" else "Ничего не нашлось", color = Pal.muted) }
            }
            if (loading && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            if (more && items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(items.size) {
                    runCatching { Api.catalog(query, page + 1) }.onSuccess { (list, m) ->
                        val seen = items.map { it.id }.toHashSet()
                        items = items + list.filter { seen.add(it.id) }; page += 1; more = m && list.isNotEmpty()
                    }.onFailure { more = false }
                }
                Loading()
            }
        }
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) = FilterChip(
    selected, onClick, label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Pal.pink.copy(alpha = 0.25f), selectedLabelColor = Pal.text),
)
