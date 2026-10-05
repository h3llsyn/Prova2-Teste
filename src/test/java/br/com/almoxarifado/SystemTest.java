package br.com.almoxarifado;

import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static br.com.almoxarifado.Domain.*;

/** Testes de aceitação independentes de bibliotecas externas. */
public final class SystemTest {
    private static final List<String> results = new ArrayList<>();
    private record Fixture(Service service, long admin, long operator, long product, Path file) {}
    private static Fixture fixture() throws Exception {
        Path file = Files.createTempDirectory("almox-test-").resolve("dados.dat");
        Service service = new Service(new Repository(file));
        User a = service.bootstrap("admin@teste.local","Admin123!");
        User o = service.register("Operador","operador@teste.local","Operador123!");
        Product p = service.createProduct(o.id(),"Detergente","un");
        service.move(o.id(),p.id(),Type.ENTRADA,10);
        return new Fixture(service,a.id(),o.id(),p.id(),file);
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static BusinessException fail(int status,Runnable runnable) {
        try { runnable.run(); } catch (BusinessException e) { check(e.status == status,"Status inesperado: " + e.status); return e; }
        throw new AssertionError("A operação deveria falhar.");
    }
    private static void evidence(int n,String title,String input,String output) throws Exception {
        String text = "Cenário " + n + " - " + title + "\nExecutado em: " + Instant.now() + "\nJava: " + System.getProperty("java.version") +
            "\nDados: " + input + "\nSaída obtida: " + output + "\nConclusão: APROVADO\n";
        Files.createDirectories(Path.of("docs/testes")); Files.writeString(Path.of("docs/testes/cenario" + n + ".txt"),text);
        results.add(text); System.out.println("APROVADO cenário " + n + " - " + title);
    }
    public static void main(String[] args) throws Exception {
        Fixture initial = fixture();
        int before = initial.service.repository.snapshot().movements.size();
        BusinessException insufficient = fail(409,() -> initial.service.move(initial.operator,initial.product,Type.SAIDA,11));
        check(insufficient.getMessage().equals("Saída não permitida: estoque insuficiente. Disponível: 10. Solicitado: 11."),"Mensagem divergente");
        check(initial.service.repository.snapshot().products.get(initial.product).balance() == 10,"Saldo alterado na rejeição");
        check(initial.service.repository.snapshot().movements.size() == before,"Saída rejeitada gravada");
        evidence(1,"Bloquear saída acima do saldo","Saldo 10; saída 11",insufficient.getMessage() + " Saldo final 10; histórico preservado.");
        Fixture f = fixture(); Movement partial = f.service.move(f.operator,f.product,Type.SAIDA,4);
        check(partial.after() == 6 && partial.before() == 10,"Saldo parcial errado");
        check(partial.userId() == f.operator && partial.timestamp() != null && partial.userName().equals("Operador"),"Auditoria ausente");
        evidence(2,"Permitir saída menor que o saldo","Saldo 10; saída 4","Saldo final 6; saída registrada com data, hora e operador.");
        f = fixture(); Movement exact = f.service.move(f.operator,f.product,Type.SAIDA,10);
        check(exact.after() == 0,"Saída igual ao saldo não zerou estoque");
        evidence(3,"Permitir saída igual ao saldo","Saldo 10; saída 10","Saldo final 0; movimentação registrada.");
        Fixture invalid = fixture();
        fail(400,() -> invalid.service.move(invalid.operator,invalid.product,Type.SAIDA,0));
        fail(400,() -> invalid.service.move(invalid.operator,invalid.product,Type.SAIDA,-1));
        check(invalid.service.repository.snapshot().products.get(invalid.product).balance() == 10,"Quantidade inválida alterou saldo");
        evidence(4,"Rejeitar quantidade zero ou negativa","Saldo 10; saídas 0 e -1","HTTP lógico 400 em ambas; saldo 10.");
        Fixture permissions = fixture();
        fail(403,() -> permissions.service.deleteProduct(permissions.operator,permissions.product));
        fail(403,() -> permissions.service.deleteUser(permissions.operator,permissions.admin));
        fail(403,() -> permissions.service.updateUser(permissions.operator,permissions.operator,"Operador","operador@teste.local","",Role.ADMINISTRADOR));
        evidence(5,"Controlar permissões de operador","Operador tenta excluir produto, excluir usuário e promover a própria conta","Operações bloqueadas com 403.");
        Fixture concurrent = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> withdraw = () -> { start.await(); try { concurrent.service.move(concurrent.operator,concurrent.product,Type.SAIDA,7); return true; } catch (BusinessException e) { check(e.status == 409,"Erro concorrente inesperado"); return false; } };
        Future<Boolean> first = pool.submit(withdraw); Future<Boolean> second = pool.submit(withdraw); start.countDown();
        int accepted = (first.get() ? 1 : 0) + (second.get() ? 1 : 0); pool.shutdown();
        check(accepted == 1 && concurrent.service.repository.snapshot().products.get(concurrent.product).balance() == 3,"Retirada simultânea excedeu estoque");
        evidence(6,"Serializar retiradas simultâneas","Saldo 10; duas saídas concorrentes de 7","Uma saída aceita, outra bloqueada; saldo final 3.");
        Repository reopened = new Repository(concurrent.file);
        check(reopened.snapshot().products.get(concurrent.product).balance() == 3,"Saldo não persistiu");
        check(reopened.snapshot().movements.size() == 2,"Histórico não persistiu");
        evidence(7,"Persistir saldo e histórico","Reabrir arquivo após cenário 6","Saldo 3 e duas movimentações recuperados (entrada e saída).");
        Fixture reversal = fixture(); Movement output = reversal.service.move(reversal.operator,reversal.product,Type.SAIDA,4);
        Movement reverse = reversal.service.reverse(reversal.admin,output.id());
        check(reverse.after() == 10 && reverse.reversedId() == output.id(),"Estorno errado");
        fail(409,() -> reversal.service.reverse(reversal.admin,output.id()));
        check(reversal.service.repository.snapshot().movements.size() == 3,"Estorno apagou histórico");
        evidence(8,"Estornar sem apagar auditoria","Entrada 10; saída 4; estorno da saída","Saldo 10; três registros preservados; segundo estorno bloqueado.");
        httpTest();
        Fixture auth = fixture();
        check(auth.service.login("ADMIN@TESTE.LOCAL","Admin123!").id() == auth.admin,"Login correto falhou");
        fail(401,() -> auth.service.login("admin@teste.local","senhaerrada"));
        fail(409,() -> auth.service.register("Duplicado","admin@teste.local","Senha123!"));
        fail(409,() -> auth.service.updateUser(auth.admin,auth.admin,"Admin","admin@teste.local","",Role.OPERADOR));
        User other = auth.service.createUser(auth.admin,"Outro","outro@teste.local","Senha123!",Role.OPERADOR);
        auth.service.deleteUser(auth.admin,other.id());
        fail(401,() -> auth.service.login("outro@teste.local","Senha123!"));
        check(!Json.encode(auth.service.repository.snapshot().users.values()).contains("210000:"),"Hash exposto na resposta");
        evidence(10,"Autenticar e proteger contas","Login válido e inválido, e-mail repetido, último admin, usuário excluído","Login válido aceito; tentativas indevidas bloqueadas; hashes ausentes do JSON.");
        Files.writeString(Path.of("docs/testes/resumo.txt"),String.join("\n",results));
        System.out.println("SUCESSO: " + results.size() + " cenários aprovados.");
    }
    private static void httpTest() throws Exception {
        Fixture f = fixture();
        try (Server server = new Server(f.service,0,Path.of("web"))) {
            server.start(); String base = "http://127.0.0.1:" + server.port(); HttpClient client = HttpClient.newHttpClient();
            var unauthorized = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(unauthorized.statusCode() == 401,"Rota pública indevida");
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("email=operador%40teste.local&password=Operador123%21")).build(),HttpResponse.BodyHandlers.ofString());
            check(login.statusCode() == 200,"Login HTTP falhou");
            String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            String csrf = login.body().split("\"csrf\":\"")[1].split("\"")[0];
            var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products/" + f.product)).header("Cookie",cookie).header("X-CSRF-Token",csrf).DELETE().build(),HttpResponse.BodyHandlers.ofString());
            check(denied.statusCode() == 403,"Operador excluiu por HTTP");
            var noCsrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/movements")).header("Cookie",cookie).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("productId=" + f.product + "&type=SAIDA&quantity=1")).build(),HttpResponse.BodyHandlers.ofString());
            check(noCsrf.statusCode() == 403,"CSRF não validado");
            var list = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products?name=Detergente&page=1&size=1&from=2000-01-01&to=2099-12-31")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(list.statusCode() == 200 && list.body().contains("\"total\":1") && list.body().contains("Detergente"),"Paginação/filtro falhou");
            var empty = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products?name=inexistente")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(empty.body().contains("\"total\":0"),"Filtro por nome não aplicado");
            var badDate = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products?from=data-invalida")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(badDate.statusCode() == 400,"Data inválida deveria retornar 400");
            var excludedDate = client.send(HttpRequest.newBuilder(URI.create(base + "/api/products?to=2000-01-01")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(excludedDate.body().contains("\"total\":0"),"Filtro por data não aplicado");
            var excessive = client.send(HttpRequest.newBuilder(URI.create(base + "/api/movements")).header("Cookie",cookie).header("X-CSRF-Token",csrf).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("productId=" + f.product + "&type=SAIDA&quantity=11")).build(),HttpResponse.BodyHandlers.ofString());
            check(excessive.statusCode() == 409 && excessive.body().contains("estoque insuficiente"),"Bloqueio não propagado por HTTP");
            var accepted = client.send(HttpRequest.newBuilder(URI.create(base + "/api/movements")).header("Cookie",cookie).header("X-CSRF-Token",csrf).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("productId=" + f.product + "&type=SAIDA&quantity=4")).build(),HttpResponse.BodyHandlers.ofString());
            check(accepted.statusCode() == 201 && accepted.body().contains("\"after\":6"),"Saída válida falhou por HTTP");
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(page.statusCode() == 200 && page.body().contains("Almoxarifado"),"Interface não servida");
            evidence(9,"Proteger e consultar rotas HTTP","Sem sessão, operador autenticado, sem CSRF, filtros e paginação","401 sem sessão; 403 em exclusão e CSRF; filtros com total 1 e 0; interface retorna 200.");
        }
    }
}
