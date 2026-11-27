package ru.qmods.anixq.mobile.ui

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.qmods.anixq.mobile.ui.cinema.CinemaNavigation
import ru.qmods.anixq.mobile.ui.cinema.CinemaSettings
import ru.qmods.anixq.tv.data.Title

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CinemaInterfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cinemaTabsKeepTheMinimizedPlayer() {
        val nav = Nav()
        val route = PlayerRoute(Title(1, "title"), episodeId = 4, startAt = 75.0)
        rule.setContent { CinemaNavigation(nav) }
        rule.runOnIdle {
            nav.play(route)
            nav.minimizePlayer()
        }
        rule.onNodeWithText("Каталог").performClick().assertIsSelected()
        rule.onNodeWithText("Поиск").performClick().assertIsSelected()
        rule.onNodeWithText("Моё").performClick().assertIsSelected()
        rule.runOnIdle {
            assertEquals(Tab.LIBRARY, nav.tab)
            assertSame(route, nav.playerRoute)
        }
    }

    @Test fun cinemaPreferenceSurvivesInitializationAndSettingsCanRestoreClassic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("looks", Context.MODE_PRIVATE)
        rule.runOnIdle {
            prefs.edit().remove("interface").commit()
            Looks.init(context)
            assertEquals(Looks.Interface.CLASSIC, Looks.interfaceMode)
            Looks.setInterface(Looks.Interface.CINEMA)
            Looks.init(context)
            assertEquals(Looks.Interface.CINEMA, Looks.interfaceMode)
        }
        try {
            rule.setContent { CinemaSettings(Nav()) }
            rule.onNodeWithText("Классический").performClick()
            rule.runOnIdle {
                Looks.init(context)
                assertEquals(Looks.Interface.CLASSIC, Looks.interfaceMode)
            }
        } finally {
            rule.runOnIdle {
                prefs.edit().remove("interface").commit()
                Looks.init(context)
            }
        }
    }
}
