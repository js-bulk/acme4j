/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2015 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.shredzone.acme4j.util.TestUtils.*;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

import java.net.HttpURLConnection;
import java.net.URL;

import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.CompactSerializer;
import org.jose4j.lang.JoseException;
import org.junit.Test;
import org.shredzone.acme4j.connector.Resource;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.provider.TestableConnectionProvider;
import org.shredzone.acme4j.util.JSON;
import org.shredzone.acme4j.util.JSONBuilder;
import org.shredzone.acme4j.util.TestUtils;

/**
 * Unit tests for {@link AccountBuilder}.
 */
public class AccountBuilderTest {

    private URL resourceUrl = url("http://example.com/acme/resource");
    private URL locationUrl = url("http://example.com/acme/account");;

    /**
     * Test if a new account can be created.
     */
    @Test
    public void testRegistration() throws Exception {
        TestableConnectionProvider provider = new TestableConnectionProvider() {
            private boolean isUpdate;

            @Override
            public void sendSignedRequest(URL url, JSONBuilder claims, Session session) {
                assertThat(session, is(notNullValue()));
                assertThat(url, is(locationUrl));
                assertThat(isUpdate, is(false));
                isUpdate = true;
            }

            @Override
            public void sendSignedRequest(URL url, JSONBuilder claims, Session session, boolean enforceJwk) {
                assertThat(session, is(notNullValue()));
                assertThat(url, is(resourceUrl));
                assertThat(claims.toString(), sameJSONAs(getJSON("newAccount").toString()));
                assertThat(enforceJwk, is(true));
                isUpdate = false;
            }

            @Override
            public int accept(int... httpStatus) throws AcmeException {
                if (isUpdate) {
                    assertThat(httpStatus, isIntArrayContainingInAnyOrder(HttpURLConnection.HTTP_OK));
                    return HttpURLConnection.HTTP_OK;
                } else {
                    assertThat(httpStatus, isIntArrayContainingInAnyOrder(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED));
                    return HttpURLConnection.HTTP_CREATED;
                }
            }

            @Override
            public URL getLocation() {
                return locationUrl;
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("newAccountResponse");
            }
        };

        provider.putTestResource(Resource.NEW_ACCOUNT, resourceUrl);

        AccountBuilder builder = new AccountBuilder();
        builder.addContact("mailto:foo@example.com");
        builder.agreeToTermsOfService();

        Session session = provider.createSession();
        Account account = builder.create(session);

        assertThat(account.getLocation(), is(locationUrl));
        assertThat(account.getTermsOfServiceAgreed(), is(true));
        assertThat(session.getKeyIdentifier(), is(locationUrl.toString()));

        try {
            AccountBuilder builder2 = new AccountBuilder();
            builder2.agreeToTermsOfService();
            builder2.create(session);
            fail("registered twice on same session");
        } catch (IllegalArgumentException ex) {
            // expected
        }

        provider.close();
    }

    /**
     * Test if a new account with Key Identifier can be created.
     */
    @Test
    public void testRegistrationWithKid() throws Exception {
        String keyIdentifier = "NCC-1701";

        TestableConnectionProvider provider = new TestableConnectionProvider() {
            @Override
            public void sendSignedRequest(URL url, JSONBuilder claims, Session session, boolean enforceJwk) {
                try {
                    assertThat(session, is(notNullValue()));
                    assertThat(url, is(resourceUrl));
                    assertThat(enforceJwk, is(true));

                    JSON binding = claims.toJSON()
                                    .get("external-account-binding")
                                    .required()
                                    .asObject();

                    String encodedHeader = binding.get("protected").asString();
                    String encodedSignature = binding.get("signature").asString();
                    String encodedPayload = binding.get("payload").asString();

                    String serialized = CompactSerializer.serialize(encodedHeader, encodedPayload, encodedSignature);
                    JsonWebSignature jws = new JsonWebSignature();
                    jws.setCompactSerialization(serialized);
                    jws.setKey(session.getKeyPair().getPublic());
                    assertThat(jws.verifySignature(), is(true));

                    assertThat(jws.getHeader("url"), is(resourceUrl.toString()));
                    assertThat(jws.getHeader("kid"), is(keyIdentifier));
                    assertThat(jws.getHeader("alg"), is("RS256"));

                    String decodedPayload = jws.getPayload();
                    StringBuilder expectedPayload = new StringBuilder();
                    expectedPayload.append('{');
                    expectedPayload.append("\"kty\":\"").append(TestUtils.KTY).append("\",");
                    expectedPayload.append("\"e\":\"").append(TestUtils.E).append("\",");
                    expectedPayload.append("\"n\":\"").append(TestUtils.N).append("\"");
                    expectedPayload.append("}");
                    assertThat(decodedPayload, sameJSONAs(expectedPayload.toString()));
                } catch (JoseException ex) {
                    ex.printStackTrace();
                    fail("decoding inner payload failed");
                }
            }

            @Override
            public int accept(int... httpStatus) throws AcmeException {
                assertThat(httpStatus, isIntArrayContainingInAnyOrder(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED));
                return HttpURLConnection.HTTP_CREATED;
            }

            @Override
            public URL getLocation() {
                return locationUrl;
            }

            @Override
            public JSON readJsonResponse() {
                return JSON.empty();
            }
        };

        provider.putTestResource(Resource.NEW_ACCOUNT, resourceUrl);

        AccountBuilder builder = new AccountBuilder();
        builder.useKeyIdentifier(keyIdentifier);

        Session session = provider.createSession();
        Account account = builder.create(session);

        assertThat(account.getLocation(), is(locationUrl));
        assertThat(session.getKeyIdentifier(), is(keyIdentifier));

        provider.close();
    }

}