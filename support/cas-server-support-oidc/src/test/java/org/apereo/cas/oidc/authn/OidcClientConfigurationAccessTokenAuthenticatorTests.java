package org.apereo.cas.oidc.authn;

import org.apereo.cas.oidc.AbstractOidcTests;
import org.apereo.cas.oidc.OidcConstants;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.pac4j.core.context.JEEContext;
import org.pac4j.core.credentials.TokenCredentials;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * This is {@link OidcClientConfigurationAccessTokenAuthenticatorTests}.
 *
 * @author Misagh Moayyed
 * @since 6.1.0
 */
public class OidcClientConfigurationAccessTokenAuthenticatorTests extends AbstractOidcTests {

    @Test
    public void verifyOperation() {
        val request = new MockHttpServletRequest();
        val ctx = new JEEContext(request, new MockHttpServletResponse());
        val auth = new OidcClientConfigurationAccessTokenAuthenticator(ticketRegistry);
        val at = getAccessToken();
        when(at.getScopes()).thenReturn(Set.of(OidcConstants.CLIENT_REGISTRATION_SCOPE));
        ticketRegistry.addTicket(at);
        val credentials = new TokenCredentials(at.getId());

        auth.validate(credentials, ctx);

        val userProfile = credentials.getUserProfile();
        assertNotNull(userProfile);
        assertEquals("casuser", userProfile.getId());
    }
}
