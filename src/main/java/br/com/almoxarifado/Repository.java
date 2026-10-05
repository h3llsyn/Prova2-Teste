package br.com.almoxarifado;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.function.Function;
import static br.com.almoxarifado.Domain.*;

/** Transação única: validação, saldo e histórico são gravados no mesmo snapshot. */
public final class Repository {
    private final Path file;
    private State state;
    public Repository(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        Files.createDirectories(this.file.getParent());
        state = Files.exists(this.file) ? load() : new State();
    }
    public synchronized State snapshot() { return state.copy(); }
    public synchronized <T> T transaction(Function<State,T> action) {
        State candidate = state.copy();
        T result = action.apply(candidate);
        try { save(candidate); } catch (IOException e) {
            throw new BusinessException(500, "Não foi possível salvar os dados. Operação desfeita.");
        }
        state = candidate;
        return result;
    }
    private void save(State s) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream))) {
            out.writeUTF("ALMOX1"); out.writeLong(s.nextId);
            out.writeInt(s.users.size());
            for (User u : s.users.values()) {
                out.writeLong(u.id()); out.writeUTF(u.name()); out.writeUTF(u.email());
                out.writeUTF(u.password()); out.writeUTF(u.role().name()); out.writeBoolean(u.active());
            }
            out.writeInt(s.products.size());
            for (Product p : s.products.values()) {
                out.writeLong(p.id()); out.writeUTF(p.name()); out.writeUTF(p.unit());
                out.writeLong(p.balance()); out.writeUTF(p.created().toString()); out.writeBoolean(p.active());
            }
            out.writeInt(s.movements.size());
            for (Movement m : s.movements.values()) {
                out.writeLong(m.id()); out.writeLong(m.productId()); out.writeUTF(m.productName());
                out.writeUTF(m.type().name()); out.writeLong(m.quantity()); out.writeLong(m.before());
                out.writeLong(m.after()); out.writeUTF(m.timestamp().toString()); out.writeLong(m.userId());
                out.writeUTF(m.userName()); out.writeLong(m.reversedId());
            }
            out.flush(); stream.getFD().sync();
        }
        // Falhar se o sistema de arquivos não suportar substituição atômica.
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private State load() throws IOException {
        State s = new State();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!in.readUTF().equals("ALMOX1")) throw new IOException("Arquivo de dados inválido.");
            s.nextId = in.readLong();
            for (int n = in.readInt(); n > 0; n--) {
                User u = new User(in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), Role.valueOf(in.readUTF()), in.readBoolean());
                s.users.put(u.id(), u);
            }
            for (int n = in.readInt(); n > 0; n--) {
                Product p = new Product(in.readLong(), in.readUTF(), in.readUTF(), in.readLong(), Instant.parse(in.readUTF()), in.readBoolean());
                s.products.put(p.id(), p);
            }
            for (int n = in.readInt(); n > 0; n--) {
                Movement m = new Movement(in.readLong(), in.readLong(), in.readUTF(), Type.valueOf(in.readUTF()), in.readLong(),
                    in.readLong(), in.readLong(), Instant.parse(in.readUTF()), in.readLong(), in.readUTF(), in.readLong());
                s.movements.put(m.id(), m);
            }
        }
        return s;
    }
}
