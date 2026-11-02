from pathlib import Path
import json, math, base64, io, uuid
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(r'C:\Users\pedro\Documents\ChatGPT\advanced storage\modelos')
model = json.loads((ROOT / 'Seal Archive.bbmodel').read_text(encoding='utf-8'))
rng = np.random.default_rng(27)

def panel(kind):
    im = Image.new('RGB', (32,32)); p = im.load()
    for y in range(32):
        for x in range(32):
            grain = int(rng.integers(-4,5)) + (x//3%3-1)*3
            p[x,y] = tuple(max(0,c+grain) for c in (67,44,27))
    d=ImageDraw.Draw(im)
    # Broad timber frame; all details intentionally painted at pixel scale.
    d.rectangle((0,0,31,2),fill='#342519'); d.line((1,0,30,0),fill='#795333')
    d.rectangle((0,29,31,31),fill='#382719'); d.line((1,29,30,29),fill='#785133')
    d.rectangle((0,0,2,31),fill='#39281c'); d.line((0,2,0,29),fill='#6d4b2f')
    d.rectangle((29,0,31,31),fill='#35251a'); d.line((29,3,29,28),fill='#735034')
    if kind=='front':
        for x,y in [(4,4),(17,4),(4,17),(17,17)]:
            d.rectangle((x-1,y-1,x+11,y+11),fill='#241d16')
            d.rectangle((x,y,x+10,y+10),fill='#67472c')
            d.line((x,y,x+10,y),fill='#946740'); d.line((x,y,x,y+10),fill='#805a37')
            d.line((x+1,y+10,x+10,y+10),fill='#49321f')
            d.line((x+10,y+1,x+10,y+9),fill='#48321f')
            d.line((x+2,y+3,x+8,y+3),fill='#715031')
            d.rectangle((x+4,y+4,x+6,y+6),fill='#3a2a1a')
            d.rectangle((x+4,y+4,x+5,y+5),fill='#bd9148'); d.point((x+4,y+4),fill='#edc574')
        d.rectangle((12,12,19,19),fill='#30291d')
        d.rectangle((12,12,18,18),fill='#aa7d3b'); d.rectangle((13,13,18,18),fill='#624b2c')
        d.line([(14,13),(16,13),(18,15),(18,16),(16,18),(14,18),(13,16),(13,15),(14,13)],fill='#43c8c4')
        d.line((15,15,15,16),fill='#a0f3df'); d.line((17,15,17,16),fill='#a0f3df')
    else:
        for x in [8,15,22]:
            d.line((x,3,x,28),fill='#302319'); d.line((x+1,3,x+1,28),fill='#795232')
        for x,y in [(5,9),(18,23)]:
            d.line((x,y,x,y+4),fill='#4b321f')
    for x,y in [(0,0),(29,0),(0,29),(29,29)]:
        d.rectangle((x,y,x+2,y+2),fill='#aa7d3b'); d.line((x,y,x+2,y),fill='#dfb65f')
        d.point((x+1,y+1),fill='#735125')
    return im

surfaces={k:panel(k) for k in ['front','back','right','left','top','bottom']}
normals={'north':(0,0,-1),'south':(0,0,1),'east':(1,0,0),'west':(-1,0,0),'up':(0,1,0),'down':(0,-1,0)}
def corners(el,face):
    a,b,c=el['from']; A,B,C=el['to']
    return np.array({'north':[(A,B,c),(a,B,c),(a,b,c),(A,b,c)],'south':[(a,B,C),(A,B,C),(A,b,C),(a,b,C)],'east':[(A,B,C),(A,B,c),(A,b,c),(A,b,C)],'west':[(a,B,c),(a,B,C),(a,b,C),(a,b,c)],'up':[(a,B,c),(A,B,c),(A,B,C),(a,B,C)],'down':[(a,b,C),(A,b,C),(A,b,c),(a,b,c)]}[face],float)
def rotation(el):
    R=np.eye(3)
    for axis,deg in enumerate(el.get('rotation',[0,0,0])):
        t=math.radians(deg); c,s=math.cos(t),math.sin(t)
        M=[np.array([[1,0,0],[0,c,-s],[0,s,c]]),np.array([[c,0,s],[0,1,0],[-s,0,c]]),np.array([[c,-s,0],[s,c,0],[0,0,1]])][axis]
        R=M@R
    return R
def coords(P,n):
    x,y,z=P
    axis=int(np.argmax(np.abs(n))); positive=n[axis]>0
    if axis==0: return ('right',16-z,16-y) if positive else ('left',z,16-y)
    if axis==1: return ('top',x,z) if positive else ('bottom',x,16-z)
    return ('back',x,16-y) if positive else ('front',16-x,16-y)

atlas=Image.new('RGBA',(256,256),(0,0,0,0)); render_faces=[]
for i,el in enumerate(model['elements']):
    el['name']=['Front Panel','Back Panel','Left Panel','Right Panel','Top Panel','Bottom Panel'][i]
    R=rotation(el); origin=np.array(el.get('origin',[0,0,0])); ox=1; oy=i*40+1
    for face in el['faces']:
        local=corners(el,face); world=(local-origin)@R.T+origin; n=R@np.array(normals[face])
        w=round(np.linalg.norm(local[1]-local[0])*2); h=round(np.linalg.norm(local[3]-local[0])*2)
        patch=Image.new('RGBA',(w,h))
        for v in range(h):
            for u in range(w):
                P=world[0]+(world[1]-world[0])*(u+.5)/w+(world[3]-world[0])*(v+.5)/h
                k,s,t=coords(P,n); sx=min(31,max(0,int(s*2))); sy=min(31,max(0,int(t*2)))
                patch.putpixel((u,v),surfaces[k].getpixel((sx,sy))+(255,))
        atlas.paste(patch,(ox,oy)); el['faces'][face]={'uv':[ox,oy,ox+w,oy+h],'texture':0}
        render_faces.append((world,n,patch)); ox+=w+2
    el['autouv']=0

png=ROOT/'seal_archive_texture.png'; atlas.save(png)
buf=io.BytesIO(); atlas.save(buf,format='PNG')
model['resolution']={'width':256,'height':256}
model['textures']=[{'path':str(png),'name':png.name,'folder':'block','namespace':'','id':'0','uuid':str(uuid.uuid4()),'width':256,'height':256,'uv_width':256,'uv_height':256,'mode':'bitmap','saved':True,'render_mode':'default','source':'data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()}]
out=ROOT/'Seal Archive - Textured.bbmodel'; out.write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')

# Orthographic textured preview of the actual supplied geometry.
eye=np.array([1.2,.85,-1.5]); eye/=np.linalg.norm(eye)
right=np.cross(np.array([0,1,0]),eye); right/=np.linalg.norm(right); up=np.cross(eye,right)
size=720; image=np.full((size,size,3),[232,226,215],dtype=np.uint8); depth=np.full((size,size),-1e9)
for vertices,n,patch in render_faces:
    if n@eye<=0: continue
    centered=vertices-np.array([8,8,8]); screen=np.stack([centered@right*25+size/2,-centered@up*25+size/2],axis=1); z=centered@eye
    tex=np.array(patch)[:,:,:3]; H,W=tex.shape[:2]; uv=np.array([[0,0],[W,0],[W,H],[0,H]],float)
    shade=.62+.38*max(0,n@np.array([-.3,.85,-.43]))
    for ids in [(0,1,2),(0,2,3)]:
        ids=list(ids); tri=screen[ids]; A=np.vstack([tri.T,np.ones(3)]); inv=np.linalg.inv(A)
        lo=np.maximum(0,np.floor(tri.min(axis=0)).astype(int)); hi=np.minimum(size-1,np.ceil(tri.max(axis=0)).astype(int))
        for yy in range(lo[1],hi[1]+1):
            xx=np.arange(lo[0],hi[0]+1); weights=inv@np.stack([xx+.5,np.full(len(xx),yy+.5),np.ones(len(xx))]); inside=np.all(weights>=-1e-6,axis=0)
            zz=z[ids]@weights; valid=inside & (zz>depth[yy,xx]); xs=xx[valid]
            if not len(xs): continue
            st=uv[ids].T@weights[:,valid]; tx=np.clip(st[0].astype(int),0,W-1); ty=np.clip(st[1].astype(int),0,H-1)
            image[yy,xs]=(tex[ty,tx]*shade).astype(np.uint8); depth[yy,xs]=zz[valid]
preview=ROOT/'seal_archive_preview.png'; Image.fromarray(image).save(preview)
assert len(model['elements'])==6
assert all(f['texture']==0 and min(f['uv'])>=0 and max(f['uv'])<=256 for e in model['elements'] for f in e['faces'].values())
print(json.dumps({'model':str(out),'texture':str(png),'preview':str(preview),'faces':len(render_faces)}))
