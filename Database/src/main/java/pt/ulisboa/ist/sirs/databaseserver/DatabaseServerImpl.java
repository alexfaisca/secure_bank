package pt.ulisboa.ist.sirs.databaseserver;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import pt.ulisboa.ist.sirs.contract.databaseserver.DatabaseServer.*;
import pt.ulisboa.ist.sirs.contract.databaseserver.DatabaseServiceGrpc.DatabaseServiceImplBase;
import pt.ulisboa.ist.sirs.databaseserver.dto.MovementDto;
import pt.ulisboa.ist.sirs.databaseserver.dto.TicketDto;
import pt.ulisboa.ist.sirs.databaseserver.grpc.crypto.AbstractCryptographicDatabaseServiceImpl;
import pt.ulisboa.ist.sirs.databaseserver.grpc.crypto.DatabaseServerCryptographicManager;
import pt.ulisboa.ist.sirs.databaseserver.repository.DatabaseManager;
import pt.ulisboa.ist.sirs.utils.Utils;
import pt.ulisboa.ist.sirs.cryptology.Base;
import pt.ulisboa.ist.sirs.utils.exceptions.ReplayAttackException;
import pt.ulisboa.ist.sirs.utils.exceptions.TamperedMessageException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class DatabaseServerImpl extends DatabaseServiceImplBase {
  private abstract static class DatabaseServiceImpl extends AbstractCryptographicDatabaseServiceImpl implements BindableService {
    @Override
    public abstract ServerServiceDefinition bindService();
  }
  private final boolean debug;
  private final DatabaseManager databaseManager;
  private final DatabaseServerCryptographicManager crypto;
  private final List<OffsetDateTime> timestamps = new ArrayList<>();
  public final BindableService service;

  public DatabaseServerImpl(DatabaseManager databaseManager, DatabaseServerCryptographicManager crypto, boolean debug) {
    final DatabaseServerImpl serverImpl = this;
    this.crypto = crypto;
    this.service = new DatabaseServiceImpl() {
      @Override
      public ServerServiceDefinition bindService() {
        return super.bindService(crypto, serverImpl);
      }
    };
    this.debug = debug;
    this.databaseManager = databaseManager;
  }

  public BindableService getService() {
    return service;
  }

  public boolean isDebug() {
    return debug;
  }

  public List<OffsetDateTime> getTimestamps() {
    return this.timestamps;
  }

  public void addTimestamp(OffsetDateTime timestamp) {
    this.timestamps.add(timestamp);
  }

  public boolean oldTimestampString(OffsetDateTime timestamp) {
    return getTimestamps().contains(timestamp);
  }

  @Override
  public void authenticate(AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    try {
      // Needham-Schroeder step 3
      String timestampString = request.getTimestamp();
      if (oldTimestampString(OffsetDateTime.parse(timestampString)))
        throw new ReplayAttackException();
      addTimestamp(OffsetDateTime.parse(timestampString));
      TicketDto ticket = crypto.unbundleTicket(request.getTicket().toByteArray());

      // Store session key and session iv
      crypto.createSession(ticket.sessionKey(), ticket.sessionIV());

      // Needham-Schroeder step 4
      responseObserver.onNext(
        AuthenticateResponse.newBuilder()
          .setServerChallenge(crypto.initializeNonce())
          .setServerCert(ByteString.copyFrom(Utils.readBytesFromFile(Base.CryptographicCore.getCertPath())))
      .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void stillAlive(StillAliveRequest request, StreamObserver<StillAliveResponse> responseObserver) {
    try {
      // Needham-Schroeder step 5
      if (!crypto.checkNonce(request.getServerChallenge() + 1))
        throw new TamperedMessageException();
      crypto.validateSession(request.getPublicKey().toByteArray());

      responseObserver.onNext(
        StillAliveResponse.newBuilder().setClientChallenge(request.getClientChallenge() + 1)
      .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  public void createAccount(CreateAccountRequest request, StreamObserver<Ack> responseObserver) {
    try {
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: create account");

      List<String> usernames = request.getNamesList().stream().toList();
      byte[] password = request.getPassword().toByteArray();
      OffsetDateTime timestamp = OffsetDateTime.parse(request.getTimestamp());

      databaseManager.createAccount(usernames, password, BigDecimal.ZERO, timestamp);

      responseObserver.onNext(Ack.getDefaultInstance());
      responseObserver.onCompleted();
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: create account successful");
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  public void deleteAccount(DeleteAccountRequest request, StreamObserver<Ack> responseObserver) {
    try {
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: delete account");

      String username = request.getName();
      byte[] password = request.getPassword().toByteArray();
      OffsetDateTime timestamp = OffsetDateTime.parse(request.getTimestamp());

      databaseManager.deleteAccount(username, password, timestamp);

      responseObserver.onNext(Ack.getDefaultInstance());
      responseObserver.onCompleted();
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: delete account successful");
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void balance(BalanceRequest request, StreamObserver<BalanceResponse> responseObserver) {
    try {
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: balance");

      String username = request.getName();
      byte[] password = request.getPassword().toByteArray();
      OffsetDateTime timestamp = OffsetDateTime.parse(request.getTimestamp());

      BigDecimal balance = databaseManager.balance(username, password, timestamp);

      responseObserver.onNext(BalanceResponse.newBuilder().setAmount(balance.toString()).build());
      responseObserver.onCompleted();
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: balance successful");
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  public void getMovements(GetMovementsRequest request, StreamObserver<GetMovementsResponse> responseObserver) {
    try {
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: get account movements");

      String username = request.getName();
      byte[] password = request.getPassword().toByteArray();
      OffsetDateTime timestamp = OffsetDateTime.parse(request.getTimestamp());

      List<MovementDto> movements = databaseManager.getMovements(username, password, timestamp);

      responseObserver.onNext(GetMovementsResponse.newBuilder().addAllMovements(
        movements.stream().map(m -> GetMovementsResponse.Movement.newBuilder()
          .setId(m.movementRef().toString())
          .setCurrency(m.currency())
          .setDate(m.date().toString())
          .setValue(m.amount().toString())
          .setDescription(m.description())
          .build()
        ).toList()
      ).build());
      responseObserver.onCompleted();
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: get account movements successful");
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  public void orderPayment(OrderPaymentRequest request, StreamObserver<Ack> responseObserver) {
    try {
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: order payment");

      String username = request.getName();
      byte[] password = request.getPassword().toByteArray();
      LocalDateTime date = LocalDateTime.parse(request.getDate());
      BigDecimal amount = new BigDecimal(request.getAmount());
      String description = request.getDescription();
      String recipient = request.getRecipient();
      OffsetDateTime timestamp = OffsetDateTime.parse(request.getTimestamp());

      databaseManager.orderPayment(username, password, date, amount, description, recipient, timestamp);

      responseObserver.onNext(Ack.getDefaultInstance());
      responseObserver.onCompleted();
      if (isDebug())
        System.out.println("\tDatabaseServerImpl: order payment successful");
    } catch (Exception e) {
      responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
    }
  }

}
