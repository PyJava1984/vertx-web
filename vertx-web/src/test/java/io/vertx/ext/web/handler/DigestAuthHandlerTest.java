/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.handler;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.auth.htdigest.HtdigestAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.WebTestBase;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;

import static io.vertx.ext.auth.impl.Codec.base16Encode;

/**
 * @author <a href="mailto:plopes@redhat.com">Paulo Lopes</a>
 */
public class DigestAuthHandlerTest extends WebTestBase {

  private static final MessageDigest MD5;
  private static final String DEFAULT_NONCE_MAP_NAME = "htdigest.nonces";

  static {
    try {
      MD5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testLoginDefaultRealm() throws Exception {
    doLogin("testrealm@host.com");
  }

  @Test
  public void checkNoncesCleanup() throws Exception {
    router.clear();
    HtdigestAuth authProvider = HtdigestAuth.create(vertx);
    /* set nonceExpireTimeout to a negative value so the cached nonces must be deleted as soon as a new request
     * is done
     */
    router.route("/dir/*").handler(DigestAuthHandler.create(vertx, authProvider, -100));
    int initialNoncesSize = vertx.sharedData().getLocalMap(DEFAULT_NONCE_MAP_NAME).size();
    /* Now make some new requests without authentication: for each new request a new nonce is generated
     * and the old one is expired so the final nonces size must be equal to the initial one + 1
     */
    int numRequests = 5;
    for (int i = 0; i < numRequests; ++i) {
      testRequest(HttpMethod.GET, "/dir/index.html", null, null, 401, "Unauthorized", null);
    }
    int finalNoncesSize = vertx.sharedData().getLocalMap(DEFAULT_NONCE_MAP_NAME).size();
    assertEquals(initialNoncesSize + 1, finalNoncesSize);
  }

  private void doLogin(String realm) throws Exception {
    router.clear();
    Handler<RoutingContext> handler = rc -> {
      assertNotNull(rc.user());
      assertEquals("Mufasa", rc.user().principal().getString("username"));
      rc.response().end("Welcome to the protected resource!");
    };

    HtdigestAuth authProvider = HtdigestAuth.create(vertx);
    router.route("/dir/*").handler(DigestAuthHandler.create(vertx, authProvider));

    router.route("/dir/index.html").handler(handler);

    final AtomicReference<String> nonce = new AtomicReference<>();
    final AtomicReference<String> opaque = new AtomicReference<>();

    testRequest(HttpMethod.GET, "/dir/index.html", null, resp -> {
      String wwwAuth = resp.headers().get("WWW-Authenticate");
      assertNotNull(wwwAuth);
      assertTrue(wwwAuth.startsWith("Digest realm=\"" + realm + "\", qop=\"auth\", nonce=\""));
      // extract nonce + opaque from the response
      int pos = wwwAuth.indexOf("nonce=\"") + 7;
      nonce.set(wwwAuth.substring(pos, endOfVariable(wwwAuth, pos, '\"')));
      pos = wwwAuth.indexOf("opaque=\"") + 8;
      opaque.set(wwwAuth.substring(pos, endOfVariable(wwwAuth, pos, '\"')));
    }, 401, "Unauthorized", null);

    // Now try again with credentials
    testRequest(HttpMethod.GET, "/dir/index.html", req -> {
      // rebuild the response value
      String response = md5("939e7578ed9e3c518a452acee763bce9:" + nonce.get() + ":00000001:0a4f113b:auth:39aff3a2bab6126f332b942af96d3366");
      // create the browser header
      req.putHeader("Authorization", "Digest username=\"Mufasa\", realm=\"testrealm@host.com\", nonce=\"" + nonce.get() + "\", uri=\"/dir/index.html\", qop=auth, nc=00000001, cnonce=\"0a4f113b\", response=\"" + response + "\", opaque=\"" + opaque.get() + "\"");
    }, resp -> {
      String wwwAuth = resp.headers().get("WWW-Authenticate");
      assertNull(wwwAuth);
    }, 200, "OK", "Welcome to the protected resource!");
  }

  private static int endOfVariable(String header, int pos, char delim) {
    int i;
    for (i = pos; i < header.length(); i++) {
      if (header.charAt(i) == delim) {
        break;
      }
    }
    return i;
  }

  private static synchronized String md5(String payload) {
    MD5.reset();
    return base16Encode(MD5.digest(payload.getBytes(StandardCharsets.UTF_8)));
  }

}
