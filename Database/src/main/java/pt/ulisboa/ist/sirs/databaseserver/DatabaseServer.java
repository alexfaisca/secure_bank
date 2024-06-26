package pt.ulisboa.ist.sirs.databaseserver;

import pt.ulisboa.ist.sirs.cryptology.Base;
import pt.ulisboa.ist.sirs.cryptology.Operations;
import pt.ulisboa.ist.sirs.databaseserver.grpc.crypto.AuthenticationClientCryptographicManager;
import pt.ulisboa.ist.sirs.databaseserver.grpc.crypto.DatabaseServerCryptographicInterceptor;
import pt.ulisboa.ist.sirs.databaseserver.grpc.DatabaseService;
import pt.ulisboa.ist.sirs.databaseserver.grpc.crypto.DatabaseServerCryptographicManager;
import pt.ulisboa.ist.sirs.databaseserver.repository.DatabaseManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import io.grpc.*;
import pt.ulisboa.ist.sirs.utils.Utils;

public class DatabaseServer {
  private final boolean debug;
  private final Server server;
  private final DatabaseManager state;

  public DatabaseServer(List<String> args, boolean debug) throws Exception {
    this.debug = debug;
    final String databaseAddress = args.get(2);
    final int databasePort = Integer.parseInt(args.get(3));
    final String namingServerAddress = args.get(4);
    final int namingServerPort = Integer.parseInt(args.get(5));
    final DatabaseServerCryptographicInterceptor crypto = new DatabaseServerCryptographicInterceptor();

    final DatabaseServerCryptographicManager cryptoCore = new DatabaseServerCryptographicManager(
      crypto, Base.CryptographicCore.getPublicKeyPath(), Base.CryptographicCore.getPrivateKeyPath()
    );
    this.state = new DatabaseManager(
      new DatabaseService.DatabaseServiceBuilder(
        args.get(0),
        args.get(1),
        databaseAddress,
        databasePort,
        namingServerAddress,
        namingServerPort,
        args.get(7),
        new AuthenticationClientCryptographicManager(),
        debug)
    .build());

    try {
      test(state);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e.getMessage());
    }

    final BindableService databaseService = new DatabaseServerImpl(state, cryptoCore, debug).getService();
    this.server = Grpc.newServerBuilderForPort(
      databasePort,
      TlsServerCredentials.newBuilder().keyManager(new File(args.get(7)), new File(args.get(8))).build())
      .addService(ServerInterceptors.intercept(databaseService, crypto)).build();
  }

  private void serverStartup() throws IOException {
    if (debug)
      System.out.println("Database: Starting up '" + state.getService().getServerServiceName() + "''s '"
        + state.getService().getServerName() + "' server at " + state.getService().getServerAddress() + ":"
        + state.getService().getServerPort());
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        serverShutdown();
      } catch (Exception e) {
        System.out.println(e.getMessage());
      }
    }));
  }

  private void test(DatabaseManager state) throws NoSuchAlgorithmException {
    // Initialize some accounts
    state.createAccount(
            Collections.singletonList("Alice"),
            Operations.hash("12345".getBytes()),
            new BigDecimal("10101010101"),
            OffsetDateTime.now()
    );
    state.createAccount(
            Collections.singletonList("Bob"),
            Operations.hash("12345".getBytes()),
            new BigDecimal("9599543"),
            OffsetDateTime.now()
    );
    state.orderPayment(
            "Bob",
            Operations.hash("12345".getBytes()),
            LocalDateTime.now(),
            new BigDecimal("1000"),
            "Last Tuesday's dinner",
            "Alice",
            OffsetDateTime.now()
    );
  }

  private void serverShutdown() {
    if (debug)
      System.out.println("Database: Deleting '" + state.getService().getServerServiceName() + "''s '"
        + state.getService().getServerName() + "' server at " + state.getService().getServerAddress() + ":"
        + state.getService().getServerPort());
    state.shutDown();
    server.shutdownNow();
    System.out.println("Shutting down.");
    System.exit(0);
  }

  private void blockUntilShutDown() {
    System.out.println("Press ENTER to delete '" + state.getService().getServerServiceName() + "''s '"
      + state.getService().getServerName() + "' server.");
    try (Scanner scan = new Scanner(System.in)) {
      scan.nextLine();
    }
    serverShutdown();
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Database Server");
    final boolean debug = true;

    if (System.getenv("service-name") == null ||
        System.getenv("server-name") == null ||
        System.getenv("server-address") == null ||
        System.getenv("server-port") == null ||
        System.getenv("naming-server-address") == null ||
        System.getenv("naming-server-port") == null ||
        System.getenv("path-server-trust-chain") == null ||
        System.getenv("path-server-cert") == null ||
        System.getenv("path-server-key") == null)
      throw new Exception("""
          Bad program usage. Please provide the following environment variables
              1.  <service-name>
              2.  <server-name>
              3.  <server-address>
              4.  <server-port>
              5.  <path-server-trust-chain>
              6. <path-server-cert>
              7. <path-server-key>
          """);

    try (FileInputStream certFile = new FileInputStream(System.getenv("path-server-cert"))) {
      CertificateFactory certGen = CertificateFactory.getInstance("X.509");
      X509Certificate cert = (X509Certificate) certGen.generateCertificate(certFile);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Utils.readBytesFromPemFile(System.getenv("path-server-key")));
      // Guarantee directory exists
      Base.CryptographicCore.initializeSelfDirectory();
      Utils.writeBytesToFile(cert.getPublicKey().getEncoded(), Base.CryptographicCore.getPublicKeyPath());
      Utils.writeBytesToFile(keyFactory.generatePrivate(keySpec).getEncoded(), Base.CryptographicCore.getPrivateKeyPath());

      DatabaseServer databaseServer = new DatabaseServer(
          List.of(
              System.getenv("service-name"),
              System.getenv("server-name"),
              System.getenv("server-address"),
              System.getenv("server-port"),
              System.getenv("naming-server-address"),
              System.getenv("naming-server-port"),
              System.getenv("path-server-trust-chain"),
              System.getenv("path-server-cert"),
              System.getenv("path-server-key")),
          debug);
      databaseServer.serverStartup();
      databaseServer.blockUntilShutDown();
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
