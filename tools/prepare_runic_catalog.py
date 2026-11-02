"""Prepare the catalog's 64px inventory thumbnail and solid-piece collisions."""
import base64
import io
import json

import numpy as np
from PIL import Image

from build_pack import ROOT, CATALOG_MODEL
from fix_conduit_variant_textures import geometry
from texture_runic_conduit import corners, NORMALS, render


def main():
    model=json.loads(CATALOG_MODEL.read_text(encoding='utf-8'));transforms=geometry(model)
    atlas=Image.open(io.BytesIO(base64.b64decode(model['textures'][0]['source'].split(',')[1])))
    boxes=[];faces=[]
    for e in model['elements']:
        _,R,t=transforms[e['uuid']];points=[]
        planar=any(abs(b-a)<1e-7 for a,b in zip(e['from'],e['to']))
        for face,f in e['faces'].items():
            v=corners(e,face)@R.T+t;points.extend(v)
            if f.get('texture') is not None:
                faces.append((v,R@np.array(NORMALS[face]),atlas.crop(tuple(f['uv'])),'glass' if planar else 'copper'))
        if planar:continue
        points=np.array(points)
        lo=points.min(0)/32+np.array([.5,0,.5]);hi=points.max(0)/32+np.array([.5,0,.5])
        boxes.append({'Min':dict(zip('XYZ',[round(float(v),8) for v in lo])),
                      'Max':dict(zip('XYZ',[round(float(v),8) for v in hi]))})
    hitbox=ROOT/'pack/Server/Item/Block/Hitboxes/AdvancedStorage_Runic_Catalog.json'
    hitbox.write_text(json.dumps({'Boxes':boxes},indent=2)+'\n')
    icon=ROOT/'pack/Common/Icons/ItemsGenerated/AdvancedStorage_Runic_Catalog.png'
    render(faces,icon,[1.2,1.5,1.35],transparent=True,output_size=64,render_resolution=320,view_scale=320/62)
    for language in ('en-US','pt-BR'):
        p=ROOT/f'pack/Server/Languages/{language}/server.lang'
        lines=[s for s in p.read_text(encoding='utf-8').splitlines() if not s.startswith('items.AdvancedStorage_Runic_Catalog.')]
        lines.extend(['items.AdvancedStorage_Runic_Catalog.name = Runic Catalog',
                      'items.AdvancedStorage_Runic_Catalog.description = An enchanted ledger for the arcane library. Visual prototype; the network interface is not yet active.'])
        p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(f'Prepared Runic Catalog: {len(boxes)} solid collision boxes and a 64 x 64 RGBA icon.')


if __name__=='__main__':main()
