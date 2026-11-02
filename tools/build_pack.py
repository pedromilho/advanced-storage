"""Export the Blockbench source and build an installable Hytale asset pack.

No game assets are redistributed. Coordinate, quaternion and face conventions
follow Hypixel's official Blockbench exporter (see README.md).
"""
from __future__ import annotations

import argparse
import base64
import itertools
import json
import math
import os
from pathlib import Path
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = ROOT / 'modelos' / 'Seal Archive - Textured v2.bbmodel'
CONDUIT_MODEL = ROOT / 'modelos' / 'RunicConduit - Textured.bbmodel'
CORE_MODEL = ROOT/'modelos/corrected/ArcaneCore - Textured.bbmodel'
CATALOG_MODEL = ROOT/'modelos/corrected/RunicCatalog - Textured.bbmodel'
SEAL_MODELS = {f'Storage_Seal_Tier_{tier}':ROOT/f'modelos/seals/Storage Seal - Tier {tier}.bbmodel'
               for tier in range(1,6)}
CONDUIT_VARIANTS = {
    'Runic_Conduit_Curve': ROOT/'modelos/corrected/RunicConduit - curve.bbmodel',
    'Runic_Conduit_T': ROOT/'modelos/corrected/RunicConduit - T.bbmodel',
    'Runic_Conduit_Cross': ROOT/'modelos/corrected/RunicConduit - 4connections.bbmodel',
    'Runic_Conduit_Up': ROOT/'modelos/corrected/RunicConduit - T-cima.bbmodel',
    'Runic_Conduit_UpDown': ROOT/'modelos/corrected/RunicConduit - T-cima-baixo.bbmodel',
    'Runic_Conduit_All': ROOT/'modelos/corrected/RunicConduit - all.bbmodel',
    'Runic_Conduit_Corner3': ROOT/'modelos/corrected/RunicConduit - corner3.generated.bbmodel'
}
DIRECTIONS = {'north': 'back', 'south': 'front', 'east': 'right',
              'west': 'left', 'up': 'top', 'down': 'bottom'}


def vector(values):
    return dict(zip('xyz', values))


def subtract(a, b):
    return [x - y for x, y in zip(a, b)]


def multiply(a, b):
    x, y, z, w = a
    X, Y, Z, W = b
    return [w*X+x*W+y*Z-z*Y, w*Y-x*Z+y*W+z*X,
            w*Z+x*Y-y*X+z*W, w*W-x*X-y*Y-z*Z]


def quaternion(rotation):
    # Blockbench cubes use ZYX Euler order: q = qZ * qY * qX.
    result = [0, 0, 0, 1]
    for axis, degrees in enumerate(rotation):
        angle = math.radians(degrees) / 2
        q = [0, 0, 0, math.cos(angle)]
        q[axis] = math.sin(angle)
        result = multiply(q, result)
    return dict(zip('xyzw', [round(v, 12) for v in result]))


def export_model(source, *, turn_to_player=True):
    textures = source.get('textures', [])
    if len(textures) != 1:
        raise ValueError('This exporter expects one texture atlas.')
    texture = textures[0]
    uri = texture.get('source', '')
    if not uri.startswith('data:image/png;base64,'):
        raise ValueError('Save the texture embedded in the .bbmodel first.')
    png = base64.b64decode(uri.split(',', 1)[1], validate=True)
    if png[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Embedded texture is not a PNG.')
    width, height = struct.unpack('>II', png[16:24])
    uv_width = texture.get('uv_width', source['resolution']['width'])
    uv_height = texture.get('uv_height', source['resolution']['height'])
    if (width, height) != (uv_width, uv_height):
        raise ValueError('Pixel coordinates must match atlas UV dimensions.')
    elements = {el['uuid']: el for el in source['elements']}
    groups = {g['uuid']: g for g in source.get('groups', [])}
    counter = itertools.count(1)
    exported = []

    def node(ref, parent_origin):
        is_group = isinstance(ref, dict)
        obj = groups[ref['uuid']] if is_group else elements[ref]
        if not obj.get('export', True):
            return None
        origin = obj.get('origin', [0, 0, 0])
        result = {
            'id': str(next(counter)), 'name': obj['name'],
            'position': vector(subtract(origin, parent_origin)),
            'orientation': quaternion(obj.get('rotation', [0, 0, 0])),
            'shape': {
                'type': 'none', 'offset': vector([0, 0, 0]),
                'stretch': vector([1, 1, 1]), 'settings': {},
                'textureLayout': {}, 'unwrapMode': 'custom',
                'visible': obj.get('visibility', True),
                'doubleSided': obj.get('double_sided', False),
                'shadingMode': obj.get('shading_mode', 'flat')
            }
        }
        if is_group:
            result['children'] = [n for c in ref.get('children', [])
                                  if (n := node(c, origin)) is not None]
            return result
        if obj.get('type') != 'cube':
            raise ValueError('Only cuboids are currently supported.')
        size = subtract(obj['to'], obj['from'])
        zero_axes = [i for i, length in enumerate(size) if abs(length) < 1e-7]
        if len(zero_axes) > 1:
            raise ValueError('A model element must be a box or a single plane.')
        quad = bool(zero_axes)
        face_dims = {'north': (size[0], size[1]), 'south': (size[0], size[1]),
                     'east': (size[2], size[1]), 'west': (size[2], size[1]),
                     'up': (size[0], size[2]), 'down': (size[0], size[2])}
        densities = []
        for name, face in obj['faces'].items():
            if face.get('texture') is None:
                continue
            if face.get('rotation', 0) != 0:
                raise ValueError('Rotated UV faces require an exporter update.')
            a, b, c, d = face['uv']
            fw, fh = face_dims[name]
            densities += [abs(c-a)/fw, abs(d-b)/fh]
            if min(a, c) < 0 or max(a, c) > width or min(b, d) < 0 or max(b, d) > height:
                raise ValueError(f'UV outside atlas on {obj["uuid"]}/{name}')
            direction = 'front' if quad else DIRECTIONS[name]
            result['shape']['textureLayout'][direction] = {
                'offset': {'x': round(a), 'y': round(b)},
                'mirror': {'x': a > c, 'y': b > d}, 'angle': 0
            }
            # Alpha in the PNG alone does not enable the model's transparent pass.
            # Match the official Hytale exporter's per-face transparency flag.
            if face.get('transparent', False):
                result['shape']['textureLayout'][direction]['transparent'] = True
        if not densities or any(abs(v-densities[0]) > 1e-7 for v in densities):
            raise ValueError('Each cube needs consistent texture pixel density.')
        density = densities[0]
        # Hytale derives UV extents from shape size. Use inverse stretch to
        # preserve the 32-unit geometry while retaining our two pixels/unit UVs.
        shape = result['shape']
        shape['type'] = 'box'
        shape['settings'] = {'size': vector([v*density for v in size]), 'isStaticBox': True}
        shape['stretch'] = vector([v/density for v in obj.get('stretch', [1, 1, 1])])
        shape['offset'] = vector([(a+b)/2-o for a, b, o in zip(obj['from'], obj['to'], origin)])
        if quad:
            used_face = next(name for name, f in obj['faces'].items() if f.get('texture') is not None)
            normal = {'north':'-Z','south':'+Z','east':'+X','west':'-X','up':'+Y','down':'-Y'}[used_face]
            axis = zero_axes[0]
            flat_size = [size[2] if axis == 0 else size[0], size[2] if axis == 1 else size[1]]
            shape['type'] = 'quad'
            shape['settings'] = {'size':dict(zip('xy',[v*density for v in flat_size])),
                                 'normal':normal,'isStaticBox':True}
        exported.append((obj, result, density))
        return result

    nodes = [n for ref in source['outliner'] if (n := node(ref, [0, 0, 0])) is not None]
    if len(exported) != len([e for e in source['elements'] if e.get('export', True)]):
        raise ValueError('Some source elements are missing from the outliner.')
    for original, output, density in exported:
        shape = output['shape']
        if shape['type'] == 'quad':
            w,h = [shape['settings']['size'][k] for k in 'xy']
            normal = shape['settings']['normal'][-1]
            sizes = {'X':[0,h,w],'Y':[w,0,h],'Z':[w,h,0]}[normal]
        else:
            sizes = [shape['settings']['size'][axis] for axis in 'xyz']
        for i, (axis, a, b) in enumerate(zip('xyz', original['from'], original['to'])):
            expected = (b-a)*original.get('stretch', [1, 1, 1])['xyz'.index(axis)]
            assert math.isclose(sizes[i]*shape['stretch'][axis], expected,abs_tol=1e-7)
        q = output['orientation']
        assert math.isclose(sum(v*v for v in q.values()), 1, abs_tol=1e-9)
    # The source's drawer side faces -Z. Rotate the exported prop by 180°
    # so that the game's normal NESW placement presents it to the player.
    # Apply this at the roots so every child, texture and pivot stays aligned.
    for root in nodes if turn_to_player else []:
        root['position']['x'] *= -1
        root['position']['z'] *= -1
        orientation = multiply([0, 1, 0, 0], [root['orientation'][k] for k in 'xyzw'])
        root['orientation'] = dict(zip('xyzw', orientation))
    return {'nodes': nodes, 'format': 'prop', 'lod': 'auto'}, png, len(exported)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, default=DEFAULT_MODEL)
    parser.add_argument('--conduit-model', type=Path, default=CONDUIT_MODEL)
    parser.add_argument('--install-to', type=Path, help='Hytale UserData/Mods directory')
    args = parser.parse_args()
    if args.install_to:
        parser.error('Install the unified mod with tools/build_runtime.ps1 -InstallTo instead.')
    from crafting import apply_recipes
    apply_recipes()
    from prepare_tooltip_assets import prepare_tooltip_assets
    prepare_tooltip_assets()
    source = json.loads(args.model.read_text(encoding='utf-8-sig'))
    model, texture, count = export_model(source)
    pack = ROOT / 'build' / 'AdvancedStorage'
    # Merge staged assets; the ZIP includes only the explicit current file set.
    source_files = [p for p in (ROOT/'pack').rglob('*') if p.is_file()]
    staged = []
    for source_file in source_files:
        if source_file.suffix.lower() == '.png' and 'Icons' in source_file.parts:
            header = source_file.read_bytes()[:24]
            if header[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', header[16:24]) != (64, 64):
                raise ValueError(f'Inventory icon must be a 64 x 64 PNG: {source_file}')
        dest = pack/source_file.relative_to(ROOT/'pack')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, dest)
        staged.append(dest)
    conduit_source = json.loads(args.conduit_model.read_text(encoding='utf-8-sig'))
    conduit_model, conduit_texture, conduit_count = export_model(conduit_source, turn_to_player=False)
    models = [('Seal_Archive', model, texture), ('Runic_Conduit', conduit_model, conduit_texture)]
    core_source = json.loads(CORE_MODEL.read_text(encoding='utf-8-sig'))
    core_model, core_texture, _ = export_model(core_source, turn_to_player=False)
    models.append(('Arcane_Core', core_model, core_texture))
    catalog_source = json.loads(CATALOG_MODEL.read_text(encoding='utf-8-sig'))
    catalog_model, catalog_texture, _ = export_model(catalog_source, turn_to_player=False)
    models.append(('Runic_Catalog', catalog_model, catalog_texture))
    for variant, path in CONDUIT_VARIANTS.items():
        variant_source = json.loads(path.read_text(encoding='utf-8-sig'))
        variant_model, variant_texture, _ = export_model(variant_source, turn_to_player=False)
        models.append((variant, variant_model, variant_texture))
    for name,path in SEAL_MODELS.items():
        seal_source=json.loads(path.read_text(encoding='utf-8-sig'))
        seal_model,seal_texture,_=export_model(seal_source,turn_to_player=False)
        models.append((name,seal_model,seal_texture))
    for name, exported_model, png in models:
        category='Items' if name in SEAL_MODELS else 'Blocks'
        output = pack/f'Common/{category}/AdvancedStorage/{name}.blockymodel'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(exported_model, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
        texture_file = output.with_name(f'{name}_Texture.png')
        texture_file.write_bytes(png)
        staged += [output, texture_file]
    manifest = json.loads((pack/'manifest.json').read_text())
    archive = ROOT/'dist'/f'advanced-storage-{manifest["Version"]}.zip'
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(staged):
            z.write(path, path.relative_to(pack).as_posix())
    print(f'Exported {len(models)} models, including Arcane Core, without changing authored physical sizes.')
    print(f'Pack: {archive}')


if __name__ == '__main__':
    main()
