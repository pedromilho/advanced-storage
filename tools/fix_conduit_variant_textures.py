"""Repack and texture the user's conduit variants; retain all geometry/hierarchy."""
import base64
import copy
import io
import json
from pathlib import Path
import uuid

import numpy as np
from PIL import Image, ImageDraw

from texture_runic_conduit import ROOT, NORMALS, corners, rotation, texture_patch, render
from build_pack import export_model

SOURCES = ['RunicConduit - curve.bbmodel', 'RunicConduit - T.bbmodel',
           'RunicConduit - 4connections.bbmodel']
VERTICAL_SOURCES = ['RunicConduit - T-cima.bbmodel',
                    'RunicConduit - T-cima-baixo.bbmodel', 'RunicConduit - all.bbmodel']
OUTPUT = ROOT/'modelos/corrected'


def geometry(model):
    """Resolve cuboids through every parent transform, including the rotated arm."""
    groups = {g['uuid']: g for g in model['groups']}
    elements = {e['uuid']: e for e in model['elements']}
    results = {}

    def walk(ref, parent_R, parent_t, ancestors):
        if isinstance(ref, dict):
            g = groups[ref['uuid']]
            o = np.array(g['origin'], float)
            R = rotation(g)
            # World parent transform followed by a rotation about group origin.
            total_R = parent_R@R
            total_t = parent_R@(o-R@o)+parent_t
            for child in ref['children']:
                walk(child, total_R, total_t, ancestors+[g['name']])
            return
        e = elements[ref]
        own_R = rotation(e); o = np.array(e['origin'], float)
        total_R = parent_R@own_R
        total_t = parent_R@(o-own_R@o)+parent_t
        name = ' '.join(ancestors).lower()
        if 'energia' in name:
            kind = 'energy'
        elif 'vidro' in name:
            kind = 'glass'
        else:
            kind = 'copper'
        results[ref] = (kind, total_R, total_t)

    for ref in model['outliner']:
        walk(ref, np.eye(3), np.zeros(3), [])
    assert len(results) == len(elements)
    return results


def fix(filename):
    source = ROOT/'modelos'/filename
    model = json.loads(source.read_text(encoding='utf-8-sig'))
    original = copy.deepcopy(model)
    transforms = geometry(model)
    patches = []; faces = []; ox = oy = 2; rowh = 0
    old_bad_uv = sum(min(f['uv']) < 0 or max(f['uv']) > 512
                     for e in model['elements'] for f in e['faces'].values())
    for e in model['elements']:
        kind, R, translation = transforms[e['uuid']]
        # Orient the procedural material along each arm, including rotated arms.
        spans = np.abs(R)@np.array([b-a for a, b in zip(e['from'], e['to'])])
        long_axis = int(np.argmax(spans))
        for name in e['faces']:
            local = corners(e, name); world = local@R.T+translation
            normal = R@np.array(NORMALS[name])
            w = max(1, round(np.linalg.norm(local[1]-local[0])*2))
            h = max(1, round(np.linalg.norm(local[3]-local[0])*2))
            coords = world.copy(); material_normal = normal.copy()
            if long_axis == 0:
                coords = world[:, [2, 1, 0]]
                material_normal = normal[[2, 1, 0]]
            elif long_axis == 1:
                coords = world[:, [0, 2, 1]].copy()
                coords[:, 1] += 16
                coords[:, 2] -= 16
                material_normal = normal[[0, 2, 1]]
            patch = texture_patch(kind, w, h, coords, material_normal)
            # Broad copper elbow plates get subtle panel edging, rather than
            # sampling unrelated glass/energy regions from the previous atlas.
            if kind == 'copper' and min(w, h) >= 8:
                d = ImageDraw.Draw(patch)
                d.rectangle((1, 1, w-2, h-2), outline=(117, 59, 34, 255))
                for x, y in [(2, 2), (w-3, 2), (2, h-3), (w-3, h-3)]:
                    d.point((x, y), fill=(236, 170, 100, 255))
            if ox+w+2 > 512:
                ox = 2; oy += rowh+2; rowh = 0
            f = {'uv': [ox, oy, ox+w, oy+h], 'texture': 0}
            if kind != 'copper':
                f['transparent'] = True
            e['faces'][name] = f
            patches.append((ox, oy, patch))
            faces.append((world, normal, patch, kind))
            ox += w+2; rowh = max(rowh, h)
        e['autouv'] = 0
        e['shading_mode'] = 'fullbright' if kind == 'energy' else 'flat'
        e['double_sided'] = kind == 'glass'
    height = 1 << (oy+rowh+2-1).bit_length()
    atlas = Image.new('RGBA', (512, height), (0, 0, 0, 0))
    for x, y, patch in patches:
        w, h = patch.size
        atlas.paste(patch, (x, y))
        atlas.paste(patch.crop((0, 0, w, 1)), (x, y-1))
        atlas.paste(patch.crop((0, h-1, w, h)), (x, y+h))
        atlas.paste(patch.crop((0, 0, 1, h)), (x-1, y))
        atlas.paste(patch.crop((w-1, 0, w, h)), (x+w, y))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    texture_path = OUTPUT/(source.stem+'.png')
    atlas.save(texture_path)
    stream = io.BytesIO(); atlas.save(stream, format='PNG')
    model['resolution'] = {'width': 512, 'height': height}
    model['textures'] = [{
        'path': str(texture_path), 'name': texture_path.name, 'id': '0',
        'uuid': str(uuid.uuid4()), 'width': 512, 'height': height,
        'uv_width': 512, 'uv_height': height, 'mode': 'bitmap',
        'saved': True, 'render_mode': 'default',
        'source': 'data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode()
    }]
    output_path = OUTPUT/filename
    output_path.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding='utf-8')
    # Verify the entire nested hierarchy, every dimension, pivot and rotation.
    assert model['groups'] == original['groups'] and model['outliner'] == original['outliner']
    for before, after in zip(original['elements'], model['elements']):
        for key in ['from', 'to', 'origin', 'rotation', 'stretch', 'uuid']:
            assert before.get(key) == after.get(key), key
    exported, png, count = export_model(model, turn_to_player=False)
    assert count == len(original['elements'])
    assert all(0 <= min(e['faces'][f]['uv']) and e['faces'][f]['uv'][2] <= 512
               and e['faces'][f]['uv'][3] <= height for e in model['elements'] for f in e['faces'])
    render(faces, OUTPUT/(source.stem+' - preview.png'), [1.2, 1.6, -1.35],
           render_resolution=400, output_size=640, view_scale=400/56)
    print(json.dumps({'file': str(output_path), 'elements': count,
                      'old_faces_outside_atlas': old_bad_uv,
                      'atlas_size': [512, height], 'geometry_preserved': True}), flush=True)


if __name__ == '__main__':
    for filename in SOURCES:
        fix(filename)
