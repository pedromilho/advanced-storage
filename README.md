# Advanced Storage — Hytale

Mod de armazenamento com tema de magia e biblioteca medieval. Esta é a beta **0.1.0**, para **Hytale 0.7.5**.

## Instalação

Baixe `advanced-storage-0.1.0-beta.jar` na [página de releases](https://github.com/pedromilho/advanced-storage/releases). Coloque somente esse arquivo na pasta `UserData/Mods` e habilite **AdvancedStorage** no mundo.

O JAR contém código, modelos, texturas, receitas e interfaces. Não instale os antigos `advanced-storage-0.1.0.zip` e `advanced-storage-runtime-0.1.0.jar` junto com ele. Faça a substituição com o mundo fechado.

## A rede

Monte uma rede conectando **Seal Archive → Runic Conduit → Arcane Core → Runic Conduit → Runic Catalog**.

- **Arcane Core:** ativa a rede e mostra o esquema dos blocos conectados.
- **Seal Archive:** recebe quatro Storage Seals em slots horizontais.
- **Runic Conduit:** conecta os blocos automaticamente nas seis direções.
- **Runic Catalog:** permite pesquisar, ordenar, depositar e retirar itens.
- **Blank Seal** e **Runic Book:** componentes de fabricação.

Os seals guardam seus itens ao serem removidos do Archive.

| Seal | Capacidade em itens individuais |
| --- | ---: |
| Tier 1 | 1.000 |
| Tier 2 | 4.000 |
| Tier 3 | 16.000 |
| Tier 4 | 64.000 |
| Tier 5 | 128.000 |

## Fabricação

As onze receitas ficam na **Arcane Workbench → Misc**. Ingredientes genéricos aceitam qualquer tronco, fragmento de cristal, tijolo ou baú pequeno do grupo correspondente. Os upgrades de seals exigem seals vazios.

Veja [receitas e quantidades](docs/crafting.md). A fonte das receitas é [advanced-storage-crafts.json](recipes/advanced-storage-crafts.json), com os dois componentes adicionais definidos em `tools/crafting.py`.

## Comando de ingredientes

`/advancedstorage <item>` entrega os ingredientes de uma execução da receita, desde que haja espaço para o kit inteiro. Requer Creative ou permissão administrativa.

```text
/advancedstorage seal_archive
/advancedstorage runic_conduit
/advancedstorage arcane_core
/advancedstorage runic_catalog
/advancedstorage blank_seal
/advancedstorage runic_book
/advancedstorage storage_seal_tier_1
```

Os tiers vão de `1` a `5`. IDs completos também são aceitos. Uma receita de Runic Conduit produz seis unidades.

## Interfaces

O Catalog integra inventário, hotbar, pesquisa e ordenação por nome ou quantidade. A lista se reorganiza após as retiradas. Suporta arraste, Shift + clique esquerdo, divisão com Shift + clique direito, distribuição com botão direito e junção por duplo clique esquerdo. Um clique esquerdo na origem cancela a seleção.

Catalog e Archive usam um tooltip próprio com o estilo do jogo para evitar a persistência do tooltip após fechar a tela. Isso se aplica somente às interfaces do mod.

## Compilar e verificar

Requer Windows, Python 3.10+, Pillow, JDK 25 e uma instalação local do Hytale 0.7.5. O servidor e o arquivo `Assets.zip` do Hytale não são distribuídos neste repositório. Os scripts usam por padrão `D:/Games/Hytale/install/release/package/game/latest`.

```powershell
python -m pip install Pillow
./tools/build_runtime.ps1
python tools/check_pack.py
python tools/test_package_mod.py
./tools/run_connection_checks.ps1
```

O arquivo instalável é `dist/advanced-storage-0.1.0.jar`. O ZIP em `dist` é apenas um intermediário de build.

Para instalar a build local e migrar uma instalação antiga:

```powershell
./tools/build_runtime.ps1 -InstallTo "D:\Games\Hytale\UserData\Mods"
```

O instalador guarda os arquivos substituídos em `UserData/AdvancedStorageBackups`. Os testes de integração usam um servidor e um mundo isolados em `.runtime/connection-checks`.

## Editor de receitas

Execute `python tools/recipe_editor.py` e abra <http://127.0.0.1:8765>. O editor salva rascunhos em `config/crafting_recipes.json`; não modifica o jogo diretamente. Veja [a documentação do editor](recipe-editor/README.md).

## Licença

Veja [LICENSE](LICENSE). Hytale e seus assets originais pertencem aos respectivos titulares.
