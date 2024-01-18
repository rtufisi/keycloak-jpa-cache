package io.phasetwo.keycloak.jpacache.loginFailure;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.jpacache.connection.JpaCacheProvider;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserLoginFailureProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import jakarta.persistence.EntityManager;
import static io.phasetwo.keycloak.common.CommunityProfiles.isJpaCacheEnabled;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;
import static org.keycloak.userprofile.DeclarativeUserProfileProvider.PROVIDER_PRIORITY;

@AutoService(UserLoginFailureProviderFactory.class)
public class JpaCacheLoginFailureProviderFactory implements UserLoginFailureProviderFactory<JpaCacheLoginFailureProvider>, EnvironmentDependentProviderFactory {

  @Override
  public JpaCacheLoginFailureProvider create(KeycloakSession session) {
    EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    return new JpaCacheLoginFailureProvider(session, em);
  }

  @Override
  public void init(Config.Scope config) {

  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {

  }

  @Override
  public void close() {

  }

  @Override
  public String getId() {
    return "infinispan"; // use same name as infinispan provider to override it
  }

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }

  @Override
  public boolean isSupported() {
    return isJpaCacheEnabled();
  }
}
