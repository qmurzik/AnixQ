package ru.qmods.anixq.tv.focus

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.ui.AnixTheme
import ru.qmods.anixq.tv.ui.App
import ru.qmods.anixq.tv.ui.Nav
import ru.qmods.anixq.tv.ui.Tab

/** Remote-control navigation on the real Compose focus engine (JVM, no device). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class FocusTest {
    @get:Rule val rule = createComposeRule()

    private fun press(k: Key) { rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    /** Text of the focused element (merged), or "" if none. */
    private fun focusedText(): String {
        val nodes = rule.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes()
        return nodes.joinToString { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text } +
            n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun everyTabReachableAndDownEntersContent() {
        Store.init(ApplicationProvider.getApplicationContext())
        val nav = Nav()
        rule.setContent { AnixTheme { App(nav) } }
        rule.waitForIdle()
        rule.onAllNodesWithText("Главная")[0].performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val tabs = listOf(Tab.HOME, Tab.CATALOG, Tab.SEARCH, Tab.LIBRARY, Tab.SETTINGS)
        val labels = tabs.map { it.label }
        for ((i, tab) in tabs.withIndex()) {
            if (i > 0) press(Key.DirectionRight)
            assertEquals("walking right along the bar", tab, nav.tab)
            assertTrue("bar tab ${tab.label} focused, got '${focusedText()}'", focusedText().contains(tab.label))
            press(Key.DirectionDown)
            val f = focusedText()
            assertFalse("down from ${tab.label} must leave the bar (focused: '$f')", labels.any { f == it } || f.isEmpty())
            assertEquals("down must not switch tabs", tab, nav.tab)
            press(Key.DirectionUp)
            // Up from the first row returns to the current tab.
            assertTrue("up from ${tab.label} content returns to its tab, got '${focusedText()}'", focusedText().contains(tab.label))
        }
    }
}
