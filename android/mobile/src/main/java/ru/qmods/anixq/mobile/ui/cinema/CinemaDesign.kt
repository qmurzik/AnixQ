package ru.qmods.anixq.mobile.ui.cinema

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ru.qmods.anixq.mobile.ui.Nav
import ru.qmods.anixq.mobile.ui.Tab
import ru.qmods.anixq.tv.data.Title

internal object Film {
    val canvas = Color(0xFF141614)
    val panel = Color(0xFF202420)
    val raised = Color(0xFF2B312A)
    val paper = Color(0xFFF2EBDD)
    val secondary = Color(0xFFB8BBAE)
    val ticket = Color(0xFFF1CB8E)
    val ink = Color(0xFF282319)
    val rule = Color(0xFF42483E)
    val edge = 22.dp
}

internal val CinemaNavigationHeight = 68.dp

@Composable
internal fun CinemaNavigation(nav: Nav) {
    Row(
        Modifier.fillMaxWidth().background(Film.canvas).navigationBarsPadding().height(CinemaNavigationHeight)
            .padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp))
            .background(Film.raised).border(1.dp, Film.rule, RoundedCornerShape(22.dp)).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            Triple(Tab.HOME, "Витрина", Icons.Default.Theaters),
            Triple(Tab.CATALOG, "Каталог", Icons.Default.GridView),
            Triple(Tab.SEARCH, "Поиск", Icons.Default.Search),
            Triple(Tab.LIBRARY, "Моё", Icons.Default.CollectionsBookmark),
        ).forEach { (tab, title, icon) ->
            val selected = nav.tab == tab
            val background by animateColorAsState(if (selected) Film.ticket else Color.Transparent, label = "tab")
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(17.dp))
                    .background(background)
                    .selectable(selected = selected, role = Role.Tab) { nav.tab = tab },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val color = if (selected) Film.ink else Film.paper
                Icon(icon, null, Modifier.size(19.dp), tint = color)
                Text(title, color = color, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun CinemaHeading(text: String, modifier: Modifier = Modifier, size: Int = 36) {
    Text(text, modifier, color = Film.paper, fontFamily = FontFamily.Serif, fontSize = size.sp,
        lineHeight = (size + 2).sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun CinemaLabel(text: String, modifier: Modifier = Modifier, color: Color = Film.secondary) {
    Text(text, modifier, color = color, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun CinemaIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Film.raised)
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(21.dp), tint = Film.paper)
    }
}

@Composable
internal fun CinemaHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Film.edge, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) CinemaIcon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
        CinemaHeading(title, Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun CinemaAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = false) {
    val shape = RoundedCornerShape(13.dp)
    val color = if (filled) Film.ink else Film.paper
    Row(modifier.heightIn(min = 46.dp).clip(shape).background(if (filled) Film.ticket else Film.raised)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp), tint = color)
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = color, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun CinemaArt(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(Brush.linearGradient(listOf(Film.raised, Film.panel)))) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = scale)
    }
}

@Composable
internal fun CinemaPoster(title: Title, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp))) {
            CinemaArt(title.thumb, Modifier.fillMaxSize())
            title.rating?.takeIf { it > 0.0 }?.let {
                Text("%.1f".format(it), Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(Film.canvas.copy(alpha = .88f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Film.ticket, fontSize = 11.sp)
            }
        }
        Text(title.title, Modifier.padding(top = 7.dp, start = 1.dp, end = 1.dp), color = Film.paper,
            fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        title.year?.let { CinemaLabel(it.toString(), Modifier.padding(start = 1.dp, top = 2.dp)) }
    }
}

@Composable
internal fun CinemaRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Film.panel)
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 13.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Film.ticket)
        Column(Modifier.weight(1f)) {
            Text(title, color = Film.paper, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { CinemaLabel(it, Modifier.padding(top = 3.dp)) }
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(19.dp), tint = Film.secondary)
    }
}

@Composable
internal fun CinemaStatus(message: String?, loading: Boolean = false, action: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Film.edge, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) CircularProgressIndicator(color = Film.ticket, modifier = Modifier.size(28.dp))
        message?.let { CinemaLabel(it) }
        if (action != null) CinemaAction("Повторить", action)
    }
}
