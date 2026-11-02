"""Author Hytale's native six-direction auto-connection rules for approved models."""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TAG = 'AdvancedStorage_Port'
FACES = ('North', 'East', 'South', 'West', 'Up', 'Down')
BLOCKS = {
    'Straight': 'AdvancedStorage_Runic_Conduit',
    'Curve': 'AdvancedStorage_Runic_Conduit_Curve',
    'T': 'AdvancedStorage_Runic_Conduit_T',
    'Cross': 'AdvancedStorage_Runic_Conduit_Cross',
    'Up': 'AdvancedStorage_Runic_Conduit_Up',
    'UpDown': 'AdvancedStorage_Runic_Conduit_UpDown',
    'All': 'AdvancedStorage_Runic_Conduit_All',
    'Corner3': 'AdvancedStorage_Runic_Conduit_Corner3'
}
RULESET = 'AdvancedStorage_Conduit'


def neighbor(face):
    return {'Type': 'FaceTag', 'FaceTags': {face: [TAG]}, 'MatchType': 'All'}


def exact(faces):
    return {'Type': 'And', 'Rules': [neighbor(f) if f in faces else
            {'Type': 'Not', 'Rule': neighbor(f)} for f in FACES]}


def main():
    # All eight distinct 3D topologies, plus the single/isolated fallbacks.
    cases = [('All', set(FACES)),
             ('UpDown', {'East', 'South', 'West', 'Up', 'Down'}),
             ('Up', {'East', 'South', 'West', 'Up'}),
             ('Cross', {'North', 'East', 'South', 'West'}),
             ('Corner3', {'South', 'West', 'Up'}),
             ('T', {'East', 'South', 'West'}),
             ('Curve', {'South', 'West'}), ('Straight', {'North', 'South'}),
             ('Straight', {'South'}), ('Straight', set())]
    patterns = [{
        'Rule': exact(faces),
        'RotationTransforms': [{'Yaw': 'All', 'Pitch': 'All', 'Roll': 'All'}]
                              if 0 < len(faces) < 6 else [{}],
        'Output': {'ShapeId': shape}
    } for shape, faces in cases]
    # Advertise compatible sockets independently of the currently displayed
    # shape. Otherwise two elbows can lose recognition and oscillate on updates.
    tags = {face: [TAG] for face in FACES}
    shapes = {shape: {'Type': 'Block', 'Block': block, 'FaceTags': copy.deepcopy(tags)}
              for shape, block in BLOCKS.items()}
    # Container interaction states have generated IDs. A namespaced block tag
    # survives those state changes, unlike an exact match of the base item ID.
    shapes['Archive'] = {'Type': 'TagPattern', 'TagPattern': 'AdvancedStorage_Archive',
                         'FaceTags': copy.deepcopy(tags)}
    # The core has four authored side sockets at Y=16; its tall crystal is not
    # a vertical connector. Never invent up/down ports for this model.
    shapes['Core'] = {'Type': 'TagPattern', 'TagPattern': 'AdvancedStorage_Core',
                      'FaceTags': {face: [TAG] for face in FACES[:4]}}
    shapes['Catalog'] = {'Type':'TagPattern','TagPattern':'AdvancedStorage_Catalog',
                         'FaceTags':{face:[TAG] for face in ('North','East','West')}}
    asset = {'Shapes': shapes, 'Patterns': patterns, 'UpdateMode': 'UpdatePlaceUpdate'}
    path = ROOT/f'pack/Server/Item/ConnectedBlockRuleSets/{RULESET}.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(asset, indent=2)+'\n', encoding='utf-8')
    for block in BLOCKS.values():
        path = ROOT/f'pack/Server/Item/Items/AdvancedStorage/{block}.json'
        item = json.loads(path.read_text(encoding='utf-8'))
        item['BlockType']['ConnectedBlockRuleSet'] = {
            'Type': 'Patterned', 'RuleSetId': RULESET,
            'TemplatedShapes': {shape: {'Block': target} for shape, target in BLOCKS.items()}
        }
        path.write_text(json.dumps(item, indent=2)+'\n', encoding='utf-8')
    for language in ('en-US', 'pt-BR'):
        path = ROOT/f'pack/Server/Languages/{language}/server.lang'
        lines = path.read_text(encoding='utf-8').splitlines()
        for i, line in enumerate(lines):
            if any(line.startswith(f'items.{block}.description =') for block in BLOCKS.values()):
                lines[i] = line.split(' =')[0]+' = Copper and glass conduit. Automatically connects in six directions to conduits and the Seal Archive. Item transfer is not yet active.'
        path.write_text('\n'.join(lines)+'\n', encoding='utf-8')


if __name__ == '__main__':
    main()
