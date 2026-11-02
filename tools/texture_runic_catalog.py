"""Texture the authored catalog, including thin cloth and folded-page quads."""
import base64
import copy
import io
import json
import uuid

import numpy as np
from PIL import Image, ImageDraw

from build_pack import ROOT
from fix_conduit_variant_textures import geometry
from texture_runic_conduit import NORMALS, corners, render


def classify(model):
    groups={g['uuid']:g for g in model['groups']};elements={e['uuid']:e for e in model['elements']};result={}
    def visit(ref,names):
        if isinstance(ref,dict):
            for child in ref['children']:visit(child,names+[groups[ref['uuid']]['name']])
            return
        e=elements[ref];size=np.array(e['to'])-np.array(e['from'])
        if 'bandeira' in names:kind='cloth'
        elif 'livro' in names:kind='cover' if size[0]>20 else 'paper'
        elif any(n.startswith('reforço') for n in names) or (size[0]==32 and size[1]==1 and size[2]==1):kind='bronze'
        else:kind='wood'
        result[ref]=kind
    for ref in model['outliner']:visit(ref,[])
    return result


def paint(kind,w,h,face,points):
    image=Image.new('RGBA',(w,h));p=image.load()
    bases={'cloth':(24,57,73),'wood':(64,43,28),'cover':(83,34,31),'paper':(221,202,158),'bronze':(155,113,57)}
    for y in range(h):
        for x in range(w):
            grain=(x*13+y*23+x*y)%7-3
            if kind=='wood':grain+=(x//4%3-1)*4
            if kind=='cloth':grain+=2 if (x+y)%2 else -2
            p[x,y]=tuple(max(0,c+grain) for c in bases[kind])+(255,)
    d=ImageDraw.Draw(image)
    if kind=='wood':
        d.line((0,0,w-1,0),fill='#95663c');d.line((w-1,0,w-1,h-1),fill='#2b2219')
        if min(w,h)>12:
            for x in range(12,w-1,12):
                d.line((x,1,x,h-2),fill='#35261b');d.line((x+1,1,x+1,h-2),fill='#775030')
    elif kind=='bronze':
        d.line((0,0,w-1,0),fill='#dfb768');d.line((0,0,0,h-1),fill='#c59850')
        d.line((w-1,0,w-1,h-1),fill='#634625');d.line((0,h-1,w-1,h-1),fill='#76522a')
        if w>6 and h>6:
            for x,y in [(2,2),(w-3,2),(2,h-3),(w-3,h-3)]:d.point((x,y),fill='#edcb86')
    elif kind=='cover':
        if min(w,h)>=8:
            d.rectangle((1,1,w-2,h-2),outline='#bc914e')
            d.rectangle((3,3,w-4,h-4),outline='#50251f')
            for x,y in [(2,2),(w-5,2),(2,h-5),(w-5,h-5)]:d.rectangle((x,y,x+2,y+2),fill='#c69a50')
        else:
            d.line((0,0,w-1,0),fill='#ae7549');d.line((0,h-1,w-1,h-1),fill='#371e1c')
    elif kind=='paper':
        d.line((0,0,w-1,0),fill='#f4e3b8');d.line((w-1,0,w-1,h-1),fill='#ad9567')
        if face=='up' and min(w,h)>=10:
            d.rectangle((2,2,w-3,h-3),outline='#b6a176')
            # Handwritten runic rows are pixel marks, not modern UI text.
            for row in range(5,h-4,5):
                for col in range(5,w-4,4):
                    if ((col*7+row*3)%11)<3:continue
                    d.line((col,row,col,row+2),fill='#68533b')
                    d.line((col,row,col+1,row+1),fill='#68533b')
            cy=h//2;cx=w//2;r=min(6,(w-6)//2)
            d.rectangle((cx-r-1,cy-r-1,cx+r+1,cy+r+1),fill='#ddca9f')
            d.ellipse((cx-r,cy-r,cx+r,cy+r),outline='#378d89')
            for x in [cx-2,cx,cx+2]:d.line((x,cy-2,x,cy+2),fill='#267b79')
        elif face not in ('up','down') and h>2:
            for y in range(1,h,2):d.line((0,y,w-1,y),fill='#bda77b')
    else:
        d.line((1,0,1,h-1),fill='#b88e46');d.line((w-2,0,w-2,h-1),fill='#b88e46')
        if h>=18 and w>=18:
            cx=w//2;cy=h//2;r=min(8,w//2-4,h//2-3)
            d.ellipse((cx-r,cy-r,cx+r,cy+r),outline='#4ed3c6',width=1)
            for x in [cx-3,cx,cx+3]:d.line((x,cy-3,x,cy+3),fill='#85e5d2')
            d.point((cx,cy-r-2),fill='#c99d51')
    return image


def main():
    path=ROOT/'modelos/RunicCatalog.bbmodel'
    model=json.loads(path.read_text(encoding='utf-8-sig'));original=copy.deepcopy(model)
    transforms=geometry(model);kinds=classify(model)
    patches=[];faces=[];x=y=2;rowh=0;quads=0
    for e in model['elements']:
        _,R,t=transforms[e['uuid']];kind=kinds[e['uuid']]
        size=np.array(e['to'])-np.array(e['from'])
        zero=np.flatnonzero(np.abs(size)<1e-7)
        active=None
        if len(zero):
            assert len(zero)==1
            active=['east','up','south'][zero[0]];quads+=1
        for face in e['faces']:
            local=corners(e,face);u=np.linalg.norm(local[1]-local[0]);v=np.linalg.norm(local[3]-local[0])
            if u<1e-7 or v<1e-7 or (active is not None and face!=active):
                e['faces'][face]={'uv':[0,0,0,0],'texture':None}
                continue
            w=max(1,round(u*2));h=max(1,round(v*2))
            if x+w+2>512:x=2;y+=rowh+2;rowh=0
            points=local@R.T+t;normal=R@np.array(NORMALS[face])
            patch=paint(kind,w,h,face,points)
            patches.append((x,y,patch));e['faces'][face]={'uv':[x,y,x+w,y+h],'texture':0}
            faces.append((points,normal,patch,'glass' if active else 'copper'))
            x+=w+2;rowh=max(rowh,h)
        e['autouv']=0;e['double_sided']=active is not None;e['shading_mode']='flat'
    height=1<<(y+rowh+1).bit_length()
    atlas=Image.new('RGBA',(512,height))
    for x,y,p in patches:
        w,h=p.size;atlas.paste(p,(x,y))
        atlas.paste(p.crop((0,0,w,1)),(x,y-1));atlas.paste(p.crop((0,h-1,w,h)),(x,y+h))
        atlas.paste(p.crop((0,0,1,h)),(x-1,y));atlas.paste(p.crop((w-1,0,w,h)),(x+w,y))
    out=ROOT/'modelos/corrected/RunicCatalog - Textured.bbmodel'
    out.parent.mkdir(parents=True,exist_ok=True)
    png=out.with_name('runic_catalog_texture.png');atlas.save(png)
    buffer=io.BytesIO();atlas.save(buffer,format='PNG')
    model['resolution']={'width':512,'height':height}
    model['textures']=[{'path':str(png),'name':png.name,'id':'0','uuid':str(uuid.uuid4()),
        'width':512,'height':height,'uv_width':512,'uv_height':height,'mode':'bitmap',
        'saved':True,'render_mode':'default','source':'data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode()}]
    for before,after in zip(original['elements'],model['elements']):
        for key in ('from','to','rotation','origin','stretch','uuid'):assert before.get(key)==after.get(key),key
    assert original['groups']==model['groups'] and original['outliner']==model['outliner']
    for e in model['elements']:
        for f in e['faces'].values():
            if f.get('texture') is not None:
                a,b,c,d=f['uv'];assert 0<=a<c<=512 and 0<=b<d<=height
    out.write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')
    preview=out.with_name('runic_catalog_preview.png')
    render(faces,preview,[1.2,1.5,1.35],render_resolution=400,output_size=680,view_scale=400/62)
    print(json.dumps({'model':str(out),'texture':str(png),'preview':str(preview),
                      'elements':len(model['elements']),'thin_planes':quads,'geometry_preserved':True}))


if __name__=='__main__':main()
