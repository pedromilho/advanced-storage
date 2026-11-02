"""Build and install a single plugin JAR containing the Advanced Storage asset pack."""
import argparse
import json
import os
from pathlib import Path
import shutil
import zipfile
from datetime import datetime

ROOT=Path(__file__).resolve().parents[1]
NAME='advanced-storage-0.1.0.jar'
LEGACY=('advanced-storage-0.1.0.zip','advanced-storage-runtime-0.1.0.jar')


def package():
    assets=ROOT/'dist/advanced-storage-0.1.0.zip'
    classes=ROOT/'build/runtime-classes'
    output=ROOT/'dist'/NAME
    with zipfile.ZipFile(assets) as source, zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as target:
        manifest=json.loads(source.read('manifest.json'))
        manifest['Main']='pedro.advancedstorage.AdvancedStoragePlugin'
        manifest['IncludesAssetPack']=True
        target.writestr('manifest.json',json.dumps(manifest,indent=2)+'\n')
        for name in source.namelist():
            if name!='manifest.json': target.writestr(name,source.read(name))
        for path in sorted(classes.rglob('*')):
            if path.is_file() and path.name!='manifest.json': target.write(path,path.relative_to(classes).as_posix())
    with zipfile.ZipFile(output) as result:
        assert result.namelist().count('manifest.json')==1
        assert 'pedro/advancedstorage/AdvancedStoragePlugin.class' in result.namelist()
        assert 'Common/UI/Custom/AdvancedStorage/Catalog.ui' in result.namelist()
        assert 'Server/Item/Items/AdvancedStorage/AdvancedStorage_Seal_Archive.json' in result.namelist()
        assert result.testzip() is None
    return output


def install(output,directory):
    directory=directory.resolve()
    directory.mkdir(parents=True,exist_ok=True)
    target=directory/NAME
    pending=directory/(NAME+'.pending')
    backup=directory.parent/'AdvancedStorageBackups'/datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    moved=[]
    shutil.copy2(output,pending)
    try:
        for name in (*LEGACY,NAME):
            old=directory/name
            if old.is_file():
                backup.mkdir(parents=True,exist_ok=True)
                saved=backup/name
                os.replace(old,saved)
                moved.append((old,saved))
        os.replace(pending,target)
    except Exception:
        for old,saved in reversed(moved): os.replace(saved,old)
        raise
    finally:
        pending.unlink(missing_ok=True)
    print(f'Installed unified mod: {target}')
    if moved: print(f'Previous files backed up: {backup}')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install-to',type=Path)
    args=parser.parse_args()
    output=package()
    if args.install_to: install(output,args.install_to)
    print(output)
