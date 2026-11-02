"""Texture the authored Arcane Core, preserving its geometry and hierarchy."""
import base64
import copy
import io
import json
import math
from pathlib import Path
import uuid

import numpy as np
from PIL import Image

from texture_runic_conduit import ROOT, corners, NORMALS, render, texture_patch
from fix_conduit_variant_textures import geometry
from build_pack import export_model


def materials(model):
    groups = {g['uuid']: g for g in model['groups']}
    elements = {e['uuid']: e for e in model['elements']}
    result = {}
    def walk(ref, names):
        if isinstance(ref, dict):
            for child in ref['children']:
                walk(child, names+[groups[ref['uuid']]['name']])
        else:
            e = elements[ref]
            if 'cristal' in names:
                kind = 'crystal'
            elif any(n.startswith('conexão') for n in names):
                kind = 'socket' if e.get('color') == 7 else 'copper'
            elif names[-1] == 'base':
                kind = 'stone'
            else:
                kind = 'bronze'
            result[ref] = kind
    for ref in model['outliner']:
        walk(ref, [])
    return result


def paint(kind, w, h, points, normal):
    if kind == 'copper':
        return texture_patch(kind, w, h, points, normal)
    image = Image.new('RGBA', (w, h)); px = image.load()
    axis = int(np.argmax(np.abs(normal)))
    for v in range(h):
        for u in range(w):
            x, y, z = points[0]+(points[1]-points[0])*(u+.5)/w+(points[3]-points[0])*(v+.5)/h
            grain = (int(x*2)*17+int(y*2)*31+int(z*2)*13) % 9-4
            edge = u in (0, w-1) or v in (0, h-1)
            if kind == 'stone':
                brick = int((y+2)//4)
                seam = int(y*2)%8 == 0 or int((x if axis==2 else z)*2+brick*9)%18 == 0
                color = (69, 74, 72) if seam else (91+grain, 95+grain, 87+grain)
                if edge: color = (120+grain, 120+grain, 104+grain)
                # The base's four large faces carry the network's recurring rune.
                s = x if axis == 2 else z
                if axis != 1 and y < 12:
                    dx, dy = s, y-6
                    radius = max(abs(dx), abs(dy))
                    ring = 2 <= radius <= 2.5 and not(abs(dx)>2 and abs(dy)>2)
                    bars = abs(dy)<1.2 and any(abs(dx-c)<.26 for c in (-1,0,1))
                    if ring or bars: color = (50, 184, 176)
            elif kind == 'bronze':
                color = (145+grain, 105+grain, 51+grain)
                if edge: color = (213, 168, 88) if u==0 or v==0 else (91, 65, 33)
                if not edge and (int(x*2)+int(z*2)+int(y*2))%43==0:
                    color = (78, 111, 87)
            elif kind == 'socket':
                color = (99+grain, 70+grain, 36+grain)
                if edge: color = (204, 147, 75)
                if 1<u<w-2 and 1<v<h-2 and (u==w//2 or v==h//2):
                    color = (72, 211, 195)
            else:
                # Painted facets with a bright ridge; no transparency is needed
                # to convey a luminous mineral crystal.
                shade = (int(x*2)+int(z*2))%5
                color = [(28,149,160),(35,178,181),(52,207,202),(90,229,212),(145,244,224)][shade]
                if edge: color = (169, 249, 229) if u==0 or v==0 else (23, 137, 152)
                if abs(x)<.3 or abs(z)<.3:
                    color = (159, 247, 227)
                if int(y*2)%19==0 and u%3==0: color = (213, 255, 239)
            px[u,v] = tuple(max(0, min(255, c)) for c in color)+(255,)
    return image


def main():
    source = ROOT/'modelos/ArcaneCore.bbmodel'
    model = json.loads(source.read_text(encoding='utf-8-sig'))
    original = copy.deepcopy(model)
    transforms = geometry(model); kinds = materials(model)
    patches = []; faces = []; ox = oy = 2; rowh = 0
    for e in model['elements']:
        _, R, t = transforms[e['uuid']]; kind = kinds[e['uuid']]
        for name in e['faces']:
            local = corners(e, name); points = local@R.T+t
            normal = R@np.array(NORMALS[name])
            w = max(1, round(np.linalg.norm(local[1]-local[0])*2))
            h = max(1, round(np.linalg.norm(local[3]-local[0])*2))
            if ox+w+2 > 512:
                ox = 2; oy += rowh+2; rowh = 0
            patch = paint(kind, w, h, points, normal)
            e['faces'][name] = {'uv': [ox, oy, ox+w, oy+h], 'texture': 0}
            patches.append((ox, oy, patch))
            faces.append((points, normal, patch, 'energy' if kind=='crystal' else 'copper'))
            ox += w+2; rowh = max(rowh, h)
        e['autouv'] = 0
        e['shading_mode'] = 'fullbright' if kind == 'crystal' else 'flat'
        e['double_sided'] = False
    height = 1 << (oy+rowh+1).bit_length()
    atlas = Image.new('RGBA', (512, height))
    for x, y, patch in patches:
        w, h = patch.size; atlas.paste(patch, (x,y))
        atlas.paste(patch.crop((0,0,w,1)), (x,y-1))
        atlas.paste(patch.crop((0,h-1,w,h)), (x,y+h))
        atlas.paste(patch.crop((0,0,1,h)), (x-1,y))
        atlas.paste(patch.crop((w-1,0,w,h)), (x+w,y))
    out = ROOT/'modelos/corrected/ArcaneCore - Textured.bbmodel'
    out.parent.mkdir(parents=True, exist_ok=True)
    png = out.with_name('arcane_core_texture.png'); atlas.save(png)
    stream = io.BytesIO(); atlas.save(stream,format='PNG')
    model['resolution'] = {'width':512,'height':height}
    model['textures'] = [{
        'path':str(png),'name':png.name,'id':'0','uuid':str(uuid.uuid4()),
        'width':512,'height':height,'uv_width':512,'uv_height':height,
        'mode':'bitmap','saved':True,'render_mode':'default',
        'source':'data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode()
    }]
    for before,after in zip(original['elements'],model['elements']):
        for key in ('from','to','rotation','origin','stretch','uuid'):
            assert before.get(key)==after.get(key),key
    assert original['groups']==model['groups'] and original['outliner']==model['outliner']
    exported,_,count=export_model(model,turn_to_player=False)
    assert count==136
    out.write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')
    preview=out.with_name('arcane_core_preview.png')
    render(faces,preview,[1.2,.85,-1.35],render_resolution=340,output_size=680,view_scale=340/82)
    print(json.dumps({'model':str(out),'texture':str(png),'preview':str(preview),
                      'cubes':count,'atlas':[512,height],'geometry_preserved':True}))


if __name__=='__main__':
    main()
