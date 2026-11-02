"""Compile the approved recipe draft into native Hytale crafting assets."""
import copy
import json
import shutil
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = Path('D:/Games/Hytale/install/release/package/game/latest/Assets.zip')
ITEMS = ROOT/'pack/Server/Item/Items/AdvancedStorage'


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')


def apply_recipes(assets=ASSETS):
    draft = json.loads((ROOT/'recipes/advanced-storage-crafts.json').read_text(encoding='utf-8-sig'))
    with zipfile.ZipFile(assets) as archive:
        native = {Path(n).stem:json.loads(archive.read(n)) for n in archive.namelist()
                  if n.startswith('Server/Item/Items/') and n.endswith('.json')}
    # One resource ingredient keeps every chest variant in a single bench entry.
    chest_resource = 'AdvancedStorage_Small_Chest'
    write(ROOT/f'pack/Server/Item/ResourceTypes/{chest_resource}.json',
          {'Icon':f'Icons/ResourceTypes/{chest_resource}.png'})
    icon=ROOT/f'pack/Common/Icons/ResourceTypes/{chest_resource}.png'
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT/'pack/Common/Icons/ItemsGenerated/AdvancedStorage_Seal_Archive.png',icon)
    for chest, source in native.items():
        if not (chest.startswith('Furniture_') and '_Chest_Small' in chest):
            continue
        item=copy.deepcopy(source)
        resources=item.setdefault('ResourceTypes',[])
        if not any(r['Id']==chest_resource for r in resources):
            resources.append({'Id':chest_resource})
        write(ROOT/f'pack/Server/Item/Items/AdvancedStorageCompat/{chest}.json',item)
    for obsolete in (ROOT/'pack/Server/Item/Recipes/AdvancedStorage').glob('AdvancedStorage_Seal_Archive_*.json'):
        obsolete.unlink()
    recipes = copy.deepcopy(draft['recipes'])
    recipes['AdvancedStorage_Blank_Seal'] = {
        'ingredients':[{'itemId':i,'quantity':q} for i,q in
                       [('Ingredient_Fibre',4),('Ingredient_Tree_Sap',1),('Ingredient_Life_Essence',4)]],
        'outputQuantity':1,'timeSeconds':1,'bench':'Arcanebench'}
    recipes['AdvancedStorage_Runic_Book'] = {
        'ingredients':[{'itemId':'AdvancedStorage_Blank_Seal','quantity':3},
                       {'itemId':'Ingredient_Hide_Medium','quantity':6}],
        'outputQuantity':1,'timeSeconds':1,'bench':'Arcanebench'}
    # Reuse native visual references only; do not inherit recipe-learning or weapon interactions.
    for item_id, base, name, description in [
        ('AdvancedStorage_Blank_Seal','Recipe_Page','Blank Seal','An uninscribed seal, ready to receive storage runes.'),
        ('AdvancedStorage_Runic_Book','Weapon_Spellbook_Grimoire_Brown','Runic Book','A bound collection of blank seals for a Runic Catalog.')]:
        source = native[base]
        item = {k:copy.deepcopy(source[k]) for k in
                ('Model','Texture','Icon','IconProperties','Scale','ItemSoundSetId') if k in source}
        item.update(TranslationProperties={'Name':f'server.items.{item_id}.name',
                                          'Description':f'server.items.{item_id}.description'},
                    PlayerAnimationsId='Item', Categories=['Items.Ingredients'], MaxStack=100, Quality='Common')
        write(ITEMS/f'{item_id}.json',item)
        for language in ('en-US','pt-BR'):
            path=ROOT/f'pack/Server/Languages/{language}/server.lang'
            lines=[s for s in path.read_text(encoding='utf-8').splitlines()
                   if not s.startswith(f'items.{item_id}.')]
            path.write_text('\n'.join(lines)+f'\nitems.{item_id}.name = {name}\nitems.{item_id}.description = {description}\n',encoding='utf-8')
    replacements = {'Recipe_Page':'AdvancedStorage_Blank_Seal',
                    'Weapon_Spellbook_Grimoire_Brown':'AdvancedStorage_Runic_Book'}
    groups = {'Wood_Aspen_Trunk':'Wood_Trunk', 'Wood_Azure_Trunk':'Wood_Trunk',
              'Ingredient_Crystal_White':'Crystal_Shards', 'Rock_Stone_Brick':'Bricks',
              'Furniture_Crude_Chest_Small':chest_resource}
    for item_id, recipe in recipes.items():
        inputs=[]
        for ingredient in recipe['ingredients']:
            key=ingredient['itemId']; quantity=ingredient['quantity']
            if key in groups:
                inputs.append({'ResourceTypeId':groups[key],'Quantity':quantity})
            else:
                key=replacements.get(key,key)
                if key not in native and not (ITEMS/f'{key}.json').is_file():
                    raise ValueError(f'Unknown ingredient: {key}')
                inputs.append({'ItemId':key,'Quantity':quantity})
        output={'ItemId':item_id,'Quantity':recipe['outputQuantity']}
        compiled={'Input':inputs,'Output':[output], 'OutputQuantity':recipe['outputQuantity'],
                  'TimeSeconds':recipe['timeSeconds'],
                  'BenchRequirement':[{'Id':recipe['bench'],'Type':'Crafting','Categories':['Arcane_Misc']}]}
        path=ITEMS/f'{item_id}.json'; item=json.loads(path.read_text(encoding='utf-8'))
        item['Recipe']=compiled; write(path,item)
    return len(recipes)


if __name__=='__main__':
    print(f'Compiled {apply_recipes()} recipes with grouped small-chest ingredients.')
