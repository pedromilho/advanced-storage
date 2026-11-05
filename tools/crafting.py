"""Compile the approved recipe draft into native Hytale crafting assets."""
import copy
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = Path('D:/Games/Hytale/install/release/package/game/latest/Assets.zip')
ITEMS = ROOT/'pack/Server/Item/Items/AdvancedStorage'
COBBLE_RESOURCE = 'AdvancedStorage_Cobble'


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')


def native_resources(native, item_id):
    item = native.get(item_id, {})
    while item and 'ResourceTypes' not in item:
        item = native.get(item.get('Parent'), {})
    return item.get('ResourceTypes') or []


def is_cobble_or_stone(item_id, item):
    if not item_id.startswith('Rock_'):
        return False
    if item_id.endswith(('_Cobble', '_Cobble_Mossy')) or item.get('Parent') == 'Rock_Stone':
        return True
    return item_id in {'Rock_Stone', 'Rock_Stone_Mossy', 'Rock_Stone_Aqua',
                       'Rock_Chalk', 'Rock_Magma_Cooled', 'Rock_Dawnstone'}


def apply_recipes(assets=ASSETS):
    draft = json.loads((ROOT/'recipes/advanced-storage-crafts.json').read_text(encoding='utf-8-sig'))
    chest_resource = 'AdvancedStorage_Small_Chest'
    chest_icon = f'Icons/ResourceTypes/{chest_resource}.png'
    with zipfile.ZipFile(assets) as archive:
        native = {Path(n).stem:json.loads(archive.read(n)) for n in archive.namelist()
                  if n.startswith('Server/Item/Items/') and n.endswith('.json')}
        native_icon = native['Furniture_Crude_Chest_Small']['Icon']
        icon_path = ROOT/'pack/Common'/chest_icon
        icon_path.parent.mkdir(parents=True, exist_ok=True)
        icon_path.write_bytes(archive.read('Common/' + native_icon))
    # One resource ingredient keeps every chest variant in a single bench entry.
    write(ROOT/f'pack/Server/Item/ResourceTypes/{chest_resource}.json', {'Icon':chest_icon})
    write(ROOT/f'pack/Server/Item/ResourceTypes/{COBBLE_RESOURCE}.json',
          {'Icon':'Icons/ResourceTypes/Rock_Stone_Cobble.png'})
    for item_id, source in native.items():
        if item_id.startswith('Furniture_') and '_Chest_Small' in item_id:
            resource = chest_resource
        elif is_cobble_or_stone(item_id, source):
            resource = COBBLE_RESOURCE
        else:
            continue
        item=copy.deepcopy(source)
        resources = item['ResourceTypes'] = copy.deepcopy(native_resources(native, item_id))
        if not any(r['Id'] == resource for r in resources):
            resources.append({'Id':resource})
        write(ROOT/f'pack/Server/Item/Items/AdvancedStorageCompat/{item_id}.json',item)
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
                    'Weapon_Spellbook_Grimoire_Brown':'AdvancedStorage_Runic_Book',
                    'Ingredient_Life_Essence_100':'Ingredient_Life_Essence'}
    groups = {'Rock_Stone_Brick':COBBLE_RESOURCE, 'Rock_Stone_Cobble':COBBLE_RESOURCE,
              'Furniture_Crude_Chest_Small':chest_resource}
    for item_id, recipe in recipes.items():
        inputs=[]
        for ingredient in recipe['ingredients']:
            key=ingredient['itemId']; quantity=ingredient['quantity']
            resource = groups.get(key) or next(
                (entry['Id'] for entry in native_resources(native, key)
                 if entry['Id'] in ('Wood_Trunk', 'Crystal_Shards')), None)
            if resource:
                inputs.append({'ResourceTypeId':resource,'Quantity':quantity})
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
