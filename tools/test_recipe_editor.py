"""Persistence and validation regression checks for the local recipe planner."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from recipe_editor import Catalog, RecipeStore, locate_assets


class RecipeEditorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = Catalog(locate_assets())

    @classmethod
    def tearDownClass(cls):
        cls.catalog.zip.close()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = RecipeStore(self.catalog, Path(self.temp.name)/'recipes.json')
        self.recipe = {'AdvancedStorage_Arcane_Core': {
            'ingredients': [{'itemId': 'Ingredient_Bar_Iron', 'quantity': 4}],
            'outputQuantity': 1, 'timeSeconds': 2.5, 'bench': 'Workbench',
            'notes': 'Temporary test recipe', 'status': 'ready'}}

    def test_real_icons_and_translations(self):
        self.assertEqual(len(self.catalog.targets), 9)
        iron = self.catalog.lookup['Ingredient_Bar_Iron']
        self.assertEqual(iron['name'], 'Iron Ingot')
        self.assertTrue(iron['namePt'])
        for item in self.catalog.targets + [iron]:
            self.assertTrue(self.catalog.icon(item['id']).startswith(b'\x89PNG\r\n\x1a\n'))

    def test_save_reload_and_backup(self):
        self.assertEqual(self.store.read()['recipes'], {})
        self.store.save(self.recipe, 0)
        reopened = RecipeStore(self.catalog, self.store.path)
        self.assertEqual(reopened.read()['recipes'], self.recipe)
        reopened.save({}, 1)
        backup = json.loads(self.store.path.with_suffix('.json.bak').read_text())
        self.assertEqual(backup['recipes'], self.recipe)

    def test_stale_editor_cannot_overwrite_newer_changes(self):
        self.store.save(self.recipe, 0)
        self.assertIsNone(self.store.save({}, 0))
        self.assertEqual(self.store.read()['recipes'], self.recipe)

    def test_reject_invalid_ingredients_quantities_and_incomplete_ready(self):
        for mutate in [
            lambda r: r['ingredients'][0].update(quantity=0),
            lambda r: r['ingredients'][0].update(quantity=True),
            lambda r: r['ingredients'][0].update(itemId='Not_A_Game_Item'),
            lambda r: r['ingredients'][0].update(itemId='AdvancedStorage_Arcane_Core'),
            lambda r: r.update(bench=''),
            lambda r: r.update(timeSeconds=float('nan')),
        ]:
            modified = copy.deepcopy(self.recipe)
            mutate(modified['AdvancedStorage_Arcane_Core'])
            with self.assertRaises(ValueError):
                self.store.save(modified, 0)
        self.assertFalse(self.store.path.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
