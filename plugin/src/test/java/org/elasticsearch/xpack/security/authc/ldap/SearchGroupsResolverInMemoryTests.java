/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.ldap;

import java.util.List;
import java.util.concurrent.ExecutionException;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapTestCase;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils;
import org.junit.After;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;

public class SearchGroupsResolverInMemoryTests extends LdapTestCase {

    private static final String WILLIAM_BUSH = "cn=William Bush,ou=people,o=sevenSeas";
    private LDAPConnection connection;

    @After
    public void closeConnection() {
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * Tests that a client-side timeout in the asynchronous LDAP SDK is treated as a failure, rather
     * than simply returning no results.
     */
    public void testSearchTimeoutIsFailure() throws Exception {
        ldapServers[0].setProcessingDelayMillis(100);

        final LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(500);
        options.setResponseTimeoutMillis(5);
        connect(options);

        final Settings settings = Settings.builder()
                .put("group_search.base_dn", "ou=groups,o=sevenSeas")
                .put("group_search.scope", LdapSearchScope.SUB_TREE)
                .build();
        final SearchGroupsResolver resolver = new SearchGroupsResolver(settings);
        final PlainActionFuture<List<String>> future = new PlainActionFuture<>();
        resolver.resolve(connection, WILLIAM_BUSH, TimeValue.timeValueSeconds(30), logger, null, future);

        final ExecutionException exception = expectThrows(ExecutionException.class, future::get);
        final Throwable cause = exception.getCause();
        assertThat(cause, instanceOf(LDAPException.class));
        assertThat(((LDAPException) cause).getResultCode(), is(ResultCode.TIMEOUT));
    }

    /**
     * Tests searching for groups when the "user_attribute" field is not set
     */
    public void testResolveWithDefaultUserAttribute() throws Exception {
        connect(new LDAPConnectionOptions());

        Settings settings = Settings.builder()
                .put("group_search.base_dn", "ou=groups,o=sevenSeas")
                .put("group_search.scope", LdapSearchScope.SUB_TREE)
                .build();

        final List<String> groups = resolveGroups(settings, WILLIAM_BUSH);
        assertThat(groups, iterableWithSize(1));
        assertThat(groups.get(0), containsString("HMS Lydia"));
    }

    /**
     * Tests searching for groups when the "user_attribute" field is set to "dn" (which is special)
     */
    public void testResolveWithExplicitDnAttribute() throws Exception {
        connect(new LDAPConnectionOptions());

        Settings settings = Settings.builder()
                .put("group_search.base_dn", "ou=groups,o=sevenSeas")
                .put("group_search.user_attribute", "dn")
                .build();

        final List<String> groups = resolveGroups(settings, WILLIAM_BUSH);
        assertThat(groups, iterableWithSize(1));
        assertThat(groups.get(0), containsString("HMS Lydia"));
    }

    /**
     * Tests searching for groups when the "user_attribute" field is set to a missing value
     */
    public void testResolveWithMissingAttribute() throws Exception {
        connect(new LDAPConnectionOptions());

        Settings settings = Settings.builder()
                .put("group_search.base_dn", "ou=groups,o=sevenSeas")
                .put("group_search.user_attribute", "no-such-attribute")
                .build();

        final List<String> groups = resolveGroups(settings, WILLIAM_BUSH);
        assertThat(groups, iterableWithSize(0));
    }

    private void connect(LDAPConnectionOptions options) throws LDAPException {
        if (connection != null) {
            throw new IllegalStateException("Already connected (" + connection.getConnectionName() + ' '
                    + connection.getConnectedAddress() + ')');
        }
        final LDAPURL ldapurl = new LDAPURL(ldapUrls()[0]);
        this.connection = LdapUtils.privilegedConnect(() -> new LDAPConnection(options, ldapurl.getHost(), ldapurl.getPort()));
    }

    private List<String> resolveGroups(Settings settings, String userDn) {
        final SearchGroupsResolver resolver = new SearchGroupsResolver(settings);
        final PlainActionFuture<List<String>> future = new PlainActionFuture<>();
        resolver.resolve(connection, userDn, TimeValue.timeValueSeconds(30), logger, null, future);
        return future.actionGet();
    }

}
