"""Check exported geometry, asset references and the confirmed four-slot rule."""
import itertools
import json
import math
import struct
from pathlib import Path
import unittest
import zipfile

from build_pack import DEFAULT_MODEL, CONDUIT_MODEL, CONDUIT_VARIANTS, CORE_MODEL, CATALOG_MODEL, SEAL_MODELS, ROOT, export_model


def rotate_euler(point, degrees):
    x, y, z = point
    a, b, c = map(math.radians, degrees)
    y, z = y*math.cos(a)-z*math.sin(a), y*math.sin(a)+z*math.cos(a)
    x, z = x*math.cos(b)+z*math.sin(b), -x*math.sin(b)+z*math.cos(b)
    x, y = x*math.cos(c)-y*math.sin(c), x*math.sin(c)+y*math.cos(c)
    return [x, y, z]


def rotate_quaternion(point, q):
    # Independent rotation-matrix calculation, rather than exporter helpers.
    x, y, z, w = [q[k] for k in 'xyzw']
    matrix = [
        [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
        [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
        [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]
    ]
    return [sum(a*b for a, b in zip(row, point)) for row in matrix]


class PackChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = json.loads(DEFAULT_MODEL.read_text(encoding='utf-8'))
        cls.model, cls.png, cls.count = export_model(cls.source)

    def test_all_source_corners_keep_their_world_position(self):
        self.assert_geometry(self.source, self.model, turned=True)

    def assert_geometry(self, source, model, *, turned):
        exported = []

        def collect(node, parents):
            chain = parents + [node]
            if node['shape']['type'] in ('box','quad'):
                exported.append((node, chain))
            for child in node.get('children', []):
                collect(child, chain)

        for root in model['nodes']:
            collect(root, [])
        # Order follows the source outliner, not the elements array.
        lookup = {el['uuid']: el for el in source['elements']}
        groups = {g['uuid']: g for g in source.get('groups', [])}
        ordered = []

        def source_order(ref, ancestors):
            if isinstance(ref, str):
                ordered.append((lookup[ref], ancestors))
            else:
                for child in ref['children']:
                    source_order(child, ancestors+[groups[ref['uuid']]])

        for ref in source['outliner']:
            source_order(ref, [])
        self.assertEqual(len(exported), len(ordered))
        for (element, ancestors), (node, chain) in zip(ordered, exported):
            original_origin = element.get('origin', [0, 0, 0])
            shape = node['shape']
            if shape['type']=='quad':
                w,h=[shape['settings']['size'][k] for k in 'xy']
                dimensions={'X':[0,h,w],'Y':[w,0,h],'Z':[w,h,0]}[shape['settings']['normal'][-1]]
            else:
                dimensions=[shape['settings']['size'][k] for k in 'xyz']
            for sides in itertools.product([0, 1], repeat=3):
                original = [element['to' if side else 'from'][i]-original_origin[i]
                            for i, side in enumerate(sides)]
                expected = rotate_euler(original, element.get('rotation', [0, 0, 0]))
                expected = [a+b for a, b in zip(expected, original_origin)]
                for group in reversed(ancestors):
                    origin = group.get('origin', [0, 0, 0])
                    expected = [a-b for a, b in zip(expected, origin)]
                    expected = rotate_euler(expected, group.get('rotation', [0, 0, 0]))
                    expected = [a+b for a, b in zip(expected, origin)]
                if turned:
                    expected = [-expected[0], expected[1], -expected[2]]
                local = [shape['offset'][k] + (side-.5)*dimension*shape['stretch'][k]
                         for k, side,dimension in zip('xyz', sides,dimensions)]
                actual = local
                for parent in reversed(chain):
                    actual = rotate_quaternion(actual, parent['orientation'])
                    actual = [a+parent['position'][k] for a, k in zip(actual, 'xyz')]
                for a, b in zip(actual, expected):
                    self.assertAlmostEqual(a, b, places=7)

    def test_drawers_face_the_game_front(self):
        drawer_group = next(g for g in self.source['groups'] if g['name'] == 'gavetas')
        root_index = next(i for i, ref in enumerate(self.source['outliner'])
                          if ref['uuid'] == drawer_group['uuid'])
        root = self.model['nodes'][root_index]
        for drawer in root['children']:
            center = [drawer['shape']['offset'][k] for k in 'xyz']
            center = rotate_quaternion(center, drawer['orientation'])
            center = [v+drawer['position'][k] for v, k in zip(center, 'xyz')]
            center = rotate_quaternion(center, root['orientation'])
            self.assertGreater(center[2]+root['position']['z'], 15)

    def test_pack_contains_all_custom_assets(self):
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            for item_id in ('AdvancedStorage_Seal_Archive', 'AdvancedStorage_Runic_Conduit', 'AdvancedStorage_Arcane_Core', 'AdvancedStorage_Runic_Catalog',
                            *('AdvancedStorage_'+name for name in CONDUIT_VARIANTS)):
                item = json.loads(pack.read(f'Server/Item/Items/AdvancedStorage/{item_id}.json'))
                paths = [item['Icon'], item['BlockType']['CustomModel'],
                         item['BlockType']['CustomModelTexture'][0]['Texture']]
                for path in paths:
                    self.assertIn('Common/'+path, pack.namelist())
            self.assertEqual(pack.read('Common/Blocks/AdvancedStorage/Seal_Archive_Texture.png'), self.png)
            self.assertIsNone(pack.testzip())

    def test_inventory_icons_use_native_size(self):
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            icons = [n for n in pack.namelist() if n.startswith('Common/Icons/') and n.endswith('.png')]
            self.assertEqual(sum(n.startswith("Common/Icons/ItemsGenerated/") for n in icons), 16)
            self.assertIn("Common/Icons/ResourceTypes/AdvancedStorage_Small_Chest.png", icons)
            for name in icons:
                with self.subTest(icon=name):
                    data = pack.read(name)
                    self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                    self.assertEqual(struct.unpack('>II', data[16:24]), (64, 64))

    def test_storage_seal_tiers_match_confirmed_capacities(self):
        settings=json.loads((ROOT/'config/storage_seals.json').read_text())
        expected=[1000,4000,16000,64000,128000]
        self.assertEqual(settings['capacity_unit'],'individual_items')
        self.assertEqual(settings['archive_slots'],4)
        self.assertEqual([t['capacity'] for t in settings['tiers']],expected)
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            for tier,capacity in enumerate(expected,1):
                name=f'Storage_Seal_Tier_{tier}'
                item=json.loads(pack.read(f'Server/Item/Items/AdvancedStorage/AdvancedStorage_{name}.json'))
                self.assertEqual(item['MaxStack'],1)
                self.assertNotIn('BlockType',item)
                self.assertNotIn('Consumable',item)
                self.assertEqual(item['Tags']['AdvancedStorageCapacity'],[str(capacity)])
                self.assertEqual(item['Tags']['AdvancedStorageTier'],[str(tier)])
                for path in [item['Model'],item['Texture'],item['Icon']]:
                    self.assertIn('Common/'+path,pack.namelist())
                source=json.loads(SEAL_MODELS[name].read_text(encoding='utf-8'))
                elements={e['name']:e for e in source['elements']}
                front=elements['Runic plate'];back=elements['Runic plate (back)']
                self.assertGreater(front['to'][2],.5)
                self.assertLess(back['from'][2],-.5)
                self.assertEqual(front['from'][2],-back['to'][2])
                self.assertEqual(front['to'][2],-back['from'][2])
                self.assertEqual(back['faces']['north']['texture'],0)
                self.assertIn('Tier plate (back)',elements)
                model,texture,_=export_model(source,turn_to_player=False)
                self.assert_geometry(source,model,turned=False)
                self.assertEqual(pack.read('Common/'+item['Texture']),texture)
                self.assertEqual(json.loads(pack.read('Common/'+item['Model'])),model)

    def test_core_preserves_authored_geometry_and_crystal_material(self):
        source=json.loads(CORE_MODEL.read_text(encoding='utf-8'))
        model,texture,count=export_model(source,turn_to_player=False)
        self.assertEqual(count,136)
        self.assert_geometry(source,model,turned=False)
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            self.assertEqual(json.loads(pack.read('Common/Blocks/AdvancedStorage/Arcane_Core.blockymodel')),model)
            self.assertEqual(pack.read('Common/Blocks/AdvancedStorage/Arcane_Core_Texture.png'),texture)
            boxes=json.loads(pack.read('Server/Item/Block/Hitboxes/AdvancedStorage_Arcane_Core.json'))['Boxes']
            self.assertEqual(max(b['Max']['Y'] for b in boxes),54/32)

    def test_catalog_preserves_inclined_book_and_thin_planes(self):
        source=json.loads(CATALOG_MODEL.read_text(encoding='utf-8'))
        model,texture,count=export_model(source,turn_to_player=False)
        self.assertEqual(count,46)
        self.assert_geometry(source,model,turned=False)
        shapes=[]
        def visit(node):
            shapes.append(node['shape'])
            for child in node.get('children',[]):visit(child)
        for node in model['nodes']:visit(node)
        quads=[shape for shape in shapes if shape['type']=='quad']
        self.assertEqual(len(quads),8)
        for shape in quads:
            self.assertTrue(shape['doubleSided'])
            self.assertEqual(set(shape['settings']['size']),{'x','y'})
            self.assertEqual(set(shape['textureLayout']),{'front'})
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            self.assertEqual(json.loads(pack.read('Common/Blocks/AdvancedStorage/Runic_Catalog.blockymodel')),model)
            self.assertEqual(pack.read('Common/Blocks/AdvancedStorage/Runic_Catalog_Texture.png'),texture)
            boxes=json.loads(pack.read('Server/Item/Block/Hitboxes/AdvancedStorage_Runic_Catalog.json'))['Boxes']
            self.assertEqual(len(boxes),38)

    def test_variant_geometry_including_rotated_parent_groups(self):
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            for name, path in CONDUIT_VARIANTS.items():
                with self.subTest(variant=name):
                    source = json.loads(path.read_text(encoding='utf-8'))
                    model, texture, count = export_model(source, turn_to_player=False)
                    self.assert_geometry(source, model, turned=False)
                    self.assertEqual(json.loads(pack.read(f'Common/Blocks/AdvancedStorage/{name}.blockymodel')), model)
                    self.assertEqual(pack.read(f'Common/Blocks/AdvancedStorage/{name}_Texture.png'), texture)
                    item = json.loads(pack.read(f'Server/Item/Items/AdvancedStorage/AdvancedStorage_{name}.json'))
                    self.assertTrue(item['BlockType']['RequiresAlphaBlending'])
                    hitbox = item['BlockType']['HitboxType']
                    self.assertIn(f'Server/Item/Block/Hitboxes/{hitbox}.json', pack.namelist())

    def test_conduit_geometry_keeps_authored_mounting_height(self):
        source = json.loads(CONDUIT_MODEL.read_text(encoding='utf-8'))
        model, png, count = export_model(source, turn_to_player=False)
        self.assertEqual(count, 13)
        self.assert_geometry(source, model, turned=False)
        hitbox = json.loads((ROOT/'pack/Server/Item/Block/Hitboxes/AdvancedStorage_Runic_Conduit.json').read_text())
        self.assertEqual(hitbox['Boxes'][0]['Min'], {'X': .375, 'Y': .375, 'Z': 0})
        self.assertEqual(hitbox['Boxes'][0]['Max'], {'X': .625, 'Y': .625, 'Z': 1})
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            self.assertEqual(pack.read('Common/Blocks/AdvancedStorage/Runic_Conduit_Texture.png'), png)

    def test_seal_archive_has_exactly_four_slots(self):
        item = json.loads((ROOT/'pack/Server/Item/Items/AdvancedStorage/AdvancedStorage_Seal_Archive.json').read_text())
        self.assertEqual(item['BlockType']['BlockEntity']['Components']['ItemContainerBlock']['Capacity'], 4)

    def test_interfaces_and_interaction_hint_follow_native_controls(self):
        for name in ('Seal_Archive', 'Runic_Catalog', 'Arcane_Core'):
            item = json.loads((ROOT/f'pack/Server/Item/Items/AdvancedStorage/AdvancedStorage_{name}.json').read_text())
            self.assertEqual(item['BlockType']['Interactions']['Use']['Interactions'], [{'Type': 'AdvancedStorageOpen'}])
            self.assertEqual(item['BlockType']['InteractionHint'], 'server.interactionHints.openDoor')
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            ui=[path for path in pack.namelist() if path.endswith('.ui')]
            expected_ui={f'Common/UI/Custom/AdvancedStorage/{page}.ui' for page in ('Archive','Catalog','Core','StorageTooltip')}
            expected_ui.update(f'Common/UI/Custom/AdvancedStorage/{page}{grid}Grid.ui' for page in ('Archive','Catalog') for grid in ('Stored','Inventory','Hotbar','Backpack'))
            self.assertEqual(set(ui),expected_ui)
            for page in ('Archive','Catalog'):
                markup=pack.read(f'Common/UI/Custom/AdvancedStorage/{page}.ui').decode()
                for grid in ('Stored','Inventory','Hotbar','Backpack'):
                    self.assertIn(f'Group #{grid}Host',markup)
                    fragment=pack.read(f'Common/UI/Custom/AdvancedStorage/{page}{grid}Grid.ui').decode()
                    self.assertEqual(fragment.count('ItemGrid #'),1)
                    self.assertIn(f'ItemGrid #{grid}',fragment)
                    self.assertIn('InfoDisplay: None;',fragment)
            self.assertIn('$C.@Container',pack.read('Common/UI/Custom/AdvancedStorage/Catalog.ui').decode())
            source=pack.read('Common/UI/Custom/AdvancedStorage/Catalog.ui').decode()
            self.assertNotIn('Open Storage',source)
            archive=pack.read('Common/UI/Custom/AdvancedStorage/Archive.ui').decode()
            self.assertIn('#ArchiveInfo',archive)
            self.assertIn('SlotsPerRow: 4',archive)
            for page in (archive,source):
                self.assertEqual(page.count('InfoDisplay: None;'),4)
                self.assertNotIn('TextTooltipStyle:',page)
                self.assertIn('Group #StorageRoot',page)
                self.assertNotIn('Text: "Drag',page)

            tooltip=pack.read('Common/UI/Custom/AdvancedStorage/StorageTooltip.ui').decode()
            self.assertIn('Group #StorageTooltipCanvas',tooltip)
            self.assertIn('FontSize: 18',tooltip)
            self.assertIn('RenderItalics: true',tooltip)
            self.assertIn('Border: 24',tooltip)
            self.assertTrue(all(p.startswith('Common/UI/Custom/AdvancedStorage/') for p in ui))
            self.assertIn('Anchor: (Left: 24, Top: 21, Right: 24, Bottom: 24)',tooltip)
            for path in pack.namelist():
                if path.startswith('Common/UI/Custom/AdvancedStorage/Tooltip/') and path.endswith('.png'):
                    self.assertNotIn('@2x',path)
                    dimensions=struct.unpack('>II',pack.read(path)[16:24])
                    self.assertEqual(dimensions,(33,24) if 'Arrow' in path else (54,54))
            core=pack.read('Common/UI/Custom/AdvancedStorage/Core.ui').decode()
            self.assertNotIn('ItemGrid',core)
            for path in ('Arcane_Core','Seal_Archive','Runic_Catalog'):
                self.assertIn('AdvancedStorage_'+path+'.png',core)
            # Custom UI resolves textures inside its UI asset namespace, not Common/Icons.
            import re
            for texture in re.findall(r'Background:\s*"([^"]+\.png)"',core):
                self.assertNotIn('..',texture)
                self.assertIn('Common/UI/Custom/AdvancedStorage/'+texture,pack.namelist())
            for selector in ('#Stored', '#Inventory', '#Hotbar', '#Search', '#Alphabetical', '#Quantity'):
                self.assertIn(selector,source)

    def test_transparency_survives_conduit_export_and_packaging(self):
        source = json.loads(CONDUIT_MODEL.read_text(encoding='utf-8'))
        model, _, _ = export_model(source, turn_to_player=False)
        kinds = {g['uuid']: g['name'] for g in source['groups']}
        for ref, group in zip(source['outliner'], model['nodes']):
            should_blend = kinds[ref['uuid']] in ('vidrocano', 'energia')
            for child in group['children']:
                for face in child['shape']['textureLayout'].values():
                    self.assertEqual(face.get('transparent', False), should_blend)
                if kinds[ref['uuid']] == 'energia':
                    self.assertEqual(child['shape']['shadingMode'], 'fullbright')
        with zipfile.ZipFile(ROOT/'dist/advanced-storage-0.1.0.zip') as pack:
            packed = json.loads(pack.read('Common/Blocks/AdvancedStorage/Runic_Conduit.blockymodel'))
            self.assertEqual(packed, model)

    def test_six_direction_shapes_match_actual_energy_endpoints(self):
        # Independently inspect exported geometry so connection rules cannot
        # claim an arm that the model does not actually have.
        shapes = {'Runic_Conduit': (CONDUIT_MODEL, 5),
                  'Runic_Conduit_Curve': (CONDUIT_VARIANTS['Runic_Conduit_Curve'], 12),
                  'Runic_Conduit_T': (CONDUIT_VARIANTS['Runic_Conduit_T'], 14),
                  'Runic_Conduit_Cross': (CONDUIT_VARIANTS['Runic_Conduit_Cross'], 15),
                  'Runic_Conduit_Up': (CONDUIT_VARIANTS['Runic_Conduit_Up'], 30),
                  'Runic_Conduit_UpDown': (CONDUIT_VARIANTS['Runic_Conduit_UpDown'], 62),
                  'Runic_Conduit_All': (CONDUIT_VARIANTS['Runic_Conduit_All'], 63),
                  'Runic_Conduit_Corner3': (CONDUIT_VARIANTS['Runic_Conduit_Corner3'], 28)}
        for name, (path, expected) in shapes.items():
            with self.subTest(shape=name):
                model, _, _ = export_model(json.loads(path.read_text(encoding='utf-8')), turn_to_player=False)
                points = []
                def visit(node, parents):
                    chain = parents+[node]; shape = node['shape']
                    if shape['type'] == 'box' and shape['shadingMode'] == 'fullbright':
                        for signs in itertools.product([-.5, .5], repeat=3):
                            p = [shape['offset'][k]+s*shape['settings']['size'][k]*shape['stretch'][k]
                                 for k, s in zip('xyz', signs)]
                            for parent in reversed(chain):
                                p = rotate_quaternion(p, parent['orientation'])
                                p = [v+parent['position'][k] for v, k in zip(p, 'xyz')]
                            points.append(p)
                    for child in node.get('children', []): visit(child, chain)
                for node in model['nodes']: visit(node, [])
                self.assertTrue(points)
                mask = 0
                for x, y, z in points:
                    if z <= -16+1e-6: mask |= 1
                    if x >= 16-1e-6: mask |= 2
                    if z >= 16-1e-6: mask |= 4
                    if x <= -16+1e-6: mask |= 8
                    if y >= 32-1e-6: mask |= 16
                    if y <= 1e-6: mask |= 32
                self.assertEqual(mask, expected)


if __name__ == '__main__':
    unittest.main(verbosity=2)
