"""Verify unified packaging and reversible migration from the two-file installation."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import package_mod


class UnifiedModChecks(unittest.TestCase):
    def test_artifact_contains_one_manifest_code_and_assets(self):
        with zipfile.ZipFile(package_mod.ROOT/'dist'/package_mod.NAME) as jar:
            self.assertEqual(jar.namelist().count('manifest.json'),1)
            manifest=json.loads(jar.read('manifest.json'))
            self.assertEqual(manifest['Name'],'AdvancedStorage')
            self.assertTrue(manifest['IncludesAssetPack'])
            self.assertEqual(manifest['Main'],'pedro.advancedstorage.AdvancedStoragePlugin')
            self.assertEqual(jar.read('pedro/advancedstorage/AdvancedStoragePlugin.class')[:4],bytes.fromhex('cafebabe'))
            self.assertIn('Server/Item/Items/AdvancedStorage/AdvancedStorage_Seal_Archive.json',jar.namelist())
            self.assertIn('Common/UI/Custom/AdvancedStorage/Catalog.ui',jar.namelist())

    def test_migration_keeps_one_mod_and_backs_up_previous_files(self):
        with tempfile.TemporaryDirectory(prefix='advanced-storage-package-') as temporary:
            root=Path(temporary);mods=root/'Mods';mods.mkdir()
            source=root/'source.jar';source.write_bytes(b'unified')
            for old in package_mod.LEGACY: (mods/old).write_bytes(old.encode())
            (mods/'other-mod.jar').write_bytes(b'unrelated')
            with contextlib.redirect_stdout(io.StringIO()): package_mod.install(source,mods)
            self.assertEqual((mods/package_mod.NAME).read_bytes(),b'unified')
            self.assertEqual((mods/'other-mod.jar').read_bytes(),b'unrelated')
            for old in package_mod.LEGACY:
                self.assertFalse((mods/old).exists())
                backups=list((root/'AdvancedStorageBackups').glob('*/'+old))
                self.assertEqual(len(backups),1)
                self.assertEqual(backups[0].read_bytes(),old.encode())

    def test_locked_legacy_file_rolls_back_migration(self):
        with tempfile.TemporaryDirectory(prefix='advanced-storage-package-') as temporary:
            root=Path(temporary);mods=root/'Mods';mods.mkdir()
            source=root/'source.jar';source.write_bytes(b'unified')
            for old in package_mod.LEGACY: (mods/old).write_bytes(old.encode())
            replace=package_mod.os.replace
            def locked(old,new):
                if Path(old).parent==mods and Path(old).name==package_mod.LEGACY[1]: raise PermissionError('locked')
                return replace(old,new)
            with patch.object(package_mod.os,'replace',side_effect=locked):
                with self.assertRaises(PermissionError): package_mod.install(source,mods)
            for old in package_mod.LEGACY: self.assertEqual((mods/old).read_bytes(),old.encode())
            self.assertFalse((mods/package_mod.NAME).exists())
            self.assertFalse(list(mods.glob('*.pending')))


if __name__=='__main__': unittest.main(verbosity=2)
