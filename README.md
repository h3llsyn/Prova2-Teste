# Sistema de almoxarifado em Java 21

Implementação do desafio do caderno 2: autenticação, perfis, produtos, paginação, filtros, movimentações com responsável e controle de saldo. Interface web em português. Backend Java 21, sem dependências externas para compilar, executar ou testar pelos scripts.

## Executar no Windows

Instale o **JDK 21**, configure `JAVA_HOME` e inclua `%JAVA_HOME%\bin` no `PATH`. Confirme que `java -version` e `javac -version` mostram 21.

Na raiz do projeto, use no PowerShell:

```powershell
.\run.bat
```

Abra **http://localhost:8080**. No primeiro início, o terminal mostra o e-mail `admin@almoxarifado.local` e uma senha aleatória para o administrador. A senha aparece somente na criação inicial. Para escolher a senha antes do primeiro início:

```powershell
$env:ADMIN_EMAIL = "seuemail@exemplo.com"
$env:ADMIN_PASSWORD = "EscolhaUmaSenhaForte!"
.\run.bat
```

Os usuários criados pela tela de cadastro são sempre operadores. O administrador pode criar outras contas e escolher o perfil na tela **Usuários**. A senha deve ter de 8 a 128 caracteres.

Para executar os testes:

```powershell
.\test.bat
```

## Executar no Linux ou macOS

Com JDK 21 no PATH:

```bash
bash run.sh
# Em outro terminal, para executar os testes:
bash test.sh
```

Os scripts compilam usando `javac --release 21`. Para importar no IntelliJ ou Eclipse, abra o diretório como projeto Maven e selecione o JDK 21. O Maven é opcional; `mvn test` também executa os cenários (requer baixar os plugins na primeira execução). O caminho mais simples e independente de internet é usar os scripts.

## Fluxo de uso

1. Entre como administrador ou cadastre um operador.
2. Cadastre um produto e informe sua unidade. O saldo inicial é zero.
3. Registre uma **entrada** para adicionar unidades ao saldo.
4. Registre uma **saída**. Quantidade maior que o disponível é bloqueada com a mensagem do enunciado. Quantidade menor ou igual é aceita.
5. Consulte o histórico com data, hora, produto, responsável e saldos anterior e posterior.
6. Busque produtos por nome e intervalo de datas de cadastro. As datas são interpretadas em `America/Sao_Paulo`; o histórico é gravado em UTC e exibido no fuso do navegador.

As quantidades são inteiras positivas. Use uma unidade apropriada para o material (por exemplo, mililitro para controlar frações de litro).

## Permissões

| Ação | Operador | Administrador |
| --- | --- | --- |
| Consultar, cadastrar e editar produtos | Sim | Sim |
| Registrar entrada e saída | Sim | Sim |
| Consultar histórico | Sim | Sim |
| Atualizar os próprios dados | Sim, sem mudar perfil | Sim |
| Criar conta pelo cadastro público | Sempre operador | Sempre operador |
| Listar e gerenciar outras contas e perfis | Não | Sim |
| Excluir produto sem saldo | Não | Sim |
| Excluir outra conta | Não | Sim |
| Cancelar movimentação por estorno | Não | Sim |

Excluir usuário ou produto faz uma exclusão lógica para preservar referências no histórico. Produto só pode ser excluído com saldo zero. O último administrador ativo não pode perder o perfil. Não é permitido excluir a própria conta.

As movimentações são imutáveis. A ação **Estornar** equivale ao cancelamento da operação e gera um novo registro vinculado ao original. O estorno de entrada não pode tornar o saldo negativo. Um registro só pode ser estornado uma vez; um estorno não pode ser estornado. Correções de quantidade são feitas com estorno e novo lançamento.

## Arquitetura e persistência

- `Domain`: entidades imutáveis e regras de tipagem.
- `Repository`: armazenamento e transações.
- `Service`: validações, permissões e regras de estoque.
- `Security`: hash PBKDF2 HMAC SHA256, sal aleatório e comparação de hash em tempo constante.
- `Server`: rotas HTTP, sessões, autorização e proteção CSRF.
- `Json`: serialização de respostas, sem expor hashes de senha.
- `web`: HTML, CSS e JavaScript da interface.
- `SystemTest`: testes de domínio, persistência, concorrência e HTTP.

Os dados ficam em `data/almoxarifado.dat`, fora do versionamento. Cada operação usa uma cópia do estado, grava um snapshot com substituição atômica e só então publica o novo estado. Saldo e histórico são persistidos juntos. A sincronização impede duas saídas simultâneas de consumirem o mesmo saldo. Falhas de validação ou gravação não publicam a alteração. O arquivo temporário é sincronizado antes da substituição; a durabilidade diante de perda abrupta de energia depende também do sistema de arquivos.

Execute **uma única instância por arquivo de dados**. Este projeto usa armazenamento local e carrega o conjunto de dados em memória; destina-se ao exercício e a demonstrações, não a implantações com múltiplas instâncias. Para dados de produção, adapte o repositório para um banco com transações e bloqueio de concorrência.

O servidor escuta apenas em `127.0.0.1`. As sessões expiram em oito horas e são perdidas ao reiniciar. Cookies são HttpOnly e SameSite Strict; requisições autenticadas de escrita exigem token CSRF. O cadastro público não permite escolher administrador. Alterar uma conta revoga suas sessões. Para publicar na internet, configure HTTPS, cookies Secure, limitação de tentativas de login e um banco adequado.

## Rotas

Requisições com corpo usam `application/x-www-form-urlencoded`; respostas usam JSON. Operações de escrita autenticadas exigem o cookie retornado pelo login e o cabeçalho `X-CSRF-Token` (valor devolvido em `/api/login` ou `/api/me`).

| Método | Rota | Função |
| --- | --- | --- |
| POST | `/api/register` | Cadastro público de operador |
| POST | `/api/login` | Autenticação e criação da sessão |
| GET | `/api/me` | Usuário autenticado e token CSRF |
| POST | `/api/logout` | Encerrar sessão |
| GET | `/api/products?name=&from=&to=&page=1&size=10` | Listagem e filtros |
| POST | `/api/products` | Criar com `name` e `unit` |
| PUT | `/api/products/{id}` | Atualizar `name` e `unit` |
| DELETE | `/api/products/{id}` | Excluir produto sem saldo |
| GET | `/api/movements?page=1&size=10` | Histórico paginado |
| POST | `/api/movements` | Registrar `productId`, `type` ENTRADA ou SAIDA e `quantity` |
| DELETE | `/api/movements/{id}` | Estornar lançamento |
| GET | `/api/users?page=1&size=10` | Listar usuários ativos |
| POST | `/api/users` | Criar conta com `name`, `email`, `password`, `role` |
| PUT | `/api/users/{id}` | Atualizar conta; senha vazia mantém a atual |
| DELETE | `/api/users/{id}` | Excluir conta |

O máximo por página é 100. Produtos, histórico e usuários usam paginação na interface.

## Entregáveis da prova

- `docs/plano_testes.docx`: plano de testes.
- `docs/testes/cenario1.txt` a `cenario10.txt`: evidências produzidas pela execução real dos testes.
- `docs/testes/resumo.txt`: resumo da execução.
- `resultados.docx`: dados usados, resultados obtidos e conclusões.
- `.github/workflows/java.yml`: compilação e testes com JDK 21.

Ao executar os testes novamente, as evidências de texto são atualizadas; o documento de resultados representa a execução entregue. Cada teste usa um arquivo temporário isolado e não altera os dados reais da aplicação.

O repositório fornecido estava vazio, sem scripts ou modelo de dados. As entidades foram definidas a partir do enunciado. Caso exista outro repositório-base da prova, será necessário conciliar as classes e atributos com os scripts dele.

## Versionamento na avaliação

Para seguir a regra da prova em uma nova avaliação, parta da principal, crie uma branch com seu nome e sobrenome e abra um pull request ao finalizar:

```bash
git switch main
git switch -c nome-sobrenome
git add .
git commit -m "Implementa controle de estoque e perfis de acesso"
git push -u origin nome-sobrenome
```

Abra o PR pelo GitHub, com base `main`. Ajuste `nome-sobrenome` para seu nome real.
