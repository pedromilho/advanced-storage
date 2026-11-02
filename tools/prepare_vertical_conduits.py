"""Prepare game definitions and the missing three-axis joint from approved parts."""
import base64
import copy
import io
import json
from pathlib import Path
import uuid

import numpy as np
from PIL import Image

from texture_runic_conduit import ROOT, corners, NORMALS, render, texture_patch
from fix_conduit_variant_textures import geometry

NEW = {
    'Runic_Conduit_Up': ('RunicConduit - T-cima', 'Runic Conduit - Four-way'),
    'Runic_Conduit_UpDown': ('RunicConduit - T-cima-baixo', 'Runic Conduit - Five-way'),
    'Runic_Conduit_All': ('RunicConduit - all', 'Runic Conduit - Six-way'),
    'Runic_Conduit_Corner3': ('RunicConduit - corner3.generated', 'Runic Conduit - Three-axis')
}
DIR = ROOT/'modelos/corrected'


def derive_corner():
    source = DIR/'RunicConduit - T-cima.bbmodel'
    model = json.loads(source.read_text(encoding='utf-8'))
    transforms = geometry(model)
    kept = []
    for e in model['elements']:
        kind, R, t = transforms[e['uuid']]
        pts = np.concatenate([corners(e, f)@R.T+t for f in e['faces']])
        # Remove east arm while retaining the user's copper center and the
        # south/west/up arms. Clip existing pieces against a closed east wall.
        if pts[:, 0].min() >= 5-1e-7:
            continue
        if pts[:, 0].max() > 5+1e-7:
            before = copy.deepcopy(e)
            axis = int(np.argmax(np.abs(R[0])))
            limit = (5-t[0])/R[0, axis]
            if R[0, axis] > 0:
                e['to'][axis] = round(limit, 8)
            else:
                e['from'][axis] = round(limit, 8)
            for face, f in e['faces'].items():
                old = corners(before, face); new = corners(e, face)
                U = old[1]-old[0]; V = old[3]-old[0]
                a, b, c, d = f['uv']
                uu = [(v-old[0])@U/(U@U) for v in (new[0], new[2])]
                vv = [(v-old[0])@V/(V@V) for v in (new[0], new[2])]
                f['uv'] = [round(a+uu[0]*(c-a), 8), round(b+vv[0]*(d-b), 8),
                           round(a+uu[1]*(c-a), 8), round(b+vv[1]*(d-b), 8)]
        kept.append(e)
    keep_ids = {e['uuid'] for e in kept}
    def prune(ref):
        if isinstance(ref, str):
            return ref if ref in keep_ids else None
        ref['children'] = [v for c in ref['children'] if (v := prune(c)) is not None]
        return ref
    model['elements'] = kept
    model['outliner'] = [prune(r) for r in model['outliner']]
    # Small copper cap uses the same copper palette and pixel density.
    texture = model['textures'][0]
    image = Image.open(io.BytesIO(base64.b64decode(texture['source'].split(',')[1]))).convert('RGBA')
    old_h = image.height
    atlas = Image.new('RGBA', (image.width, old_h*2)); atlas.paste(image, (0, 0))
    cap = copy.deepcopy(kept[0])
    cap.update(name='Closed east copper wall', uuid=str(uuid.uuid4()),
               origin=[0, 0, 0], rotation=[0, 0, 0], **{'from': [4, 11, -4], 'to': [5, 21, 4]})
    cap.update(shading_mode='flat', double_sided=False)
    x, y = 2, old_h+2
    for face in cap['faces']:
        pts = corners(cap, face)
        w = round(np.linalg.norm(pts[1]-pts[0])*2)
        h = round(np.linalg.norm(pts[3]-pts[0])*2)
        patch = texture_patch('copper', w, h, pts, np.array(NORMALS[face]))
        atlas.paste(patch, (x, y))
        cap['faces'][face] = {'uv': [x, y, x+w, y+h], 'texture': 0}
        x += w+2
    model['elements'].append(cap)
    copper = next(g['uuid'] for g in model['groups'] if g['name'] == 'cotovelo')
    def attach(ref):
        if isinstance(ref, dict):
            if ref['uuid'] == copper: ref['children'].append(cap['uuid'])
            for child in ref['children']: attach(child)
    for ref in model['outliner']: attach(ref)
    out = DIR/'RunicConduit - corner3.generated.bbmodel'
    png = out.with_suffix('.png'); atlas.save(png)
    buf = io.BytesIO(); atlas.save(buf, format='PNG')
    texture.update(path=str(png), name=png.name, width=atlas.width, height=atlas.height,
                   uv_width=atlas.width, uv_height=atlas.height,
                   source='data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode())
    model['resolution'] = {'width': atlas.width, 'height': atlas.height}
    model['name'] = 'Runic Conduit - Three-axis (generated)'
    out.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    derive_corner()
    base = json.loads((ROOT/'pack/Server/Item/Items/AdvancedStorage/AdvancedStorage_Runic_Conduit.json').read_text())
    translations = []
    for name, (stem, label) in NEW.items():
        path = DIR/(stem+'.bbmodel')
        d = json.loads(path.read_text(encoding='utf-8')); transforms = geometry(d)
        atlas = Image.open(path.with_suffix('.png')); faces = []; boxes = []
        for e in d['elements']:
            kind, R, t = transforms[e['uuid']]; points = []
            for face, f in e['faces'].items():
                v = corners(e, face)@R.T+t; points.extend(v)
                faces.append((v, R@np.array(NORMALS[face]), atlas.crop(tuple(f['uv'])), kind))
            points = np.array(points)
            lo = points.min(0)/32+np.array([.5, 0, .5]); hi = points.max(0)/32+np.array([.5, 0, .5])
            boxes.append({'Min': dict(zip('XYZ', [round(float(v), 8) for v in lo])),
                          'Max': dict(zip('XYZ', [round(float(v), 8) for v in hi]))})
        item = copy.deepcopy(base); item_id = 'AdvancedStorage_'+name
        item['TranslationProperties'] = {'Name': f'server.items.{item_id}.name',
                                        'Description': f'server.items.{item_id}.description'}
        item['Icon'] = f'Icons/ItemsGenerated/{item_id}.png'
        block = item['BlockType']; block['CustomModel'] = f'Blocks/AdvancedStorage/{name}.blockymodel'
        block['CustomModelTexture'] = [{'Texture': f'Blocks/AdvancedStorage/{name}_Texture.png', 'Weight': 1}]
        block['HitboxType'] = item_id
        (ROOT/f'pack/Server/Item/Items/AdvancedStorage/{item_id}.json').write_text(json.dumps(item, indent=2)+'\n')
        (ROOT/f'pack/Server/Item/Block/Hitboxes/{item_id}.json').write_text(json.dumps({'Boxes': boxes}, indent=2)+'\n')
        render(faces, ROOT/f'pack/Common/Icons/ItemsGenerated/{item_id}.png', [1.2, 1.6, -1.35],
               transparent=True, output_size=64, render_resolution=320, view_scale=320/62)
        translations.extend([f'items.{item_id}.name = {label}',
                             f'items.{item_id}.description = Copper and glass conduit. Automatically connects in six directions.'])
        print('Prepared '+name, flush=True)
    for language in ('en-US', 'pt-BR'):
        path = ROOT/f'pack/Server/Languages/{language}/server.lang'
        lines = path.read_text(encoding='utf-8').splitlines()
        replace = dict(line.split(' = ', 1) for line in translations)
        lines = [line for line in lines if line.split(' = ', 1)[0] not in replace]
        path.write_text('\n'.join(lines+translations)+'\n', encoding='utf-8')


if __name__ == '__main__':
    main()
