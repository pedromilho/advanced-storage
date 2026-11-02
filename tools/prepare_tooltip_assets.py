"""Normalize native 2x tooltip patches to CustomUI's one-pixel texture coordinates."""
from io import BytesIO
from pathlib import Path
import zipfile
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]

def prepare_tooltip_assets():
    folder=ROOT/'pack/Common/UI/Custom/AdvancedStorage/Tooltip'
    folder.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile('D:/Games/Hytale/install/release/package/game/latest/Assets.zip') as source:
        for name in source.namelist():
            if not name.startswith('Common/UI/ItemQualities/Tooltips/') or not name.endswith('@2x.png'): continue
            image=Image.open(BytesIO(source.read(name))).convert('RGBA')
            # CustomUI PatchStyle.Border is in texture pixels. Native UI resolves @2x
            # implicitly, but our copied UI paths require a normalized base texture.
            image.resize(((image.width+1)//2,(image.height+1)//2),Image.Resampling.LANCZOS).save(folder/Path(name).name.replace('@2x',''))
            obsolete=folder/Path(name).name
            obsolete.unlink(missing_ok=True)

if __name__=='__main__': prepare_tooltip_assets()
