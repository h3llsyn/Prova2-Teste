package br.com.almoxarifado;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static br.com.almoxarifado.Domain.*;

public final class Server implements AutoCloseable {
    private record Session(long userId, String csrf, Instant expires) {}
    private final Map<String,Session> sessions = new ConcurrentHashMap<>();
    private final Service service;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Path web;
    public Server(Service service, int port, Path web) throws IOException {
        this.service = service; this.web = web;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor); server.createContext("/", this::handle);
    }
    public int port() { return server.getAddress().getPort(); }
    public void start() { server.start(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
    private void handle(HttpExchange x) throws IOException {
        try {
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("X-Frame-Options", "DENY");
            x.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            String path = x.getRequestURI().getPath(); String method = x.getRequestMethod();
            if (!path.startsWith("/api/")) { staticFile(x, path); return; }
            Map<String,String> params = form(x.getRequestURI().getRawQuery());
            if (!method.equals("GET")) {
                String origin = x.getRequestHeaders().getFirst("Origin");
                if (origin != null && !origin.equals("http://localhost:" + port()) && !origin.equals("http://127.0.0.1:" + port()))
                    throw new BusinessException(403, "Origem não permitida.");
                byte[] body = x.getRequestBody().readNBytes(16385);
                if (body.length > 16384) throw new BusinessException(413, "Requisição muito grande.");
                if (body.length > 0 && !Objects.toString(x.getRequestHeaders().getFirst("Content-Type"), "").startsWith("application/x-www-form-urlencoded"))
                    throw new BusinessException(415, "Utilize application/x-www-form-urlencoded.");
                params.putAll(form(new String(body, StandardCharsets.UTF_8)));
            }
            if (path.equals("/api/register") && method.equals("POST")) {
                send(x, 201, service.register(params.get("name"), params.get("email"), params.get("password"))); return;
            }
            if (path.equals("/api/login") && method.equals("POST")) {
                User u = service.login(params.get("email"), params.get("password"));
                sessions.entrySet().removeIf(e -> !e.getValue().expires().isAfter(Instant.now()));
                String old = cookie(x); if (old != null) sessions.remove(old);
                String token = Security.token(); Session session = new Session(u.id(), Security.token(), Instant.now().plusSeconds(28800));
                sessions.put(token, session);
                x.getResponseHeaders().set("Set-Cookie", "session=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800");
                send(x, 200, Map.of("user", u, "csrf", session.csrf())); return;
            }
            String token = cookie(x); Session session = token == null ? null : sessions.get(token);
            if (session == null || !session.expires().isAfter(Instant.now())) throw new BusinessException(401, "Entre para acessar o sistema.");
            User actor = Service.user(service.repository.snapshot(), session.userId());
            if (!method.equals("GET") && !session.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF-Token")))
                throw new BusinessException(403, "Token de segurança inválido. Atualize a página.");
            if (path.equals("/api/me") && method.equals("GET")) { send(x, 200, Map.of("user", actor, "csrf", session.csrf())); return; }
            if (path.equals("/api/logout") && method.equals("POST")) {
                sessions.remove(token); x.getResponseHeaders().set("Set-Cookie", "session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"); send(x,200,Map.of("message","Sessão encerrada.")); return;
            }
            if (path.equals("/api/products")) {
                if (method.equals("GET")) { send(x, 200, products(params)); return; }
                if (method.equals("POST")) { send(x,201,service.createProduct(actor.id(), params.get("name"), params.get("unit"))); return; }
            }
            if (path.matches("/api/products/\\d+")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/')+1));
                if (method.equals("PUT")) { send(x,200,service.updateProduct(actor.id(),id,params.get("name"),params.get("unit"))); return; }
                if (method.equals("DELETE")) { service.deleteProduct(actor.id(),id); send(x,200,Map.of("message","Produto excluído.")); return; }
            }
            if (path.equals("/api/movements")) {
                if (method.equals("GET")) {
                    var items = service.repository.snapshot().movements.values().stream().sorted(Comparator.comparingLong(Movement::id).reversed()).toList();
                    send(x,200,page(items,params)); return;
                }
                if (method.equals("POST")) {
                    send(x,201,service.move(actor.id(),number(params,"productId"),Type.valueOf(params.getOrDefault("type","")),number(params,"quantity"))); return;
                }
            }
            if (path.matches("/api/movements/\\d+") && method.equals("DELETE")) {
                send(x,200,service.reverse(actor.id(),Long.parseLong(path.substring(path.lastIndexOf('/')+1)))); return;
            }
            if (path.equals("/api/users")) {
                if (method.equals("GET")) {
                    State state = service.repository.snapshot(); Service.admin(state,actor.id());
                    send(x,200,page(state.users.values().stream().filter(User::active).toList(),params)); return;
                }
                if (method.equals("POST")) {
                    send(x,201,service.createUser(actor.id(),params.get("name"),params.get("email"),params.get("password"),Role.valueOf(params.getOrDefault("role","OPERADOR")))); return;
                }
            }
            if (path.matches("/api/users/\\d+")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/')+1));
                if (method.equals("PUT")) {
                    User old = Service.user(service.repository.snapshot(), id);
                    User updated = service.updateUser(actor.id(),id,params.get("name"),params.get("email"),params.get("password"),Role.valueOf(params.getOrDefault("role",old.role().name())));
                    // Revoga sessões após alteração de dados, senha ou permissão.
                    sessions.entrySet().removeIf(e -> e.getValue().userId() == id);
                    send(x,200,updated); return;
                }
                if (method.equals("DELETE")) {
                    service.deleteUser(actor.id(),id); sessions.entrySet().removeIf(e -> e.getValue().userId() == id);
                    send(x,200,Map.of("message","Usuário excluído.")); return;
                }
            }
            throw new BusinessException(404,"Rota não encontrada.");
        } catch (BusinessException e) { send(x,e.status,Map.of("message",e.getMessage())); }
        catch (IllegalArgumentException | DateTimeException e) { send(x,400,Map.of("message","Dados inválidos. Confira os valores informados.")); }
        catch (Exception e) { e.printStackTrace(); send(x,500,Map.of("message","Não foi possível concluir a operação.")); }
        finally { x.close(); }
    }
    private Object products(Map<String,String> params) {
        String name = params.getOrDefault("name", "").toLowerCase(Locale.ROOT);
        LocalDate from = params.getOrDefault("from", "").isBlank() ? null : LocalDate.parse(params.get("from"));
        LocalDate to = params.getOrDefault("to", "").isBlank() ? null : LocalDate.parse(params.get("to"));
        if (from != null && to != null && from.isAfter(to)) throw new BusinessException(400,"Data inicial maior que a final.");
        var items = service.repository.snapshot().products.values().stream().filter(Product::active)
            .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(name))
            .filter(p -> from == null || !p.created().atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate().isBefore(from))
            .filter(p -> to == null || !p.created().atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate().isAfter(to))
            .sorted(Comparator.comparingLong(Product::id).reversed()).toList();
        return page(items,params);
    }
    private static Object page(List<?> items, Map<String,String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1"));
        int size = Integer.parseInt(params.getOrDefault("size", "10"));
        if (page < 1 || size < 1 || size > 100) throw new BusinessException(400,"Paginação inválida. Tamanho máximo: 100.");
        long offset = (long)(page-1)*size; int start = (int)Math.min(offset, items.size());
        return Map.of("items", items.subList(start, Math.min(start+size,items.size())), "total",items.size(),"page",page,"size",size);
    }
    private static long number(Map<String,String> p,String name) { return Long.parseLong(p.getOrDefault(name,"")); }
    private static String cookie(HttpExchange x) {
        String cookies = x.getRequestHeaders().getFirst("Cookie"); if (cookies == null) return null;
        for (String c : cookies.split(";")) { String v = c.trim(); if (v.startsWith("session=")) return v.substring(8); } return null;
    }
    private static Map<String,String> form(String raw) {
        Map<String,String> p = new HashMap<>(); if (raw == null || raw.isBlank()) return p;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=",2);
            p.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length == 2 ? URLDecoder.decode(parts[1],StandardCharsets.UTF_8) : "");
        }
        return p;
    }
    private void staticFile(HttpExchange x, String path) throws IOException {
        if (!x.getRequestMethod().equals("GET")) throw new BusinessException(405,"Método não permitido.");
        String file = switch(path) { case "/", "/index.html" -> "index.html"; case "/app.js" -> "app.js"; case "/style.css" -> "style.css"; default -> null; };
        if (file == null) throw new BusinessException(404,"Página não encontrada.");
        byte[] content = Files.readAllBytes(web.resolve(file));
        x.getResponseHeaders().set("Content-Type", file.endsWith("js") ? "text/javascript; charset=utf-8" : file.endsWith("css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
        x.sendResponseHeaders(200,content.length); x.getResponseBody().write(content);
    }
    private static void send(HttpExchange x,int status,Object result) throws IOException {
        byte[] content = Json.encode(result).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.sendResponseHeaders(status,content.length); x.getResponseBody().write(content);
    }
}
