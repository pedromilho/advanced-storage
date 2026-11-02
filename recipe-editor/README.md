# Editor local de crafts

Na raiz do projeto, execute:

```powershell
python tools/recipe_editor.py
```

Abra **http://127.0.0.1:8765**. O servidor escuta apenas neste computador.
Para outra instalação, use `--assets "caminho/Assets.zip"`. Para outra porta,
use `--port 8766`.

- Escolha um dos nove resultados do mod na lateral.
- Busque ingredientes em inglês, português ou pelo ID do jogo.
- Clique na miniatura para adicionar uma unidade. Cliques adicionais aumentam a quantidade.
- Ajuste quantidades, bancada, tempo e quantidade produzida.
- Marque como definida quando terminar. Alterações voltam a receita a rascunho.
- As escolhas são salvas em `config/crafting_recipes.json`, com backup da gravação
  anterior em `.json.bak`. O navegador mantém um rascunho para recuperar falhas de conexão.
- Exporte/importe JSON para transportar ou compartilhar as escolhas.

Não há receitas preenchidas automaticamente. O editor planeja receitas; não
altera os JSONs do pacote nem instala custos no jogo. As miniaturas e os nomes
vêm do `Assets.zip` local e da pasta `pack`, incluindo os cinco Storage Seals.
Variantes técnicas dos condutores podem ser ingredientes, mas não são resultados
separados: o jogo já escolhe a conexão automaticamente.

Os assets do jogo são lidos diretamente da instalação, sem extração ou publicação.
Não é necessário instalar pacotes Python ou JavaScript.
