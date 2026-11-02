"""Author five non-stackable Storage Seal items and their editable game models."""
import base64
import io
import json
import math
import uuid

import numpy as np
from PIL import Image, ImageDraw, ImageColor

from build_pack import ROOT, SEAL_MODELS
from texture_runic_conduit import corners, NORMALS, render


def model_for(tier, color):
    data=[('Parchment',[-7,5,-.5],[7,27,.5],'paper'),
          ('Upper roll',[-8,26,-1],[8,28,1],'roll'),
          ('Lower roll',[-8,4,-1],[8,6,1],'roll'),
          ('Upper left clasp',[-9,25.5,-1.5],[-7,28.5,1.5],'metal'),
          ('Upper right clasp',[7,25.5,-1.5],[9,28.5,1.5],'metal'),
          ('Lower left clasp',[-9,3.5,-1.5],[-7,6.5,1.5],'metal'),
          ('Lower right clasp',[7,3.5,-1.5],[9,6.5,1.5],'metal'),
          ('Runic plate',[-4,12,.5],[4,20,1.5],'rune'),
          ('Upper ribbon',[-1,20,.5],[1,26,1],'ribbon'),
          ('Lower ribbon',[-1,6,.5],[1,12,1],'ribbon'),
          ('Tier plate',[-5,7,1],[5,9,1.5],'tier')]
    # Hand animations can present either side. Put the relief and tier marks
    # outside both parchment faces, rather than hiding one behind opaque paper.
    data += [(name+' (back)',[low[0],low[1],-high[2]],
              [high[0],high[1],-low[2]],kind)
             for name,low,high,kind in data if kind in ('rune','ribbon','tier')]
    elements=[];patches=[];faces=[];x=y=2;rowh=0
    for name,low,high,kind in data:
        e={'name':name,'type':'cube','uuid':str(uuid.uuid4()),'from':low,'to':high,
           'origin':[0,0,0],'rotation':[0,0,0],'box_uv':False,'autouv':0,
           'export':True,'shading_mode':'flat','double_sided':False,'faces':{}}
        for face in NORMALS:
            points=corners(e,face)
            w=round(np.linalg.norm(points[1]-points[0])*4)
            h=max(1,round(np.linalg.norm(points[3]-points[0])*4))
            # Half-unit-thick ribbons retain a consistent four pixels/unit.
            if x+w+2>256:x=2;y+=rowh+2;rowh=0
            patch=Image.new('RGBA',(w,h));p=patch.load()
            for v in range(h):
                for u in range(w):
                    grain=(u*13+v*7+u*v)%7-3
                    base={'paper':(220,200,155),'roll':(199,174,119),
                          'metal':(165,114,56),'rune':(84,63,39),'tier':(84,63,39),
                          'ribbon':tuple(c//3 for c in color)}[kind]
                    p[u,v]=tuple(max(0,c+grain) for c in base)+(255,)
            d=ImageDraw.Draw(patch)
            if kind in ('metal','rune','tier'):
                d.rectangle((0,0,w-1,h-1),outline='#E4BA6C')
                if min(w,h)>5:d.rectangle((2,2,w-3,h-3),outline='#9D733B')
            if kind=='paper' and face in ('north','south'):
                d.rectangle((2,2,w-3,h-3),outline='#AF976A')
                for row in [12,20,64,72]:
                    for col in range(7,w-7,7):
                        d.line((col,row,col+2,row+2),fill='#826D49')
                        d.line((col,row,col,row+3),fill='#826D49')
                # One to five distinct pips make tiers readable without color.
                start=w//2-(tier*5-2)//2
                for pip in range(tier):
                    xx=start+pip*5;d.rectangle((xx,h-10,xx+2,h-7),fill=color)
            if kind=='rune' and face in ('north','south'):
                cx=w//2;cy=h//2;r=min(w,h)//2-5
                d.ellipse((cx-r,cy-r,cx+r,cy+r),outline=color,width=2)
                for xx in [cx-5,cx,cx+5]:d.line((xx,cy-5,xx,cy+5),fill=color,width=2)
                if tier>=3:d.point((cx,cy-r-3),fill=color)
                if tier>=4:
                    d.point((cx-r-3,cy),fill=color);d.point((cx+r+3,cy),fill=color)
                if tier==5:d.point((cx,cy+r+3),fill='#FFFFDF')
            if kind=='tier' and face in ('north','south'):
                start=w//2-(tier*5-2)//2
                for pip in range(tier):
                    xx=start+pip*5;d.rectangle((xx,2,xx+2,h-3),fill=color)
            if kind=='roll' and face in ('east','west'):
                d.rectangle((1,1,w-2,h-2),outline='#8D713F')
            patches.append((x,y,patch));e['faces'][face]={'uv':[x,y,x+w,y+h],'texture':0}
            faces.append((points,np.array(NORMALS[face]),patch,'copper'))
            x+=w+2;rowh=max(rowh,h)
        elements.append(e)
    height=1<<(y+rowh+1).bit_length();atlas=Image.new('RGBA',(256,height))
    for x,y,p in patches:atlas.paste(p,(x,y))
    stream=io.BytesIO();atlas.save(stream,format='PNG')
    path=SEAL_MODELS[f'Storage_Seal_Tier_{tier}'];path.parent.mkdir(parents=True,exist_ok=True)
    png=path.with_suffix('.png');atlas.save(png)
    model={'meta':{'format_version':'5.0','model_format':'hytale_prop','box_uv':False},
           'name':f'Storage Seal - Tier {tier}','resolution':{'width':256,'height':height},
           'elements':elements,'groups':[],'outliner':[e['uuid'] for e in elements],
           'textures':[{'name':png.name,'path':str(png),'id':'0','uuid':str(uuid.uuid4()),
                        'width':256,'height':height,'uv_width':256,'uv_height':height,
                        'mode':'bitmap','saved':True,'render_mode':'default',
                        'source':'data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode()}]}
    path.write_text(json.dumps(model,ensure_ascii=False,indent=2),encoding='utf-8')
    return faces


def main():
    config=json.loads((ROOT/'config/storage_seals.json').read_text())
    translations=[];previews=[]
    for settings in config['tiers']:
        tier=settings['tier'];capacity=settings['capacity'];color=ImageColor.getrgb(settings['color'])
        name=f'Storage_Seal_Tier_{tier}';item_id='AdvancedStorage_'+name
        faces=model_for(tier,color)
        icon=ROOT/f'pack/Common/Icons/ItemsGenerated/{item_id}.png'
        render(faces,icon,[.45,.45,2],transparent=True,output_size=64,
               render_resolution=256,view_scale=256/34)
        item={'TranslationProperties':{'Name':f'server.items.{item_id}.name','Description':f'server.items.{item_id}.description'},
              'PlayerAnimationsId':'Item','Categories':['Upgrade'],'Quality':settings['quality'],
              'MaxStack':1,'Icon':f'Icons/ItemsGenerated/{item_id}.png',
              'Model':f'Items/AdvancedStorage/{name}.blockymodel',
              'Texture':f'Items/AdvancedStorage/{name}_Texture.png',
              'IconProperties':{'Scale':.8,'Rotation':[0,15,0],'Translation':[0,-16]},
              'Tags':{'Type':['Upgrade'],'Family':['AdvancedStorage'],'AdvancedStorageRole':['StorageSeal'],
                      'AdvancedStorageTier':[str(tier)],'AdvancedStorageCapacity':[str(capacity)]}}
        (ROOT/f'pack/Server/Item/Items/AdvancedStorage/{item_id}.json').write_text(json.dumps(item,indent=2)+'\n')
        translations.extend([f'items.{item_id}.name = Storage Seal - Tier {tier}',
            f'items.{item_id}.description = Capacity: {capacity:,} individual items. One Seal Archive slot. Retains its contents when removed.'])
        previews.append((tier,capacity,Image.open(icon).convert('RGBA').resize((192,192),Image.Resampling.NEAREST)))
    for language in ('en-US','pt-BR'):
        p=ROOT/f'pack/Server/Languages/{language}/server.lang'
        lines=[s for s in p.read_text(encoding='utf-8').splitlines() if not s.startswith('items.AdvancedStorage_Storage_Seal_Tier_')]
        p.write_text('\n'.join(lines+translations)+'\n',encoding='utf-8')
    sheet=Image.new('RGB',(1100,260),'#E6E1D7');d=ImageDraw.Draw(sheet)
    for i,(tier,capacity,preview) in enumerate(previews):
        sheet.paste(preview,(i*220+14,8),preview)
        d.text((i*220+70,207),f'TIER {tier}',fill='#30261B')
        d.text((i*220+58,229),f'{capacity:,} items',fill='#50432F')
    out=ROOT/'docs/storage_seals_preview.png';sheet.save(out)
    print('Created five Storage Seal models, items and 64px inventory icons.')


if __name__=='__main__':main()
