package br.com.almoxarifado;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        Repository repository = new Repository(Path.of("data", "almoxarifado.dat"));
        Service service = new Service(repository);
        if (repository.snapshot().users.isEmpty()) {
            String email = System.getenv().getOrDefault("ADMIN_EMAIL", "admin@almoxarifado.local");
            String password = System.getenv("ADMIN_PASSWORD");
            if (password == null || password.isBlank()) {
                password = Security.token().substring(0,16);
                System.out.println("Senha inicial gerada: " + password);
            }
            service.bootstrap(email,password);
            System.out.println("Administrador inicial: " + email);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
        Server server = new Server(service,port,Path.of("web"));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start(); System.out.println("Almoxarifado: http://localhost:" + server.port());
    }
}
