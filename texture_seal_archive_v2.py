from pathlib import Path
import ast, json, math, base64, io, uuid
import numpy as np
from PIL import Image, ImageDraw

ROOT=Path(r'C:\Users\pedro\Documents\ChatGPT\advanced storage\modelos')
src=ast.parse(Path('texture_seal_archive.py').read_text(encoding='utf-8'))
for node in src.body:
    if isinstance(node,ast.FunctionDef) and node.name in ['corners','rotation']:
        exec(compile(ast.Module(body=[node],type_ignores=[]),'<helpers>','exec'))
model=json.loads((ROOT/'Seal Archive - Textured.bbmodel').read_text(encoding='utf-8'))
original=json.loads(json.dumps(model))
groups={g['uuid']:g['name'] for g in model['groups']}
membership={child:groups[g['uuid']] for g in model['outliner'] for child in g['children']}
normals={'north':(0,0,-1),'south':(0,0,1),'east':(1,0,0),'west':(-1,0,0),'up':(0,1,0),'down':(0,-1,0)}
rng=np.random.default_rng(123)
atlas=Image.new('RGBA',(512,512),(0,0,0,0)); ox=oy=2; rowh=0; render_faces=[]

def patch_for(el,face,w,h,group):
    im=Image.new('RGBA',(w,h)); px=im.load()
    for v in range(h):
        for u in range(w):
            grain=int(rng.integers(-3,4))+(u//3%3-1)*3
            color=(77,51,31) if group=='base' else (60,40,26)
            if group=='encaixe': color=(130,94,44)
            if group=='gavetas': color=(104,71,42)
            px[u,v]=tuple(max(0,c+grain) for c in color)+(255,)
    d=ImageDraw.Draw(im)
    d.line((0,0,w-1,0),fill='#91643d' if group!='encaixe' else '#e0b865')
    d.line((0,0,0,h-1),fill='#795333' if group!='encaixe' else '#bf944b')
    d.line((w-1,0,w-1,h-1),fill='#38291c');d.line((0,h-1,w-1,h-1),fill='#30251b')
    if group=='base' and min(w,h)>12:
        for x in range(12,w-2,12):
            d.line((x,1,x,h-2),fill='#3b2b1d');d.line((x+1,1,x+1,h-2),fill='#815735')
    if group=='carcaça' and max(w,h)>12:
        # Bronze ferrules on ends of structural wooden beams.
        horizontal=w>=h
        for end in [False,True]:
            if horizontal: box=(w-4 if end else 0,0,w-1 if end else 3,h-1)
            else: box=(0,h-4 if end else 0,w-1,h-1 if end else 3)
            d.rectangle(box,fill='#a77b3b');d.line((box[0],box[1],box[2],box[1]),fill='#d7ac5d')
            cx=(box[0]+box[2])//2;cy=(box[1]+box[3])//2
            d.point((cx,cy),fill='#e0b568')
            if cy+1<h:d.point((cx,cy+1),fill='#654720')
    if group=='gavetas' and w>=10 and h>=10:
        d.rectangle((1,1,w-2,h-2),outline='#bd8c50'); d.rectangle((2,2,w-3,h-3),outline='#795131')
        cx,cy=w//2,h//2
        d.rectangle((cx-2,cy-2,cx+2,cy+2),fill='#402e1c')
        d.rectangle((cx-1,cy-1,cx+1,cy+1),fill='#b98b43');d.line((cx-1,cy-1,cx+1,cy-1),fill='#e5be70')
    if group=='encaixe' and w>=10 and h>=10:
        d.rectangle((1,1,w-2,h-2),fill='#705331');d.rectangle((2,2,w-3,h-3),outline='#8c6b3c')
        cx,cy=w//2,h//2
        points=[(cx-2,cy-4),(cx+1,cy-4),(cx+4,cy-1),(cx+4,cy+1),(cx+1,cy+4),(cx-2,cy+4),(cx-4,cy+1),(cx-4,cy-1),(cx-2,cy-4)]
        d.line(points,fill='#39c6be',width=1)
        for x in [cx-2,cx,cx+2]:d.line((x,cy-1,x,cy+1),fill='#9feadd')
    return im

for i,el in enumerate(model['elements']):
    group=membership[el['uuid']];R=rotation(el);origin=np.array(el.get('origin',[0,0,0]))
    for face in el['faces']:
        local=corners(el,face);world=(local-origin)@R.T+origin;n=R@np.array(normals[face])
        w=max(1,round(np.linalg.norm(local[1]-local[0])*2));h=max(1,round(np.linalg.norm(local[3]-local[0])*2))
        if ox+w+2>512: ox=2;oy+=rowh+2;rowh=0
        assert oy+h+2<=512
        patch=patch_for(el,face,w,h,group);atlas.paste(patch,(ox,oy))
        el['faces'][face]={'uv':[ox,oy,ox+w,oy+h],'texture':0}
        # Duplicate border pixels prevent texture filtering from picking transparent atlas gaps.
        atlas.paste(patch.crop((0,0,w,1)),(ox,oy-1));atlas.paste(patch.crop((0,h-1,w,h)),(ox,oy+h))
        atlas.paste(patch.crop((0,0,1,h)),(ox-1,oy));atlas.paste(patch.crop((w-1,0,w,h)),(ox+w,oy))
        render_faces.append((world,n,patch));ox+=w+2;rowh=max(rowh,h)
    el['autouv']=0
png=ROOT/'seal_archive_texture_v2.png';atlas.save(png);buf=io.BytesIO();atlas.save(buf,format='PNG')
model['resolution']={'width':512,'height':512}
model['textures']=[{'path':str(png),'name':png.name,'folder':'block','namespace':'','id':'0','uuid':str(uuid.uuid4()),'width':512,'height':512,'uv_width':512,'uv_height':512,'mode':'bitmap','saved':True,'render_mode':'default','source':'data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()}]
out=ROOT/'Seal Archive - Textured v2.bbmodel';model['name']='Seal Archive - Textured v2'
out.write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')
for old,new in zip(original['elements'],model['elements']):
    for key in ['from','to','origin','rotation','uuid']:
        assert old.get(key)==new.get(key),key
assert original['outliner']==model['outliner'] and original['groups']==model['groups']

# Render the actual geometry with the same texture patches for visual inspection.
allpoints=np.concatenate([v for v,n,p in render_faces]); low=allpoints.min(0);high=allpoints.max(0);center=(low+high)/2
render_faces=[((v-center)*16/max(high-low)+np.array([8,8,8]),n,p) for v,n,p in render_faces]
text=Path('texture_seal_archive.py').read_text(encoding='utf-8')
render=text.split('# Orthographic textured preview of the actual supplied geometry.')[1].split('assert len(model')[0]
render=render.replace("ROOT/'seal_archive_preview.png'","ROOT/'seal_archive_preview_v2.png'")
exec(render)
print(json.dumps({'elements':len(model['elements']),'bounds':[low.tolist(),high.tolist()],'size':(high-low).tolist(),'model':str(out),'texture':str(png),'preview':str(preview)}))
