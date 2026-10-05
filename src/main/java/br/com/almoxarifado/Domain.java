package br.com.almoxarifado;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Entidades imutáveis: os saldos só mudam pelo serviço de estoque. */
public final class Domain {
    private Domain() {}
    public enum Role { OPERADOR, ADMINISTRADOR }
    public enum Type { ENTRADA, SAIDA, ESTORNO }
    public record User(long id, String name, String email, String password, Role role, boolean active) {}
    public record Product(long id, String name, String unit, long balance, Instant created, boolean active) {}
    public record Movement(long id, long productId, String productName, Type type, long quantity,
                           long before, long after, Instant timestamp, long userId, String userName,
                           long reversedId) {}
    public static final class State {
        public long nextId = 1;
        public final Map<Long, User> users = new LinkedHashMap<>();
        public final Map<Long, Product> products = new LinkedHashMap<>();
        public final Map<Long, Movement> movements = new LinkedHashMap<>();
        public State copy() {
            State copy = new State(); copy.nextId = nextId;
            copy.users.putAll(users); copy.products.putAll(products); copy.movements.putAll(movements);
            return copy;
        }
    }
    public static final class BusinessException extends RuntimeException {
        public final int status;
        public BusinessException(int status, String message) { super(message); this.status = status; }
    }
}
