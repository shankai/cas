package org.apereo.cas.support.oauth.web.response.accesstoken.response;

import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.OAuth20ResponseTypes;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.ticket.accesstoken.AccessToken;
import org.apereo.cas.token.JwtBuilder;
import org.apereo.cas.util.DateTimeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apereo.inspektr.audit.annotation.Audit;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.MappingJackson2JsonView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This is {@link OAuth20DefaultAccessTokenResponseGenerator}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class OAuth20DefaultAccessTokenResponseGenerator implements OAuth20AccessTokenResponseGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * JWT builder.
     */
    protected final JwtBuilder accessTokenJwtBuilder;

    private static boolean shouldGenerateDeviceFlowResponse(final OAuth20AccessTokenResponseResult result) {
        val generatedToken = result.getGeneratedToken();
        return OAuth20ResponseTypes.DEVICE_CODE == result.getResponseType()
            && generatedToken.getDeviceCode().isPresent()
            && generatedToken.getUserCode().isPresent()
            && generatedToken.getAccessToken().isEmpty();
    }

    @Audit(action = "OAUTH2_ACCESS_TOKEN_RESPONSE",
        actionResolverName = "OAUTH2_ACCESS_TOKEN_RESPONSE_ACTION_RESOLVER",
        resourceResolverName = "OAUTH2_ACCESS_TOKEN_RESPONSE_RESOURCE_RESOLVER")
    @Override
    @SneakyThrows
    public ModelAndView generate(final HttpServletRequest request, final HttpServletResponse response,
                                 final OAuth20AccessTokenResponseResult result) {
        if (shouldGenerateDeviceFlowResponse(result)) {
            return generateResponseForDeviceToken(request, response, result);
        }

        return generateResponseForAccessToken(request, response, result);
    }

    /**
     * Generate response for device token model and view.
     *
     * @param request  the request
     * @param response the response
     * @param result   the result
     * @return the model and view
     */
    @SneakyThrows
    protected ModelAndView generateResponseForDeviceToken(final HttpServletRequest request,
                                                          final HttpServletResponse response,
                                                          final OAuth20AccessTokenResponseResult result) {
        val model = getDeviceTokenResponseModel(result);
        return new ModelAndView(new MappingJackson2JsonView(MAPPER), model);
    }

    /**
     * Gets device token response model.
     *
     * @param result the result
     * @return the device token response model
     */
    protected Map getDeviceTokenResponseModel(final OAuth20AccessTokenResponseResult result) {
        val model = new LinkedHashMap<String, Object>();
        val uri = result.getCasProperties().getServer().getPrefix()
            .concat(OAuth20Constants.BASE_OAUTH20_URL)
            .concat("/")
            .concat(OAuth20Constants.DEVICE_AUTHZ_URL);
        model.put(OAuth20Constants.DEVICE_VERIFICATION_URI, uri);
        model.put(OAuth20Constants.EXPIRES_IN, result.getDeviceTokenTimeout());
        val generatedToken = result.getGeneratedToken();
        generatedToken.getUserCode().ifPresent(c -> model.put(OAuth20Constants.DEVICE_USER_CODE, c));
        generatedToken.getDeviceCode().ifPresent(c -> model.put(OAuth20Constants.DEVICE_CODE, c));
        model.put(OAuth20Constants.DEVICE_INTERVAL, result.getDeviceRefreshInterval());
        return model;
    }

    /**
     * Generate response for access token model and view.
     *
     * @param request  the request
     * @param response the response
     * @param result   the result
     * @return the model and view
     */
    protected ModelAndView generateResponseForAccessToken(final HttpServletRequest request,
                                                          final HttpServletResponse response,
                                                          final OAuth20AccessTokenResponseResult result) {
        val model = getAccessTokenResponseModel(request, response, result);
        return new ModelAndView(new MappingJackson2JsonView(MAPPER), model);
    }

    /**
     * Generate internal.
     *
     * @param request  the request
     * @param response the response
     * @param result   the result
     * @return the access token response model
     */
    protected Map<String, Object> getAccessTokenResponseModel(final HttpServletRequest request,
                                                              final HttpServletResponse response,
                                                              final OAuth20AccessTokenResponseResult result) {
        val model = new LinkedHashMap<String, Object>();
        val generatedToken = result.getGeneratedToken();
        generatedToken.getAccessToken().ifPresent(t -> {
            model.put(OAuth20Constants.ACCESS_TOKEN, encodeAccessToken(t, result));
            model.put(OAuth20Constants.SCOPE, String.join(" ", t.getScopes()));
        });
        generatedToken.getRefreshToken().ifPresent(t -> model.put(OAuth20Constants.REFRESH_TOKEN, t.getId()));
        model.put(OAuth20Constants.TOKEN_TYPE, OAuth20Constants.TOKEN_TYPE_BEARER);
        model.put(OAuth20Constants.EXPIRES_IN, result.getAccessTokenTimeout());
        return model;
    }

    /**
     * Encode access token string.
     *
     * @param accessToken the access token
     * @param result      the result
     * @return the string
     */
    protected String encodeAccessToken(final AccessToken accessToken,
                                       final OAuth20AccessTokenResponseResult result) {

        val registeredService = OAuthRegisteredService.class.cast(result.getRegisteredService());
        val authentication = accessToken.getAuthentication();
        val service = result.getService();

        if (registeredService != null && registeredService.isJwtAccessToken()) {
            val dt = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(result.getAccessTokenTimeout());
            val builder = JwtBuilder.JwtRequest.builder();

            val request = builder
                .serviceAudience(service.getId())
                .issueDate(DateTimeUtils.dateOf(authentication.getAuthenticationDate()))
                .jwtId(accessToken.getId())
                .subject(authentication.getPrincipal().getId())
                .validUntilDate(DateTimeUtils.dateOf(dt))
                .attributes(authentication.getAttributes())
                .build();
            return accessTokenJwtBuilder.build(request);
        }

        return accessToken.getId();
    }
}
