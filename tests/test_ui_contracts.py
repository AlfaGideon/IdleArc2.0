"""Source-level regression guards; these do not replace Android device UI testing."""
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "companion/overlay/app/src/main/java/com/idlearc/companion"


class UiContractsTest(unittest.TestCase):
    def test_widget_has_distinct_close_and_minimize_with_cleanup(self):
        text = (SRC / "OverlayService.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(text.count('setContentDescription("Закрыть виджет")'), 2)
        self.assertIn('setContentDescription("Свернуть виджет")', text)
        self.assertIn('ACTION_CLOSE.equals(intent.getAction())', text)
        self.assertIn('"Закрыть виджет", closePi', text)
        self.assertIn('uiHandler.removeCallbacks(autoSyncRunnable)', text)
        self.assertIn('LiveSyncManager.get().remove(this)', text)
        self.assertIn('wm.removeView(rootContainer)', text)

    def test_full_stance_guides_have_one_home_and_gear_has_item_summaries(self):
        text = (SRC / "DashboardCardRenderer.java").read_text(encoding="utf-8")
        self.assertEqual(text.count('root.addView(stanceGuideCard('), 1)
        self.assertEqual(text.count('root.addView(retaliationGuideCard('), 1)
        self.assertIn('pe.modelAlternative', text)
        self.assertIn('itemHints.subList', text)
        self.assertIn('collapsibleCard(ctx, "Аффиксы: "', text)
        self.assertNotIn('Лучшая замена:', text)

    def test_primary_tabs_are_kept_and_extra_tabs_accessible_in_both_surfaces(self):
        for filename in ("MainActivity.java", "OverlayService.java"):
            text = (SRC / filename).read_text(encoding="utf-8")
            self.assertIn('int[] tabOrder = {0, 1, 2, 7, 3, 4, 5, 6}', text)
            self.assertIn('tabStrip.addView(moreTabsButton)', text)
            self.assertIn('i >= 3 && i <= 6 && !showMoreTabs', text)
            self.assertIn('case 7:', text)


if __name__ == "__main__":
    unittest.main()
