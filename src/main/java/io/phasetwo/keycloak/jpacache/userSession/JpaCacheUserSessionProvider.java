package io.phasetwo.keycloak.jpacache.userSession;

import static io.phasetwo.keycloak.common.ExpirationUtils.isExpired;
import static io.phasetwo.keycloak.jpacache.userSession.expiration.JpaCacheSessionExpiration.setClientSessionExpiration;
import static io.phasetwo.keycloak.jpacache.userSession.expiration.JpaCacheSessionExpiration.setUserSessionExpiration;
import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import static org.keycloak.models.UserSessionModel.CORRESPONDING_SESSION_ID;
import static org.keycloak.models.UserSessionModel.SessionPersistenceState.TRANSIENT;
import static org.keycloak.utils.StreamsUtil.closing;

import io.phasetwo.keycloak.common.TimeAdapter;
import io.phasetwo.keycloak.jpacache.userSession.expiration.SessionExpirationData;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.AuthenticatedClientSessionValue;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.UserSession;
import io.phasetwo.keycloak.jpacache.userSession.persistence.entities.UserSessionToAttributeMapping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import org.keycloak.device.DeviceActivityManager;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

@JBossLog
@RequiredArgsConstructor
public class JpaCacheUserSessionProvider implements UserSessionProvider {
  private final KeycloakSession session;
  private final EntityManager entityManager;

  private final Map<String, UserSession> transientUserSessions = new HashMap<>();

  private Function<UserSession, JpaCacheUserSessionAdapter> entityToAdapterFunc(RealmModel realm) {
    return (origEntity) -> {
      if (origEntity == null) {
        return null;
      }

      if (isExpired(origEntity, false)) {
        if (TRANSIENT == origEntity.getPersistenceState()) {
          transientUserSessions.remove(origEntity.getId());
        } else {
          entityManager.remove(origEntity);
          entityManager.flush();
        }
        return null;
      } else {
        return new JpaCacheUserSessionAdapter(session, realm, origEntity, entityManager);
      }
    };
  }

  @Override
  public KeycloakSession getKeycloakSession() {
    return session;
  }

  @Override
  public AuthenticatedClientSessionModel createClientSession(
      RealmModel realm, ClientModel client, UserSessionModel userSession) {
    log.tracef(
        "createClientSession(%s, %s, %s)%s", realm, client, userSession, getShortStackTrace());

    if (userSession == null) {
      throw new IllegalStateException("User session is null.");
    }

    UserSession userSessionEntity =
        ((JpaCacheUserSessionAdapter) userSession).getUserSessionEntity();

    if (userSessionEntity == null) {
      throw new IllegalStateException("User session entity does not exist: " + userSession.getId());
    }

    AuthenticatedClientSessionValue entity =
        createAuthenticatedClientSessionEntityInstance(null, client.getId(), false);
    entity.setParentSession(userSessionEntity);
    String started =
        entity.getTimestamp() != null
            ? String.valueOf(TimeAdapter.fromMilliSecondsToSeconds(entity.getTimestamp()))
            : String.valueOf(0);
    entity.getNotes().put(AuthenticatedClientSessionModel.STARTED_AT_NOTE, started);
    setClientSessionExpiration(
        entity, SessionExpirationData.builder().realm(realm).build(), client);
    userSessionEntity.getClientSessions().put(client.getId(), entity);
    if (userSessionEntity.getPersistenceState() != null
        && userSessionEntity.getPersistenceState() == TRANSIENT) {
      log.tracef(
          "don't persist client session %s, as parent user session is %s",
          entity, userSessionEntity.getPersistenceState());
    } else {
      log.tracef("persisted client session %s", entity);
      entityManager.persist(entity);
      entityManager.flush();
    }
    return userSession.getAuthenticatedClientSessionByClient(client.getId());
  }

  @Override
  public AuthenticatedClientSessionModel getClientSession(
      UserSessionModel userSession, ClientModel client, String clientSessionId, boolean offline) {
    log.tracef(
        "getClientSession(%s, %s, %s, %s)%s",
        userSession, client, clientSessionId, offline, getShortStackTrace());

    if (userSession == null) {
      return null;
    }

    // Reload Session to filter out transient sessions
    JpaCacheUserSessionAdapter currentSession =
        getUserSession(userSession.getRealm(), userSession.getId());
    return currentSession == null
        ? null
        : currentSession.getAuthenticatedClientSessionByClient(client.getId());
  }

  @Override
  public UserSessionModel createUserSession(
      RealmModel realm,
      UserModel user,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId) {
    return createUserSession(
        null,
        realm,
        user,
        loginUsername,
        ipAddress,
        authMethod,
        rememberMe,
        brokerSessionId,
        brokerUserId,
        UserSessionModel.SessionPersistenceState.PERSISTENT);
  }

  @Override
  public UserSessionModel createUserSession(
      String id,
      RealmModel realm,
      UserModel user,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId,
      UserSessionModel.SessionPersistenceState persistenceState) {
    log.tracef(
        "createUserSession(%s, %s, %s, %s)%s",
        id, realm, loginUsername, persistenceState, getShortStackTrace());

    UserSession entity =
        createUserSessionEntityInstance(
            id,
            realm.getId(),
            user.getId(),
            loginUsername,
            ipAddress,
            authMethod,
            rememberMe,
            brokerSessionId,
            brokerUserId,
            false);

    entity.setPersistenceState(persistenceState);
    setUserSessionExpiration(entity, SessionExpirationData.builder().realm(realm).build());
    if (id == null) {
      entity.setId(KeycloakModelUtils.generateId());
    }
    /* need to understand more about persistenceState */
    if (TRANSIENT == persistenceState) {
      transientUserSessions.put(entity.getId(), entity);
    } else {
      if (id != null && entityManager.find(UserSession.class, id) != null) {
        throw new ModelDuplicateException("User session exists: " + id);
      }
      entityManager.persist(entity);
      entityManager.flush();
      log.tracef("persisted user session %s", entity);
    }

    JpaCacheUserSessionAdapter userSession = entityToAdapterFunc(realm).apply(entity);

    if (userSession != null) {
      DeviceActivityManager.attachDevice(userSession, session);
    }

    return userSession;
  }

  @Override
  public JpaCacheUserSessionAdapter getUserSession(RealmModel realm, String id) {
    return getUserSession(realm, id, false);
  }

  private JpaCacheUserSessionAdapter getUserSession(RealmModel realm, String id, boolean offline) {
    Objects.requireNonNull(realm, "The provided realm can't be null!");

    log.tracef("getUserSession(%s, %s)%s", realm, id, getShortStackTrace());

    if (id == null) return null;

    UserSession session = transientUserSessions.get(id);
    if (session == null) session = entityManager.find(UserSession.class, id);
    if (session != null && session.isOffline() == offline) {
      return entityToAdapterFunc(realm).apply(session);
    } else {
      return null;
    }
  }

  @Override
  public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
    log.tracef("getUserSessionsStream(%s, %s)%s", realm, user, getShortStackTrace());

    TypedQuery<UserSession> query =
        entityManager.createNamedQuery("findUserSessionsByUserId2", UserSession.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("userId", user.getId());
    query.setParameter("now", Time.currentTimeMillis());

    return query.getResultStream()
            .map(entityToAdapterFunc((realm)));
  }

  @Override
  public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client) {
    log.tracef("getUserSessionsStream(%s, %s)%s", realm, client, getShortStackTrace());

    TypedQuery<AuthenticatedClientSessionValue> query =
        entityManager.createNamedQuery(
            "findClientSessionsByClientId", AuthenticatedClientSessionValue.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("clientId", client.getId());

    return query
        .getResultStream()
        .map(AuthenticatedClientSessionValue::getParentSession)
        .map(entityToAdapterFunc((realm)));
  }

  @Override
  public Stream<UserSessionModel> getUserSessionsStream(
      RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
    log.tracef(
        "getUserSessionsStream(%s, %s, %s, %s)%s",
        realm, client, firstResult, maxResults, getShortStackTrace());

    TypedQuery<AuthenticatedClientSessionValue> query =
        entityManager
            .createNamedQuery("findClientSessionsByClientId", AuthenticatedClientSessionValue.class)
            .setParameter("realmId", realm.getId())
            .setParameter("clientId", client.getId())
            .setFirstResult(firstResult)
            .setMaxResults(maxResults);

    return query
        .getResultStream()
        .map(AuthenticatedClientSessionValue::getParentSession)
        .map(entityToAdapterFunc((realm)));
  }

  @Override
  public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(
      RealmModel realm, String brokerUserId) {
    log.tracef(
        "getUserSessionByBrokerUserIdStream(%s, %s)%s", realm, brokerUserId, getShortStackTrace());

    TypedQuery<UserSession> query =
        entityManager.createNamedQuery("findUserSessionsByBrokerUserId", UserSession.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("brokerUserId", brokerUserId);
    return query.getResultStream().map(entityToAdapterFunc((realm)));
  }

  @Override
  public UserSessionModel getUserSessionByBrokerSessionId(
      RealmModel realm, String brokerSessionId) {
    log.tracef(
        "getUserSessionByBrokerSessionId(%s, %s)%s", realm, brokerSessionId, getShortStackTrace());

    TypedQuery<UserSession> query =
        entityManager.createNamedQuery("findUserSessionsByBrokerSessionId", UserSession.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("brokerSessionId", brokerSessionId);
    UserSession session = query.getSingleResult();
    return session != null ? entityToAdapterFunc(realm).apply(session) : null;
  }

  public Stream<UserSession> getUserSessionsByBrokerSessionId(
      RealmModel realm, String brokerSessionId) {
    log.tracef(
        "getUserSessionByBrokerSessionId(%s, %s)%s", realm, brokerSessionId, getShortStackTrace());

    TypedQuery<UserSession> query =
        entityManager.createNamedQuery("findUserSessionsByBrokerSessionId", UserSession.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("brokerSessionId", brokerSessionId);
    return query.getResultStream();
  }

  @Override
  public UserSessionModel getUserSessionWithPredicate(
      RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
    log.tracef(
        "getUserSessionWithPredicate(%s, %s, %s)%s", realm, id, offline, getShortStackTrace());

    Stream<JpaCacheUserSessionAdapter> userSessionEntityStream;
    if (offline) {
      userSessionEntityStream =
          getOfflineUserSessionEntityStream(realm, id)
              .map(entityToAdapterFunc(realm))
              .filter(Objects::nonNull);
    } else {
      JpaCacheUserSessionAdapter userSession = getUserSession(realm, id);
      userSessionEntityStream = userSession != null ? Stream.of(userSession) : Stream.empty();
    }

    return userSessionEntityStream.filter(predicate).findFirst().orElse(null);
  }

  @Override
  public long getActiveUserSessions(RealmModel realm, ClientModel client) {
    log.tracef("getActiveUserSessions(%s, %s)%s", realm, client, getShortStackTrace());

    return getUserSessionsStream(realm, client).count();
  }

  @Override
  public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
    log.tracef("getActiveClientSessionStats(%s, %s)%s", realm, offline, getShortStackTrace());

    var query = entityManager.createNamedQuery("countClientSessionsByClientIds", Object[].class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("offline", offline);
    return closing(query.getResultStream())
        .collect(Collectors.toMap(row -> row[0].toString(), row -> (Long) row[1]));
  }

  @Override
  public void removeUserSession(RealmModel realm, UserSessionModel session) {
    Objects.requireNonNull(session, "The provided user session can't be null!");

    log.tracef("removeUserSession(%s, %s)%s", realm, session, getShortStackTrace());

    // unlike merge or find, getReference doesn't issue a SELECT sql statement
    UserSession entity = entityManager.getReference(UserSession.class, session.getId());
    if (entity != null) {
      entityManager.remove(entity);
      entityManager.flush();
    }
  }

  @Override
  public void removeUserSessions(RealmModel realm, UserModel user) {
    log.tracef("removeUserSessions(%s, %s)%s", realm, user, getShortStackTrace());

    getUserSessionsStream(realm, user).forEach(s -> removeUserSession(realm, s));
  }

  @Override
  public void removeAllExpired() {
    entityManager
        .createNamedQuery("removeExpiredUserSessions")
        .setParameter("now", Time.currentTimeMillis())
        .executeUpdate();
  }

  @Override
  public void removeExpired(RealmModel realm) {
    entityManager
        .createNamedQuery("removeExpiredUserSessionsByRealm")
        .setParameter("realmId", realm.getId())
        .setParameter("now", Time.currentTimeMillis())
        .executeUpdate();
  }

  @Override
  public void removeUserSessions(RealmModel realm) {
    /*
    entityManager
        .createNamedQuery("removeAllUserSessions")
        .setParameter("realmId", realm.getId())
        .executeUpdate();
    */
    TypedQuery<UserSession> query =
        entityManager.createNamedQuery("findAllUserSessions", UserSession.class);
    query.setParameter("realmId", realm.getId());
    query
        .getResultStream()
        .forEach(
            entity -> {
              log.infof("removing session %s", entity.getId());
              entityManager.remove(entity);
              // entityManager.detach(entity);
              entityManager.flush();
            });
  }

  @Override
  public void onRealmRemoved(RealmModel realm) {
    log.tracef("onRealmRemoved(%s)%s", realm, getShortStackTrace());
    removeUserSessions(realm);
  }

  @Override
  public void onClientRemoved(RealmModel realm, ClientModel client) {
    log.tracef("onClientRemoved(%s, %s)%s", realm, client, getShortStackTrace());
    getUserSessionsStream(realm, client).forEach(s -> removeUserSession(realm, s));
  }

  // xgp TODO
  @Override
  public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
    log.tracef("createOfflineUserSession(%s)%s", userSession, getShortStackTrace());

    UserSession offlineUserSession = createUserSessionEntityInstance(userSession, true);
    long currentTime = Time.currentTimeMillis();
    offlineUserSession.setTimestamp(currentTime);
    offlineUserSession.setLastSessionRefresh(currentTime);
    setUserSessionExpiration(
        offlineUserSession, SessionExpirationData.builder().realm(userSession.getRealm()).build());

    entityManager.persist(offlineUserSession);

    // TODO
    // set a reference for the offline user session to the original online user session
    UserSession userSessionEntity = entityManager.find(UserSession.class, userSession.getId());
    var correspondingSessionId = offlineUserSession.getId();

    if (correspondingSessionId != null) {
      var userSessionToAttributeMapping =
          new UserSessionToAttributeMapping(
              KeycloakModelUtils.generateId(),
              offlineUserSession,
              CORRESPONDING_SESSION_ID,
              Arrays.asList(correspondingSessionId));
      userSessionEntity.getAttributes().add(userSessionToAttributeMapping);
      userSessionEntity
          .getNotes()
          .put(CORRESPONDING_SESSION_ID, correspondingSessionId); // compatibility
    }

    entityManager.flush();

    return entityToAdapterFunc(userSession.getRealm()).apply(offlineUserSession);
  }

  @Override
  public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
    log.tracef("getOfflineUserSession(%s, %s)%s", realm, userSessionId, getShortStackTrace());

    return getOfflineUserSessionEntityStream(realm, userSessionId)
        .findFirst()
        .map(entityToAdapterFunc(realm))
        .orElse(null);
  }

  @Override
  public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
    Objects.requireNonNull(userSession, "The provided user session can't be null!");

    log.tracef("removeOfflineUserSession(%s, %s)%s", realm, userSession, getShortStackTrace());

    UserSession userSessionEntity =
        ((JpaCacheUserSessionAdapter) userSession).getUserSessionEntity();
    if (userSessionEntity.getOffline() != null && userSessionEntity.getOffline()) {
      removeUserSession(realm, userSession);
    } else if (userSessionEntity.hasCorrespondingSession()) {
      String correspondingSessionId = userSessionEntity.getNotes().get(CORRESPONDING_SESSION_ID);
      UserSession correspondingSession =
          entityManager.find(UserSession.class, correspondingSessionId);
      entityManager.remove(correspondingSession);
      entityManager.flush();
    }
  }

  @Override
  public AuthenticatedClientSessionModel createOfflineClientSession(
      AuthenticatedClientSessionModel clientSession, UserSessionModel offlineUserSession) {
    log.tracef(
        "createOfflineClientSession(%s, %s)%s",
        clientSession, offlineUserSession, getShortStackTrace());

    AuthenticatedClientSessionValue clientSessionEntity =
        createAuthenticatedClientSessionInstance(clientSession, true);
    int currentTime = Time.currentTime();
    clientSessionEntity
        .getNotes()
        .put(AuthenticatedClientSessionModel.STARTED_AT_NOTE, String.valueOf(currentTime));
    clientSessionEntity.setTimestamp(Time.currentTimeMillis());
    RealmModel realm = clientSession.getRealm();
    setClientSessionExpiration(
        clientSessionEntity,
        SessionExpirationData.builder().realm(realm).build(),
        clientSession.getClient());

    Optional<UserSession> userSessionEntity =
        getOfflineUserSessionEntityStream(realm, offlineUserSession.getId()).findFirst();
    if (userSessionEntity.isPresent()) {
      UserSession userSession = userSessionEntity.get();
      String clientId = clientSession.getClient().getClientId();
      userSession.getClientSessions().put(clientId, clientSessionEntity);

      entityManager.persist(userSession);
      entityManager.flush();

      UserSessionModel userSessionModel = entityToAdapterFunc(realm).apply(userSession);
      return userSessionModel == null
          ? null
          : userSessionModel.getAuthenticatedClientSessionByClient(clientId);
    }
    return null;
  }

  @Override
  public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
    log.tracef("getOfflineUserSessionsStream(%s, %s)%s", realm, user, getShortStackTrace());

    return getUserSessionsStream(realm, user).filter(s -> s.isOffline());
  }

  @Override
  public UserSessionModel getOfflineUserSessionByBrokerSessionId(
      RealmModel realm, String brokerSessionId) {
    log.tracef(
        "getOfflineUserSessionByBrokerSessionId(%s, %s)%s",
        realm, brokerSessionId, getShortStackTrace());

    return getUserSessionsByBrokerSessionId(realm, brokerSessionId)
        .filter(s -> s.getRealmId().equals(realm.getId()))
        .filter(s -> s.getOffline() != null && s.getOffline())
        .map(entityToAdapterFunc(realm))
        .findFirst()
        .orElse(null);
  }

  @Override
  public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(
      RealmModel realm, String brokerUserId) {
    log.tracef(
        "getOfflineUserSessionByBrokerUserIdStream(%s, %s)%s",
        realm, brokerUserId, getShortStackTrace());

    return getUserSessionByBrokerUserIdStream(realm, brokerUserId).filter(s -> s.isOffline());
  }

  @Override
  public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
    log.tracef("getOfflineSessionsCount(%s, %s)%s", realm, client, getShortStackTrace());
    TypedQuery<Long> query =
        entityManager.createNamedQuery("countOfflineClientSessions", Long.class);
    query.setParameter("realmId", realm.getId());
    query.setParameter("clientId", client.getId());
    return query.getSingleResult();
  }

  // xgp TODO this might actually contain dupes - need a better query
  @Override
  public Stream<UserSessionModel> getOfflineUserSessionsStream(
      RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
    log.tracef(
        "getOfflineUserSessionsStream(%s, %s, %s, %s)%s",
        realm, client, firstResult, maxResults, getShortStackTrace());
    TypedQuery<AuthenticatedClientSessionValue> query =
        entityManager
            .createNamedQuery(
                "findOfflineClientSessionsByClientId", AuthenticatedClientSessionValue.class)
            .setParameter("realmId", realm.getId())
            .setParameter("clientId", client.getId())
            .setFirstResult(firstResult)
            .setMaxResults(maxResults);
    return query
        .getResultStream()
        .map(AuthenticatedClientSessionValue::getParentSession)
        .map(entityToAdapterFunc(realm));
    // dont need this anymore as the jpa query has ORDER BY
    //        .sorted(Comparator.comparing(UserSession::getLastSessionRefresh))
  }

  @Override
  public void importUserSessions(
      Collection<UserSessionModel> persistentUserSessions, boolean offline) {
    if (persistentUserSessions == null || persistentUserSessions.isEmpty()) {
      return;
    }

    persistentUserSessions.forEach(
        pus -> {
          UserSession userSessionEntity =
              createUserSessionEntityInstance(
                  null,
                  pus.getRealm().getId(),
                  pus.getUser().getId(),
                  pus.getLoginUsername(),
                  pus.getIpAddress(),
                  pus.getAuthMethod(),
                  pus.isRememberMe(),
                  pus.getBrokerSessionId(),
                  pus.getBrokerUserId(),
                  offline);
          userSessionEntity.setPersistenceState(
              UserSessionModel.SessionPersistenceState.PERSISTENT);

          for (Map.Entry<String, AuthenticatedClientSessionModel> entry :
              pus.getAuthenticatedClientSessions().entrySet()) {
            AuthenticatedClientSessionValue clientSession =
                createAuthenticatedClientSessionInstance(entry.getValue(), offline);

            // Update timestamp to same value as userSession. LastSessionRefresh of userSession from
            // DB will have correct value
            clientSession.setTimestamp(userSessionEntity.getLastSessionRefresh());

            userSessionEntity.getClientSessions().put(clientSession.getClientId(), clientSession);
            entityManager.persist(userSessionEntity);
            entityManager.persist(clientSession);
          }
        });
    entityManager.flush();
  }

  @Override
  public void close() {
    // Nothing to do
  }

  @Override
  public int getStartupTime(RealmModel realm) {
    return realm.getNotBefore();
  }

  private Stream<UserSession> getOfflineUserSessionEntityStream(
      RealmModel realm, String userSessionId) {
    if (userSessionId == null) {
      return Stream.empty();
    }

    // first get a user entity by ID
    // check if it's an offline user session
    UserSession userSessionEntity = entityManager.find(UserSession.class, userSessionId);
    if (userSessionEntity != null) {
      if (Boolean.TRUE.equals(userSessionEntity.getOffline())) {
        return Stream.of(userSessionEntity);
      }
    } else {
      // no session found by the given ID, try to find by corresponding session ID

      // userSessionEntity and UserSessionToAttributeMapping  are cascaded. Removal of one will result the removal of all associations
      // The NoSql approach will not work in case of the relational approach
      return Stream.empty();
    }

    // it's online user session so lookup offline user session by corresponding session id reference
    String offlineUserSessionId = userSessionEntity.getNotes().get(CORRESPONDING_SESSION_ID);
    if (offlineUserSessionId != null) {
      return Stream.of(entityManager.find(UserSession.class, userSessionId));
    }

    return Stream.empty();
  }

  private UserSession createUserSessionEntityInstance(
      UserSessionModel userSession, boolean offline) {
    UserSession entity =
        createUserSessionEntityInstance(
            null,
            userSession.getRealm().getId(),
            userSession.getUser().getId(),
            userSession.getLoginUsername(),
            userSession.getIpAddress(),
            userSession.getAuthMethod(),
            userSession.isRememberMe(),
            userSession.getBrokerSessionId(),
            userSession.getBrokerUserId(),
            offline);

    entity.setNotes(new HashMap<>(userSession.getNotes()));
    entity.setState(userSession.getState());
    entity.setTimestamp(TimeAdapter.fromSecondsToMilliseconds(userSession.getStarted()));
    entity.setLastSessionRefresh(
        TimeAdapter.fromSecondsToMilliseconds(userSession.getLastSessionRefresh()));

    return entity;
  }

  private UserSession createUserSessionEntityInstance(
      String id,
      String realmId,
      String userId,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId,
      boolean offline) {
    long timestamp = Time.currentTimeMillis();
    return UserSession.builder()
        .id(id == null ? KeycloakModelUtils.generateId() : id)
        .realmId(realmId)
        .userId(userId)
        .loginUsername(loginUsername)
        .ipAddress(ipAddress)
        .authMethod(authMethod)
        .rememberMe(rememberMe)
        .brokerSessionId(brokerSessionId)
        .brokerUserId(brokerUserId)
        .offline(offline)
        .timestamp(timestamp)
        .lastSessionRefresh(timestamp)
        .notes(new HashMap<>())
        .build();
  }

  private AuthenticatedClientSessionValue createAuthenticatedClientSessionEntityInstance(
      String id, String clientId, boolean offline) {
    return AuthenticatedClientSessionValue.builder()
        .id(id == null ? KeycloakModelUtils.generateId() : id)
        .clientId(clientId)
        .offline(offline)
        .timestamp(Time.currentTimeMillis())
        .notes(new HashMap<>())
        .build();
  }

  private AuthenticatedClientSessionValue createAuthenticatedClientSessionInstance(
      AuthenticatedClientSessionModel clientSession, boolean offline) {
    AuthenticatedClientSessionValue entity =
        createAuthenticatedClientSessionEntityInstance(
            null, clientSession.getClient().getId(), offline);

    entity.setAction(clientSession.getAction());
    entity.setAuthMethod(clientSession.getProtocol());
    entity.setNotes(new HashMap<>(clientSession.getNotes()));
    entity.setRedirectUri(clientSession.getRedirectUri());
    entity.setTimestamp(TimeAdapter.fromSecondsToMilliseconds(clientSession.getTimestamp()));

    return entity;
  }
}
