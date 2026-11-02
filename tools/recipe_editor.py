"""Local recipe planner backed by the installed game's inventory icons.

Run: python tools/recipe_editor.py
Only binds to 127.0.0.1. Recipe drafts do not modify game assets.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import shutil
import threading
from urllib.parse import unquote, urlparse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / 'recipe-editor'
RECIPES = ROOT / 'config' / 'crafting_recipes.json'
PRIMARY = ['AdvancedStorage_Arcane_Core', 'AdvancedStorage_Seal_Archive',
           'AdvancedStorage_Runic_Catalog', 'AdvancedStorage_Runic_Conduit'] + [
               f'AdvancedStorage_Storage_Seal_Tier_{tier}' for tier in range(1, 6)]
GROUPS = {'Ingredient': 'Ingredientes', 'Ore': 'Minérios', 'Rock': 'Pedras',
          'Wood': 'Madeiras', 'Plant': 'Plantas', 'Furniture': 'Mobília',
          'Weapon': 'Armas', 'Armor': 'Armaduras', 'Tool': 'Ferramentas',
          'Food': 'Alimentos', 'Potion': 'Poções', 'Soil': 'Solo',
          'Deco': 'Decoração', 'Bench': 'Bancadas'}


def read_language(text):
    result = {}
    for line in text.lstrip('\ufeff').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    return result


def locate_assets(argument=None):
    candidates = [Path(argument)] if argument else [
        Path(r'D:\Games\Hytale\install\release\package\game\latest\Assets.zip'),
        Path(os.environ.get('APPDATA', '')) / 'Hytale/install/release/package/game/latest/Assets.zip']
    for path in candidates:
        if path.is_file():
            return path.resolve()
    raise FileNotFoundError('Assets.zip não encontrado. Use --assets "caminho/Assets.zip".')


class Catalog:
    def __init__(self, assets):
        self.zip = zipfile.ZipFile(assets)
        self.lock = threading.RLock()
        self.names = set(self.zip.namelist())
        self.raw = {}
        self.icon_files = {}
        self.languages = {}
        self.warnings = []
        for language in ['en-US', 'pt-BR']:
            path = f'Server/Languages/{language}/server.lang'
            self.languages[language] = read_language(self.zip.read(path).decode('utf-8-sig')) if path in self.names else {}
        for name in sorted(self.names):
            if name.startswith('Server/Item/Items/') and name.endswith('.json'):
                try:
                    folder = name.split('/')[3] if len(name.split('/')) > 4 else Path(name).stem.split('_')[0]
                    self.raw[Path(name).stem] = (json.loads(self.zip.read(name)), folder)
                except (ValueError, UnicodeDecodeError):
                    self.warnings.append(name)
        for language in self.languages:
            file = ROOT / 'pack/Server/Languages' / language / 'server.lang'
            if file.exists():
                self.languages[language].update(read_language(file.read_text(encoding='utf-8-sig')))
        for file in (ROOT/'pack/Server/Item/Items').rglob('*.json'):
            self.raw[file.stem] = (json.loads(file.read_text(encoding='utf-8-sig')), 'Mod')
        self.items = []
        self.lookup = {}
        benches = set()
        for item_id, (_, folder) in self.raw.items():
            data = self.resolve(item_id)
            icon = data.get('Icon')
            if not isinstance(icon, str):
                continue
            local_icon = ROOT/'pack/Common'/icon
            archive_icon = 'Common/'+icon
            if local_icon.is_file():
                self.icon_files[item_id] = local_icon
            elif archive_icon in self.names:
                self.icon_files[item_id] = archive_icon
            else:
                continue
            key = data.get('TranslationProperties', {}).get('Name', '')
            if key.startswith('server.'):
                key = key[7:]
            fallback = item_id.replace('_', ' ')
            name = self.languages['en-US'].get(key, fallback)
            pt = self.languages['pt-BR'].get(key, name)
            item = {'id': item_id, 'name': name, 'namePt': pt,
                    'group': GROUPS.get(folder, folder), 'mod': folder == 'Mod',
                    'quality': data.get('Quality', 'Common'),
                    'icon': '/api/icons/'+item_id,
                    'categories': data.get('Categories', [])}
            self.items.append(item)
            self.lookup[item_id] = item
            for bench in (data.get('Recipe') or {}).get('BenchRequirement', []):
                if isinstance(bench.get('Id'), str) and bench['Id'] != 'TODO':
                    benches.add(bench['Id'])
        self.items.sort(key=lambda item: (not item['mod'], item['name'].startswith('[TMP]') or item['group'].startswith('_'), item['name'].casefold()))
        self.targets = [self.lookup[k] for k in PRIMARY if k in self.lookup]
        self.benches = sorted(benches)

    @lru_cache(maxsize=None)
    def resolve(self, item_id, trail=()):
        if item_id in trail or item_id not in self.raw:
            return {}
        raw = self.raw[item_id][0]
        parent = raw.get('Parent')
        result = dict(self.resolve(parent, trail+(item_id,))) if isinstance(parent, str) else {}
        # Only shallow fields used for this picker are inherited.
        result.update(raw)
        return result

    @lru_cache(maxsize=1024)
    def icon(self, item_id):
        path = self.icon_files[item_id]
        if isinstance(path, Path):
            return path.read_bytes()
        with self.lock:
            return self.zip.read(path)


class RecipeStore:
    def __init__(self, catalog, path=RECIPES):
        self.catalog = catalog
        self.path = path
        self.lock = threading.RLock()

    def read(self):
        with self.lock:
            if not self.path.exists():
                return {'version': 1, 'revision': 0, 'recipes': {}}
            return json.loads(self.path.read_text(encoding='utf-8'))

    def validate(self, recipes):
        if not isinstance(recipes, dict) or len(recipes) > len(self.catalog.targets):
            raise ValueError('Lista de receitas inválida.')
        target_ids = {item['id'] for item in self.catalog.targets}
        result = {}
        for target, recipe in recipes.items():
            if target not in target_ids or not isinstance(recipe, dict):
                raise ValueError('Item de saída desconhecido.')
            ingredients = recipe.get('ingredients', [])
            if not isinstance(ingredients, list) or len(ingredients) > 64:
                raise ValueError('Limite de 64 ingredientes por receita.')
            seen, clean = set(), []
            for ingredient in ingredients:
                if not isinstance(ingredient, dict):
                    raise ValueError('Ingrediente inválido.')
                item_id, quantity = ingredient.get('itemId'), ingredient.get('quantity')
                if item_id not in self.catalog.lookup or item_id in seen:
                    raise ValueError('Ingrediente desconhecido ou repetido.')
                if item_id == target:
                    raise ValueError('Uma receita não pode usar seu próprio resultado.')
                if type(quantity) is not int or not 1 <= quantity <= 9999:
                    raise ValueError('Quantidades devem estar entre 1 e 9999.')
                seen.add(item_id)
                clean.append({'itemId': item_id, 'quantity': quantity})
            quantity = recipe.get('outputQuantity', 1)
            time = recipe.get('timeSeconds', 1)
            if type(quantity) is not int or not 1 <= quantity <= 9999:
                raise ValueError('Quantidade de saída inválida.')
            if type(time) not in [int, float] or not 0 <= time <= 86400:
                raise ValueError('Tempo deve estar entre 0 e 86400 segundos.')
            bench = recipe.get('bench', '')
            if not isinstance(bench, str) or len(bench) > 120:
                raise ValueError('Bancada inválida.')
            notes = recipe.get('notes', '')
            if not isinstance(notes, str) or len(notes) > 2000:
                raise ValueError('Observações devem ter até 2000 caracteres.')
            status = recipe.get('status', 'draft')
            if status not in ['draft', 'ready']:
                raise ValueError('Estado inválido.')
            if status == 'ready' and (not clean or not bench):
                raise ValueError('Escolha ingredientes e bancada antes de marcar como definida.')
            result[target] = {'ingredients': clean, 'outputQuantity': quantity,
                              'timeSeconds': time, 'bench': bench, 'notes': notes, 'status': status}
        return result

    def save(self, recipes, revision):
        with self.lock:
            current = self.read()
            if type(revision) is not int or revision != current['revision']:
                return None
            result = {'version': 1, 'revision': revision+1, 'recipes': self.validate(recipes)}
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix('.json.tmp')
            if self.path.exists():
                shutil.copy2(self.path, self.path.with_suffix('.json.bak'))
            temporary.write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
            os.replace(temporary, self.path)
            return result


def create_handler(catalog, store, port):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            if args and str(args[0]).startswith('POST'):
                super().log_message(format, *args)

        def trusted(self):
            return self.headers.get('Host') in [f'127.0.0.1:{port}', f'localhost:{port}']

        def send(self, status, data, content_type='application/json; charset=utf-8', cache=False):
            payload = json.dumps(data, ensure_ascii=False).encode() if isinstance(data, (dict, list)) else data
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Cache-Control', 'private, max-age=3600' if cache else 'no-store')
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            if not self.trusted():
                return self.send(403, {'error': 'Este editor aceita apenas localhost.'})
            path = unquote(urlparse(self.path).path)
            if path == '/api/catalog':
                return self.send(200, {'items': catalog.items, 'targets': catalog.targets,
                                       'benches': catalog.benches, 'warnings': len(catalog.warnings)})
            if path == '/api/recipes':
                return self.send(200, store.read())
            if path.startswith('/api/icons/'):
                item = path[len('/api/icons/'):]
                if item not in catalog.icon_files:
                    return self.send(404, {'error': 'Miniatura não encontrada.'})
                return self.send(200, catalog.icon(item), 'image/png', cache=True)
            allowed = {'/': 'index.html', '/app.js': 'app.js', '/styles.css': 'styles.css'}
            if path not in allowed:
                return self.send(404, {'error': 'Página não encontrada.'})
            file = WEB/allowed[path]
            return self.send(200, file.read_bytes(), mimetypes.guess_type(file)[0]+'; charset=utf-8')

        def do_POST(self):
            if not self.trusted() or self.headers.get('Origin') not in [None, f'http://127.0.0.1:{port}', f'http://localhost:{port}']:
                return self.send(403, {'error': 'Origem não permitida.'})
            if urlparse(self.path).path != '/api/recipes':
                return self.send(404, {'error': 'Endpoint não encontrado.'})
            try:
                length = int(self.headers.get('Content-Length', 0))
                if not 0 < length <= 300_000:
                    raise ValueError('Arquivo de receitas grande demais ou vazio.')
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError('Documento inválido.')
                saved = store.save(data.get('recipes'), data.get('revision'))
                if saved is None:
                    return self.send(409, {'error': 'As receitas mudaram em outra janela. Exporte suas alterações antes de recarregar.'})
                return self.send(200, saved)
            except (ValueError, TypeError, KeyError) as error:
                return self.send(400, {'error': str(error)})
            except OSError:
                return self.send(500, {'error': 'Não foi possível gravar as receitas no projeto.'})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    catalog = Catalog(locate_assets(args.assets))
    store = RecipeStore(catalog)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), create_handler(catalog, store, args.port))
    print(f'Editor de crafts: http://127.0.0.1:{args.port}', flush=True)
    print(f'{len(catalog.items)} itens com miniaturas. Receitas: {RECIPES}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        catalog.zip.close()


if __name__ == '__main__':
    main()
