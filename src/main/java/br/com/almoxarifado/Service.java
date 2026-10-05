package br.com.almoxarifado;

import java.time.Instant;
import java.util.Locale;
import static br.com.almoxarifado.Domain.*;

public final class Service {
    public final Repository repository;
    public Service(Repository repository) { this.repository = repository; }
    public User bootstrap(String email, String password) {
        return repository.transaction(s -> {
            if (!s.users.isEmpty()) throw new BusinessException(409, "Administrador inicial já configurado.");
            return insertUser(s, "Administrador", email, password, Role.ADMINISTRADOR);
        });
    }
    public User register(String name, String email, String password) {
        return repository.transaction(s -> insertUser(s, name, email, password, Role.OPERADOR));
    }
    public User createUser(long actor, String name, String email, String password, Role role) {
        return repository.transaction(s -> { admin(s, actor); return insertUser(s, name, email, password, role); });
    }
    private User insertUser(State s, String name, String email, String password, Role role) {
        email = email(email);
        String normalized = email;
        if (s.users.values().stream().anyMatch(u -> u.email().equals(normalized))) throw new BusinessException(409, "E-mail já cadastrado.");
        User u = new User(s.nextId++, text(name, "Nome", 100), email, Security.hash(password), role, true);
        s.users.put(u.id(), u); return u;
    }
    public User login(String email, String password) {
        User u = repository.snapshot().users.values().stream().filter(x -> x.active() && x.email().equals(email(email))).findFirst().orElse(null);
        if (u == null || !Security.verify(password, u.password())) throw new BusinessException(401, "E-mail ou senha incorretos.");
        return u;
    }
    public User updateUser(long actor, long id, String name, String email, String password, Role role) {
        return repository.transaction(s -> {
            User a = user(s, actor); User old = user(s, id);
            if (a.role() != Role.ADMINISTRADOR && (actor != id || role != old.role())) throw new BusinessException(403, "Você só pode atualizar seus dados, sem alterar seu perfil.");
            if (old.role() == Role.ADMINISTRADOR && role != Role.ADMINISTRADOR) protectLastAdmin(s, id);
            String mail = email(email);
            if (s.users.values().stream().anyMatch(u -> u.id() != id && u.email().equals(mail))) throw new BusinessException(409, "E-mail já cadastrado.");
            User updated = new User(id, text(name, "Nome", 100), mail, password == null || password.isBlank() ? old.password() : Security.hash(password), role, true);
            s.users.put(id, updated); return updated;
        });
    }
    public void deleteUser(long actor, long id) {
        repository.transaction(s -> {
            admin(s, actor); User u = user(s, id);
            if (actor == id) throw new BusinessException(409, "Não é permitido excluir sua própria conta.");
            if (u.role() == Role.ADMINISTRADOR) protectLastAdmin(s, id);
            s.users.put(id, new User(id, u.name(), u.email(), u.password(), u.role(), false)); return null;
        });
    }
    private void protectLastAdmin(State s, long id) {
        if (s.users.values().stream().noneMatch(u -> u.id() != id && u.active() && u.role() == Role.ADMINISTRADOR))
            throw new BusinessException(409, "O sistema deve manter pelo menos um administrador ativo.");
    }
    public Product createProduct(long actor, String name, String unit) {
        return repository.transaction(s -> {
            user(s, actor); Product p = new Product(s.nextId++, text(name, "Nome", 100), text(unit, "Unidade", 20), 0, Instant.now(), true);
            s.products.put(p.id(), p); return p;
        });
    }
    public Product updateProduct(long actor, long id, String name, String unit) {
        return repository.transaction(s -> {
            user(s, actor); Product p = product(s, id);
            Product updated = new Product(id, text(name, "Nome", 100), text(unit, "Unidade", 20), p.balance(), p.created(), true);
            s.products.put(id, updated); return updated;
        });
    }
    public void deleteProduct(long actor, long id) {
        repository.transaction(s -> {
            admin(s, actor); Product p = product(s, id);
            if (p.balance() != 0) throw new BusinessException(409, "Esvazie o estoque antes de excluir o produto.");
            s.products.put(id, new Product(id, p.name(), p.unit(), 0, p.created(), false)); return null;
        });
    }
    public Movement move(long actor, long productId, Type type, long quantity) {
        return repository.transaction(s -> {
            User u = user(s, actor); Product p = product(s, productId);
            if (type == Type.ESTORNO) throw new BusinessException(400, "Utilize a operação de estorno.");
            if (quantity <= 0) throw new BusinessException(400, "A quantidade deve ser maior que zero.");
            if (type == Type.SAIDA && quantity > p.balance()) throw new BusinessException(409,
                "Saída não permitida: estoque insuficiente. Disponível: " + p.balance() + ". Solicitado: " + quantity + ".");
            return apply(s, u, p, type, quantity, type == Type.ENTRADA ? quantity : -quantity, 0);
        });
    }
    public Movement reverse(long actor, long id) {
        return repository.transaction(s -> {
            User u = admin(s, actor); Movement m = s.movements.get(id);
            if (m == null) throw new BusinessException(404, "Movimentação não encontrada.");
            if (m.type() == Type.ESTORNO || s.movements.values().stream().anyMatch(x -> x.reversedId() == id)) throw new BusinessException(409, "Movimentação já estornada ou não estornável.");
            Product p = product(s, m.productId());
            long delta = m.type() == Type.ENTRADA ? -m.quantity() : m.quantity();
            if (delta < 0 && p.balance() < m.quantity()) throw new BusinessException(409, "Estorno não permitido: estoque insuficiente.");
            return apply(s, u, p, Type.ESTORNO, m.quantity(), delta, id);
        });
    }
    private Movement apply(State s, User u, Product p, Type type, long quantity, long delta, long reversedId) {
        long after;
        try { after = Math.addExact(p.balance(), delta); } catch (ArithmeticException e) { throw new BusinessException(400, "Quantidade acima do limite suportado."); }
        Movement m = new Movement(s.nextId++, p.id(), p.name(), type, quantity, p.balance(), after, Instant.now(), u.id(), u.name(), reversedId);
        s.products.put(p.id(), new Product(p.id(), p.name(), p.unit(), after, p.created(), true)); s.movements.put(m.id(), m); return m;
    }
    public static User user(State s, long id) {
        User u = s.users.get(id); if (u == null || !u.active()) throw new BusinessException(401, "Sessão inválida. Entre novamente."); return u;
    }
    public static User admin(State s, long id) {
        User u = user(s, id); if (u.role() != Role.ADMINISTRADOR) throw new BusinessException(403, "Ação exclusiva do administrador."); return u;
    }
    public static Product product(State s, long id) {
        Product p = s.products.get(id); if (p == null || !p.active()) throw new BusinessException(404, "Produto não encontrado."); return p;
    }
    private static String email(String email) {
        String value = text(email, "E-mail", 200).toLowerCase(Locale.ROOT);
        if (!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new BusinessException(400, "E-mail inválido."); return value;
    }
    private static String text(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.trim().length() > limit) throw new BusinessException(400, field + " inválido."); return value.trim();
    }
}
