"""Texture new sockets while retaining the catalog's existing painted surfaces."""
import base64
import copy
import io
import json
from pathlib import Path
import shutil
import uuid

import numpy as np
from PIL import Image, ImageDraw

from build_pack import ROOT, CATALOG_MODEL
from fix_conduit_variant_textures import geometry
from texture_runic_conduit import corners, NORMALS, texture_patch, render


def main():
    source=ROOT/'modelos/RunicCatalog.bbmodel'
    model=json.loads(source.read_text(encoding='utf-8-sig'));original=copy.deepcopy(model)
    groups={g['uuid']:g for g in model['groups']};socket_ids=set()
    def visit(ref,is_socket=False):
        if isinstance(ref,dict):
            is_socket=is_socket or groups[ref['uuid']]['name'].startswith('encaix')
            for child in ref['children']:visit(child,is_socket)
        elif is_socket:socket_ids.add(ref)
    for ref in model['outliner']:visit(ref)
    assert len(socket_ids)==15
    transforms=geometry(model)
    base=Image.open(io.BytesIO(base64.b64decode(model['textures'][0]['source'].split(',')[1]))).convert('RGBA')
    atlas=Image.new('RGBA',(base.width,base.height*2));atlas.paste(base,(0,0))
    x,y=2,base.height+2;rowh=0
    for e in model['elements']:
        if e['uuid'] not in socket_ids:
            assert all(f.get('texture') in (None,0) for f in e['faces'].values())
            continue
        _,R,t=transforms[e['uuid']]
        for face in e['faces']:
            local=corners(e,face);points=local@R.T+t;normal=R@np.array(NORMALS[face])
            w=round(np.linalg.norm(local[1]-local[0])*2);h=round(np.linalg.norm(local[3]-local[0])*2)
            if x+w+2>atlas.width:x=2;y+=rowh+2;rowh=0
            patch=texture_patch('copper',w,h,points,normal)
            size=np.array(e['to'])-np.array(e['from'])
            if face=='north' and np.allclose(size,[8,8,3]):
                d=ImageDraw.Draw(patch)
                d.rectangle((2,2,w-3,h-3),fill='#513c29')
                cx,cy=w//2,h//2
                d.ellipse((cx-5,cy-5,cx+4,cy+4),outline='#49cfc1')
                for xx in [cx-3,cx-1,cx+1]:d.line((xx,cy-2,xx,cy+1),fill='#8ce4d0')
            atlas.paste(patch,(x,y))
            atlas.paste(patch.crop((0,0,w,1)),(x,y-1));atlas.paste(patch.crop((0,h-1,w,h)),(x,y+h))
            atlas.paste(patch.crop((0,0,1,h)),(x-1,y));atlas.paste(patch.crop((w-1,0,w,h)),(x+w,y))
            e['faces'][face]={'uv':[x,y,x+w,y+h],'texture':0}
            x+=w+2;rowh=max(rowh,h)
        e['autouv']=0;e['shading_mode']='flat'
    assert y+rowh+2<=atlas.height
    assert np.array_equal(np.array(atlas.crop((0,0,base.width,base.height))),np.array(base))
    png=CATALOG_MODEL.with_name('runic_catalog_texture.png');atlas.save(png)
    buffer=io.BytesIO();atlas.save(buffer,format='PNG')
    model['resolution']={'width':atlas.width,'height':atlas.height}
    model['textures']=[{'path':str(png),'name':png.name,'id':'0','uuid':str(uuid.uuid4()),
        'width':atlas.width,'height':atlas.height,'uv_width':atlas.width,'uv_height':atlas.height,
        'mode':'bitmap','saved':True,'render_mode':'default',
        'source':'data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode()}]
    for a,b in zip(original['elements'],model['elements']):
        for key in ('from','to','origin','rotation','stretch','uuid'):assert a.get(key)==b.get(key)
        if a['uuid'] not in socket_ids:assert a==b
    assert original['groups']==model['groups'] and original['outliner']==model['outliner']
    backup=CATALOG_MODEL.with_name('RunicCatalog - Before socket textures.bbmodel')
    if CATALOG_MODEL.exists() and not backup.exists():shutil.copy2(CATALOG_MODEL,backup)
    CATALOG_MODEL.write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')
    faces=[]
    for e in model['elements']:
        _,R,t=transforms[e['uuid']]
        planar=any(abs(b-a)<1e-7 for a,b in zip(e['from'],e['to']))
        for face,f in e['faces'].items():
            if f.get('texture') is not None:
                faces.append((corners(e,face)@R.T+t,R@np.array(NORMALS[face]),atlas.crop(tuple(f['uv'])),'glass' if planar else 'copper'))
    render(faces,CATALOG_MODEL.with_name('runic_catalog_preview.png'),[1.2,1.5,1.35],
           render_resolution=400,output_size=680,view_scale=400/62)
    print('Prepared 15 socket pieces; original artwork and all geometry preserved.')


if __name__=='__main__':main()
