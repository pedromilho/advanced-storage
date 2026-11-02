"""Generate a native-size icon and collision boxes from the textured core."""
import base64
import io
import json

import numpy as np
from PIL import Image

from build_pack import ROOT, CORE_MODEL
from fix_conduit_variant_textures import geometry
from texture_arcane_core import materials
from texture_runic_conduit import corners, NORMALS, render


def main():
    model = json.loads(CORE_MODEL.read_text(encoding='utf-8'))
    transforms = geometry(model); kinds = materials(model)
    atlas = Image.open(io.BytesIO(base64.b64decode(model['textures'][0]['source'].split(',')[1])))
    boxes = []; faces = []
    for e in model['elements']:
        _, R, t = transforms[e['uuid']]; points = []
        for face, f in e['faces'].items():
            v = corners(e, face)@R.T+t; points.extend(v)
            faces.append((v, R@np.array(NORMALS[face]), atlas.crop(tuple(f['uv'])),
                          'energy' if kinds[e['uuid']]=='crystal' else 'copper'))
        points = np.array(points)
        lo = points.min(0)/32+np.array([.5,0,.5]); hi = points.max(0)/32+np.array([.5,0,.5])
        boxes.append({'Min':dict(zip('XYZ',[round(float(v),8) for v in lo])),
                      'Max':dict(zip('XYZ',[round(float(v),8) for v in hi]))})
    hitbox = ROOT/'pack/Server/Item/Block/Hitboxes/AdvancedStorage_Arcane_Core.json'
    hitbox.write_text(json.dumps({'Boxes':boxes},indent=2)+'\n')
    icon = ROOT/'pack/Common/Icons/ItemsGenerated/AdvancedStorage_Arcane_Core.png'
    render(faces,icon,[1.2,.85,-1.35],transparent=True,output_size=64,
           render_resolution=320,view_scale=320/82)
    for language in ('en-US','pt-BR'):
        path=ROOT/f'pack/Server/Languages/{language}/server.lang'
        lines=[s for s in path.read_text(encoding='utf-8').splitlines()
               if not s.startswith('items.AdvancedStorage_Arcane_Core.')]
        lines.extend(['items.AdvancedStorage_Arcane_Core.name = Arcane Core',
                      'items.AdvancedStorage_Arcane_Core.description = An arcane heart with four conduit sockets. Visual prototype; network power is not yet active.'])
        path.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(f'Prepared Arcane Core: {len(boxes)} collision boxes, 64 x 64 RGBA icon.')


if __name__=='__main__':
    main()
