"""Texture the authored conduit without changing its geometry or mounting height."""
from pathlib import Path
import ast
import base64
import io
import json
import math
import uuid

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / 'modelos'
SOURCE = DIR / 'RunicConduit.bbmodel'

# Same face coordinate conventions as the archive preview.
tree = ast.parse((ROOT / 'texture_seal_archive.py').read_text(encoding='utf-8'))
for definition in tree.body:
    if isinstance(definition, ast.FunctionDef) and definition.name in ('corners', 'rotation'):
        exec(compile(ast.Module(body=[definition], type_ignores=[]), '<geometry>', 'exec'))

NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0),
           'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}


def texture_patch(kind, w, h, vertices, normal):
    patch = Image.new('RGBA', (w, h))
    pixels = patch.load()
    axis = int(np.argmax(np.abs(normal)))
    for v in range(h):
        for u in range(w):
            p = vertices[0] + (vertices[1]-vertices[0])*(u+.5)/w + (vertices[3]-vertices[0])*(v+.5)/h
            x, y, z = p
            iz = int(math.floor((z+16)*2))
            grain = (u*31+v*17+u*v*3) % 7 - 3
            edge = u in (0, w-1) or v in (0, h-1)
            if kind == 'copper':
                end = abs(z) > 12
                base = (170, 89, 47) if end else (126, 65, 35)
                if edge:
                    base = (215, 137, 77) if u == 0 or v == 0 else (88, 44, 27)
                if end and iz % 8 == 2:
                    base = (222, 151, 88)
                if 9 < abs(z) < 10:
                    base = (63, 102, 86)  # Sparse verdigris at metal seams.
                pixels[u, v] = tuple(max(0, c+grain) for c in base) + (255,)
            elif kind == 'glass':
                # Clear centers, readable blue-green edges and occasional glints.
                color = (77, 157, 162, 30)
                if edge:
                    color = (134, 211, 208, 130)
                if abs(z) > 14.5:
                    color = (180, 107, 57, 255)
                elif axis == 1 and ((iz+u)//3) % 23 == 0:
                    color = (216, 248, 233, 145)
                elif axis == 0 and iz in (12, 13, 38, 39) and v % 7 < 2:
                    color = (192, 240, 229, 135)
                pixels[u, v] = color
            else:
                # Static illuminated filament and pulses; animation is future work.
                across = (x if axis == 1 else y-16) * 2
                center_line = abs(across) < 1.5
                pulse = iz % 20 in (5, 6, 7)
                if axis == 2:
                    center_line = abs(x) < .8 or abs(y-16) < .8
                    pulse = abs(x) < 1.5 and abs(y-16) < 1.5
                color = (17, 99, 105, 12)
                if center_line:
                    color = (58, 214, 210, 205)
                if pulse and (abs(across) < 4 or axis == 2):
                    color = (136, 250, 230, 230)
                if center_line and pulse:
                    color = (215, 255, 240, 255)
                pixels[u, v] = color
    return patch


def render(faces, destination, eye_direction, *, transparent=False, output_size=840,
           render_resolution=840, view_scale=None):
    size = render_resolution
    scale = view_scale if view_scale is not None else 20*size/840
    eye = np.array(eye_direction, float); eye /= np.linalg.norm(eye)
    right = np.cross(np.array([0, 1, 0]), eye); right /= np.linalg.norm(right)
    up = np.cross(eye, right)
    center = np.array([0, 16, 0])
    fragments = []
    for vertices, normal, patch, kind in faces:
        if normal @ eye <= 0 and kind == 'copper':
            continue
        vertices = vertices-center
        screen = np.stack([vertices@right*scale+size/2, -vertices@up*scale+size/2], axis=1)
        z = vertices@eye
        tex = np.array(patch)
        H, W = tex.shape[:2]
        uv = np.array([[0, 0], [W, 0], [W, H], [0, H]], float)
        rgba = np.zeros((size, size, 4), dtype=np.uint8)
        depth = np.full((size, size), -np.inf, dtype=np.float32)
        shade = 1 if kind == 'energy' else .70+.30*max(0, normal@np.array([-.3, .85, -.43]))
        for ids in ([0, 1, 2], [0, 2, 3]):
            triangle = screen[ids]
            A = np.vstack([triangle.T, np.ones(3)])
            if abs(np.linalg.det(A)) < 1e-8:
                continue
            inverse = np.linalg.inv(A)
            lo = np.maximum(0, np.floor(triangle.min(0)).astype(int))
            hi = np.minimum(size-1, np.ceil(triangle.max(0)).astype(int))
            for yy in range(lo[1], hi[1]+1):
                xx = np.arange(lo[0], hi[0]+1)
                weights = inverse@np.stack([xx+.5, np.full(len(xx), yy+.5), np.ones(len(xx))])
                mask = np.all(weights >= -1e-6, axis=0)
                xs = xx[mask]
                if not len(xs):
                    continue
                st = uv[ids].T@weights[:, mask]
                tx = np.clip(st[0].astype(int), 0, W-1); ty = np.clip(st[1].astype(int), 0, H-1)
                colors = tex[ty, tx].copy(); colors[:, :3] = (colors[:, :3]*shade).astype(np.uint8)
                rgba[yy, xs] = colors
                depth[yy, xs] = z[ids]@weights[:, mask]
        fragments.append((depth, rgba))
    # Sort per-pixel surface fragments to composite nested transparent glass/core.
    depths = np.stack([d for d, c in fragments])
    colors = np.stack([c for d, c in fragments])
    order = np.argsort(depths, axis=0)
    background = np.empty((size, size, 3), float)
    background[:] = (0, 0, 0) if transparent else (230, 225, 215)
    coverage = np.zeros((size, size, 1), float)
    yy, xx = np.indices((size, size))
    for layer in range(len(fragments)):
        color = colors[order[layer], yy, xx].astype(float)
        alpha = color[:, :, 3:4]/255
        background = color[:, :, :3]*alpha+background*(1-alpha)
        coverage = alpha+coverage*(1-alpha)
    if transparent:
        background = np.divide(background, coverage, out=np.zeros_like(background), where=coverage>0)
        background = np.dstack([background, coverage*255])
    image = Image.fromarray(np.clip(background, 0, 255).astype(np.uint8))
    if output_size != size:
        image = image.resize((output_size, output_size), Image.Resampling.LANCZOS)
    image.save(destination)


def main():
    model = json.loads(SOURCE.read_text(encoding='utf-8-sig'))
    original = json.loads(json.dumps(model))
    group_names = {g['uuid']: g['name'] for g in model['groups']}
    membership = {child: group_names[g['uuid']] for g in model['outliner'] for child in g['children']}
    kinds = {'lados cano': 'copper', 'vidrocano': 'glass', 'energia': 'energy'}
    atlas = Image.new('RGBA', (512, 512), (0, 0, 0, 0))
    ox = oy = 2; rowh = 0; faces = []
    for el in model['elements']:
        kind = kinds[membership[el['uuid']]]
        R = rotation(el); origin = np.array(el.get('origin', [0, 0, 0]))
        for face in el['faces']:
            local = corners(el, face); vertices = (local-origin)@R.T+origin
            normal = R@np.array(NORMALS[face])
            w = max(1, round(np.linalg.norm(local[1]-local[0])*2))
            h = max(1, round(np.linalg.norm(local[3]-local[0])*2))
            if ox+w+2 > 512:
                ox = 2; oy += rowh+2; rowh = 0
            assert oy+h+2 <= 512
            patch = texture_patch(kind, w, h, vertices, normal)
            atlas.paste(patch, (ox, oy))
            el['faces'][face] = {'uv': [ox, oy, ox+w, oy+h], 'texture': 0}
            if kind in ('glass', 'energy'):
                el['faces'][face]['transparent'] = True
            faces.append((vertices, normal, patch, kind))
            ox += w+2; rowh = max(rowh, h)
        el['autouv'] = 0
        if kind == 'glass':
            el['double_sided'] = True
        if kind == 'energy':
            el['shading_mode'] = 'fullbright'
    texture = DIR / 'runic_conduit_texture.png'; atlas.save(texture)
    stream = io.BytesIO(); atlas.save(stream, format='PNG')
    model['resolution'] = {'width': 512, 'height': 512}
    model['textures'] = [{
        'path': str(texture), 'name': texture.name, 'id': '0',
        'uuid': str(uuid.uuid4()), 'width': 512, 'height': 512,
        'uv_width': 512, 'uv_height': 512, 'mode': 'bitmap',
        'saved': True, 'render_mode': 'default',
        'source': 'data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode()
    }]
    for before, after in zip(original['elements'], model['elements']):
        for key in ('from', 'to', 'rotation', 'origin', 'uuid'):
            assert before.get(key) == after.get(key), key
    assert original['groups'] == model['groups'] and original['outliner'] == model['outliner']
    out = DIR / 'RunicConduit - Textured.bbmodel'
    out.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding='utf-8')
    render(faces, DIR/'runic_conduit_preview.png', [1.2, 1, -1.35])
    print(json.dumps({'model': str(out), 'texture': str(texture), 'elements': len(model['elements']),
                      'geometry_preserved': True}))


if __name__ == '__main__':
    main()
