/*
 * Copyright 2023 IT-Systemhaus der Bundesagentur fuer Arbeit
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.phasetwo.keycloak.common;

import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;

@JBossLog
public class ExpirationUtils {

  /**
   * Checks whether the {@code entity} is expired
   *
   * @param entity to check
   * @param allowInfiniteValues sets how null values are interpreted, if true entity with expiration
   *     equal to {@code null} is interpreted as never expiring entity, if false entities with
   *     {@code null} expiration are interpreted as expired entities
   * @return true if the {@code entity} is expired (expiration time is in the past or now), false
   *     otherwise
   */
  public static boolean isExpired(ExpirableEntity entity, boolean allowInfiniteValues) {
    Long expiration = entity.getExpiration();
    if (!allowInfiniteValues && expiration == null) return false;
    long now = Time.currentTimeMillis();
    boolean expired = expiration != null && expiration <= now;
    log.tracef(
        "isExpired %d <= %d ? %b %s",
        expiration, now, expired, expiration != null ? expiration - now : 0);
    return expired;
  }

  public static boolean isNotExpired(Object entity) {
    return !isExpired((ExpirableEntity) entity, true);
  }
}
