package org.apereo.cas.web.flow;

import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.audit.AuditableExecution;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationServiceSelectionPlan;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.adaptive.AdaptiveAuthenticationPolicy;
import org.apereo.cas.authentication.principal.ClientCredential;
import org.apereo.cas.authentication.principal.ClientCustomPropertyConstants;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.ticket.AbstractTicketException;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.validation.DelegatedAuthenticationAccessStrategyHelper;
import org.apereo.cas.web.DelegatedClientNavigationController;
import org.apereo.cas.web.DelegatedClientWebflowManager;
import org.apereo.cas.web.flow.actions.AbstractAuthenticationAction;
import org.apereo.cas.web.flow.resolver.CasDelegatingWebflowEventResolver;
import org.apereo.cas.web.flow.resolver.CasWebflowEventResolver;
import org.apereo.cas.web.support.ArgumentExtractor;
import org.apereo.cas.web.support.WebUtils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.pac4j.core.client.BaseClient;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.client.IndirectClient;
import org.pac4j.core.context.JEEContext;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.saml.metadata.SAML2ServiceProviderMetadataResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.HttpServletRequest;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * This class represents an action to put at the beginning of the webflow.
 * <p>
 * Before any authentication, redirection urls are computed for the different clients defined as well as the theme,
 * locale, method and service are saved into the web session.</p>
 * After authentication, appropriate information are expected on this callback url to finish the authentication
 * process with the provider.
 *
 * @author Jerome Leleu
 * @since 3.5.0
 */
@Slf4j
@Getter
public class DelegatedClientAuthenticationAction extends AbstractAuthenticationAction {
    /**
     * All the urls and names of the provider clients.
     */
    public static final String FLOW_ATTRIBUTE_PROVIDER_URLS = "delegatedAuthenticationProviderUrls";

    private static final Pattern PAC4J_CLIENT_SUFFIX_PATTERN = Pattern.compile("Client\\d*");
    private static final Pattern PAC4J_CLIENT_CSS_CLASS_SUBSTITUTION_PATTERN = Pattern.compile("\\W");

    /**
     * The Clients.
     */
    protected final Clients clients;

    /**
     * The Services manager.
     */
    protected final ServicesManager servicesManager;

    /**
     * The Delegated authentication policy enforcer.
     */
    protected final AuditableExecution delegatedAuthenticationPolicyEnforcer;

    /**
     * The Delegated client webflow manager.
     */
    protected final DelegatedClientWebflowManager delegatedClientWebflowManager;

    /**
     * The Authentication system support.
     */
    protected final AuthenticationSystemSupport authenticationSystemSupport;

    private final AuthenticationServiceSelectionPlan authenticationRequestServiceSelectionStrategies;

    private final CentralAuthenticationService centralAuthenticationService;

    private final SingleSignOnParticipationStrategy singleSignOnParticipationStrategy;

    private final SessionStore<JEEContext> sessionStore;

    private final DelegatedAuthenticationAccessStrategyHelper delegatedAuthenticationAccessStrategyHelper;

    private final CasConfigurationProperties casProperties;

    private final List<ArgumentExtractor> argumentExtractors;

    public DelegatedClientAuthenticationAction(final CasDelegatingWebflowEventResolver initialAuthenticationAttemptWebflowEventResolver,
                                               final CasWebflowEventResolver serviceTicketRequestWebflowEventResolver,
                                               final AdaptiveAuthenticationPolicy adaptiveAuthenticationPolicy,
                                               final Clients clients,
                                               final ServicesManager servicesManager,
                                               final AuditableExecution delegatedAuthenticationPolicyEnforcer,
                                               final DelegatedClientWebflowManager delegatedClientWebflowManager,
                                               final AuthenticationSystemSupport authenticationSystemSupport,
                                               final CasConfigurationProperties casProperties,
                                               final AuthenticationServiceSelectionPlan authenticationRequestServiceSelectionStrategies,
                                               final CentralAuthenticationService centralAuthenticationService,
                                               final SingleSignOnParticipationStrategy singleSignOnParticipationStrategy,
                                               final SessionStore<JEEContext> sessionStore,
                                               final List<ArgumentExtractor> argumentExtractors) {
        super(initialAuthenticationAttemptWebflowEventResolver, serviceTicketRequestWebflowEventResolver, adaptiveAuthenticationPolicy);
        this.clients = clients;
        this.servicesManager = servicesManager;
        this.delegatedAuthenticationPolicyEnforcer = delegatedAuthenticationPolicyEnforcer;
        this.delegatedClientWebflowManager = delegatedClientWebflowManager;
        this.authenticationSystemSupport = authenticationSystemSupport;
        this.authenticationRequestServiceSelectionStrategies = authenticationRequestServiceSelectionStrategies;
        this.centralAuthenticationService = centralAuthenticationService;
        this.singleSignOnParticipationStrategy = singleSignOnParticipationStrategy;
        this.sessionStore = sessionStore;
        this.casProperties = casProperties;
        this.delegatedAuthenticationAccessStrategyHelper =
            new DelegatedAuthenticationAccessStrategyHelper(this.servicesManager, delegatedAuthenticationPolicyEnforcer);
        this.argumentExtractors = argumentExtractors;
    }

    /**
     * Determine if request has errors.
     *
     * @param request the request
     * @param status  the status
     * @return the optional model and view, if request is an error.
     */
    public static Optional<ModelAndView> hasDelegationRequestFailed(final HttpServletRequest request, final int status) {
        val params = request.getParameterMap();
        if (Stream.of("error", "error_code", "error_description", "error_message").anyMatch(params::containsKey)) {
            val model = new HashMap<String, Object>();
            if (params.containsKey("error_code")) {
                model.put("code", StringEscapeUtils.escapeHtml4(request.getParameter("error_code")));
            } else {
                model.put("code", status);
            }
            model.put("error", StringEscapeUtils.escapeHtml4(request.getParameter("error")));
            model.put("reason", StringEscapeUtils.escapeHtml4(request.getParameter("error_reason")));
            if (params.containsKey("error_description")) {
                model.put("description", StringEscapeUtils.escapeHtml4(request.getParameter("error_description")));
            } else if (params.containsKey("error_message")) {
                model.put("description", StringEscapeUtils.escapeHtml4(request.getParameter("error_message")));
            }
            model.put(CasProtocolConstants.PARAMETER_SERVICE, request.getAttribute(CasProtocolConstants.PARAMETER_SERVICE));
            model.put("client", StringEscapeUtils.escapeHtml4(request.getParameter("client_name")));
            LOGGER.debug("Delegation request has failed. Details are [{}]", model);
            return Optional.of(new ModelAndView("casPac4jStopWebflow", model));
        }
        return Optional.empty();
    }

    /**
     * Is this a SAML logout request?
     *
     * @param request the HTTP request
     * @return whether it is a SAML logout request
     */
    protected static boolean isLogoutRequest(final HttpServletRequest request) {
        return request.getParameter(SAML2ServiceProviderMetadataResolver.LOGOUT_ENDPOINT_PARAMETER) != null;
    }

    @Override
    public Event doExecute(final RequestContext context) {
        val request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
        val response = WebUtils.getHttpServletResponseFromExternalWebflowContext(context);
        val webContext = new JEEContext(request, response, this.sessionStore);

        if (!isLogoutRequest(request) && singleSignOnSessionExists(context)) {
            LOGGER.trace("Found existing single sign-on session");
            if (singleSignOnSessionAuthorizedForService(context)) {
                prepareRequestContextForSingleSignOn(context, webContext);
                prepareDelegatedClients(context);
                LOGGER.trace("Skipping delegation and routing back to CAS authentication flow");
                return resumeWebflow();
            }
            val resolvedService = resolveServiceFromRequestContext(context);
            LOGGER.debug("Single sign-on session in unauthorized for service [{}]", resolvedService);
            val tgt = WebUtils.getTicketGrantingTicketId(context);
            centralAuthenticationService.deleteTicket(tgt);
        }

        val clientName = request.getParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER);
        LOGGER.trace("Delegated authentication is handled by client name [{}]", clientName);
        if (hasDelegationRequestFailed(request, response.getStatus()).isPresent()) {
            throw new IllegalArgumentException("Delegated authentication has failed with client " + clientName);
        }

        if (StringUtils.isNotBlank(clientName)) {
            val service = restoreAuthenticationRequestInContext(context, webContext, clientName);
            val client = findDelegatedClientByName(request, clientName, service);

            try {
                LOGGER.debug("Fetching credentials from delegated client [{}]", client);
                val credentials = getCredentialsFromDelegatedClient(webContext, client);
                val clientCredential = new ClientCredential(credentials, clientName);
                LOGGER.info("Credentials are successfully authenticated using the delegated client [{}]", clientName);
                WebUtils.putCredential(context, clientCredential);
                WebUtils.putServiceIntoFlowScope(context, service);
                LOGGER.trace("Authentication is resolved by service request from [{}]", service);
                val resolvedService = authenticationRequestServiceSelectionStrategies.resolveService(service);
                val registeredService = servicesManager.findServiceBy(resolvedService);
                LOGGER.trace("Located registered service [{}] mapped to resolved service [{}]", registeredService, resolvedService);
                WebUtils.putRegisteredService(context, registeredService);
            } catch (final Exception e) {
                return handleException(webContext, client, e);
            }
            return super.doExecute(context);
        }

        prepareDelegatedClients(context);

        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            return stopWebflow();
        }
        return error();
    }

    /**
     * Handle the thrown exception.
     *
     * @param webContext the web context
     * @param client     the authentication client
     * @param e          the thrown exception
     * @return the event to trigger
     */
    protected Event handleException(final JEEContext webContext, final BaseClient<Credentials> client, final Exception e) {
        val msg = String.format("Delegated authentication has failed with client %s", client.getName());
        LOGGER.error(msg, e);
        throw new IllegalArgumentException(msg);
    }

    /**
     * Gets credentials from delegated client.
     *
     * @param webContext the web context
     * @param client     the client
     * @return the credentials from delegated client
     */
    protected Credentials getCredentialsFromDelegatedClient(final JEEContext webContext, final BaseClient<Credentials> client) {
        val credentials = client.getCredentials(webContext);
        LOGGER.debug("Retrieved credentials from client as [{}]", credentials);
        if (credentials.isEmpty()) {
            throw new IllegalArgumentException("Unable to determine credentials from the context with client " + client.getName());
        }
        return credentials.get();
    }

    /**
     * Find delegated client by name base client.
     *
     * @param request    the request
     * @param clientName the client name
     * @param service    the service
     * @return the base client
     */
    protected BaseClient<Credentials> findDelegatedClientByName(final HttpServletRequest request,
                                                                final String clientName, final Service service) {
        val clientResult = clients.findClient(clientName);
        if (clientResult.isEmpty()) {
            LOGGER.warn("Delegated client [{}] can not be located", clientName);
            throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE, StringUtils.EMPTY);
        }
        val client = BaseClient.class.cast(clientResult.get());
        LOGGER.debug("Delegated authentication client is [{}] with service [{}]", client, service);
        if (service != null) {
            request.setAttribute(CasProtocolConstants.PARAMETER_SERVICE, service.getId());
            if (!isDelegatedClientAuthorizedForService(client, service)) {
                LOGGER.warn("Delegated client [{}] is not authorized by service [{}]", client, service);
                throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE, StringUtils.EMPTY);
            }
        }
        client.init();
        return client;
    }

    /**
     * Prepare the data for the login page.
     *
     * @param context The current webflow context
     */
    protected void prepareDelegatedClients(final RequestContext context) {
        val currentService = WebUtils.getService(context);
        val service = authenticationRequestServiceSelectionStrategies.resolveService(currentService, WebApplicationService.class);

        val request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
        val response = WebUtils.getHttpServletResponseFromExternalWebflowContext(context);
        val webContext = new JEEContext(request, response, this.sessionStore);

        val urls = new LinkedHashSet<DelegatedClientIdentityProviderConfiguration>();
        this.clients
            .findAllClients()
            .stream()
            .filter(client -> client instanceof IndirectClient && isDelegatedClientAuthorizedForService(client, service))
            .map(IndirectClient.class::cast)
            .forEach(client -> {
                try {
                    client.init();
                    val provider = buildProviderConfiguration(client, webContext, currentService);
                    provider.ifPresent(p -> {
                        urls.add(p);

                        determineAutoRedirectPolicyForProvider(context, service, p);
                    });
                } catch (final Exception e) {
                    LOGGER.error("Cannot process client [{}]", client, e);
                }
            });

        if (!urls.isEmpty()) {
            context.getFlowScope().put(FLOW_ATTRIBUTE_PROVIDER_URLS, urls);
        } else if (response.getStatus() != HttpStatus.UNAUTHORIZED.value()) {
            LOGGER.warn("No delegated authentication providers could be determined based on the provided configuration. "
                + "Either no clients are configured, or the current access strategy rules prohibit CAS from using authentication providers for this request.");
        }
    }

    /**
     * Determine auto redirect policy for provider.
     *
     * @param context  the context
     * @param service  the service
     * @param provider the provider
     */
    protected void determineAutoRedirectPolicyForProvider(final RequestContext context,
                                                          final WebApplicationService service,
                                                          final DelegatedClientIdentityProviderConfiguration provider) {
        if (service != null) {
            val registeredService = servicesManager.findServiceBy(service);
            val delegatedPolicy = registeredService.getAccessStrategy().getDelegatedAuthenticationPolicy();
            if (delegatedPolicy.isExclusive() && delegatedPolicy.getAllowedProviders().size() == 1
                && provider.getName().equalsIgnoreCase(delegatedPolicy.getAllowedProviders().iterator().next())) {
                LOGGER.trace("Registered service [{}] is exclusively allowed to use provider [{}]", registeredService, provider);
                provider.setAutoRedirect(true);
                WebUtils.putDelegatedAuthenticationProviderPrimary(context, provider);
            }
        }

        if (WebUtils.getDelegatedAuthenticationProviderPrimary(context) == null && provider.isAutoRedirect()) {
            LOGGER.trace("Provider [{}] is configured to auto-redirect", provider);
            WebUtils.putDelegatedAuthenticationProviderPrimary(context, provider);
        }
    }

    /**
     * Build provider configuration optional.
     *
     * @param client     the client
     * @param webContext the web context
     * @param service    the service
     * @return the optional
     */
    protected Optional<DelegatedClientIdentityProviderConfiguration> buildProviderConfiguration(final IndirectClient client,
                                                                                                final WebContext webContext,
                                                                                                final WebApplicationService service) {
        val name = client.getName();
        val matcher = PAC4J_CLIENT_SUFFIX_PATTERN.matcher(client.getClass().getSimpleName());
        val type = matcher.replaceAll(StringUtils.EMPTY).toLowerCase();
        val uriBuilder = UriComponentsBuilder
            .fromUriString(DelegatedClientNavigationController.ENDPOINT_REDIRECT)
            .queryParam(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER, name);

        if (service != null) {
            val sourceParam = service.getSource();
            val serviceParam = service.getOriginalUrl();
            if (StringUtils.isNotBlank(sourceParam) && StringUtils.isNotBlank(serviceParam)) {
                uriBuilder.queryParam(sourceParam, serviceParam);
            }
        }

        val methodParam = webContext.getRequestParameter(CasProtocolConstants.PARAMETER_METHOD)
            .map(String::valueOf).orElse(StringUtils.EMPTY);
        if (StringUtils.isNotBlank(methodParam)) {
            uriBuilder.queryParam(CasProtocolConstants.PARAMETER_METHOD, methodParam);
        }
        val localeParam = webContext.getRequestParameter(casProperties.getLocale().getParamName())
            .map(String::valueOf).orElse(StringUtils.EMPTY);
        if (StringUtils.isNotBlank(localeParam)) {
            uriBuilder.queryParam(casProperties.getLocale().getParamName(), localeParam);
        }
        val themeParam = webContext.getRequestParameter(casProperties.getTheme().getParamName())
            .map(String::valueOf).orElse(StringUtils.EMPTY);
        if (StringUtils.isNotBlank(themeParam)) {
            uriBuilder.queryParam(casProperties.getTheme().getParamName(), themeParam);
        }
        val redirectUrl = uriBuilder.toUriString();
        val autoRedirect = (Boolean) client.getCustomProperties().getOrDefault(ClientCustomPropertyConstants.CLIENT_CUSTOM_PROPERTY_AUTO_REDIRECT, Boolean.FALSE);
        val p = new DelegatedClientIdentityProviderConfiguration(name, redirectUrl, type, getCssClass(name), autoRedirect);
        return Optional.of(p);
    }

    /**
     * Get a valid CSS class for the given provider name.
     *
     * @param name Name of the provider
     * @return the css class
     */
    protected String getCssClass(final String name) {
        var computedCssClass = "fa fa-lock";
        if (StringUtils.isNotBlank(name)) {
            computedCssClass = computedCssClass.concat(' ' + PAC4J_CLIENT_CSS_CLASS_SUBSTITUTION_PATTERN.matcher(name).replaceAll("-"));
        }
        LOGGER.trace("CSS class for [{}] is [{}]", name, computedCssClass);
        return computedCssClass;
    }

    /**
     * Stop webflow event.
     *
     * @return the event
     */
    protected Event stopWebflow() {
        return new Event(this, CasWebflowConstants.TRANSITION_ID_STOP);
    }

    /**
     * Resume webflow event.
     *
     * @return the event
     */
    protected Event resumeWebflow() {
        return new Event(this, CasWebflowConstants.TRANSITION_ID_RESUME);
    }

    /**
     * Restore authentication request in context service (return null for a logout call).
     *
     * @param requestContext the request context
     * @param webContext     the web context
     * @param clientName     the client name
     * @return the service
     */
    protected Service restoreAuthenticationRequestInContext(final RequestContext requestContext,
                                                            final JEEContext webContext,
                                                            final String clientName) {
        val logoutEndpoint = isLogoutRequest(webContext.getNativeRequest());
        if (logoutEndpoint) {
            return null;
        } else {
            try {
                val clientResult = this.clients.findClient(clientName);
                if (clientResult.isPresent()) {
                    return delegatedClientWebflowManager.retrieve(requestContext, webContext, BaseClient.class.cast(clientResult.get()));
                }
                LOGGER.warn("Unable to locate client [{}] in the collection of registered clients", clientName);
            } catch (final Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
            throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE, "Service Unauthorized");
        }
    }

    private boolean singleSignOnSessionAuthorizedForService(final RequestContext context) {
        val resolvedService = resolveServiceFromRequestContext(context);

        val authentication = getSingleSignOnAuthenticationFrom(context);
        return authentication
            .map(authn -> delegatedAuthenticationAccessStrategyHelper.isDelegatedClientAuthorizedForAuthentication(authn, resolvedService))
            .orElse(Boolean.FALSE);
    }

    private Service resolveServiceFromRequestContext(final RequestContext context) {
        val service = WebUtils.getService(context);
        return authenticationRequestServiceSelectionStrategies.resolveService(service);
    }

    private Optional<Authentication> getSingleSignOnAuthenticationFrom(final RequestContext requestContext) {
        val tgtId = WebUtils.getTicketGrantingTicketId(requestContext);
        if (StringUtils.isBlank(tgtId)) {
            LOGGER.trace("No ticket-granting ticket could be located in the webflow context");
            return Optional.empty();
        }
        val ticket = this.centralAuthenticationService.getTicket(tgtId, TicketGrantingTicket.class);
        if (ticket != null && !ticket.isExpired()) {
            LOGGER.trace("Located a valid ticket-granting ticket. Examining existing single sign-on session strategies...");
            return Optional.of(ticket.getAuthentication());
        }
        return Optional.empty();
    }

    /**
     * Is there a current SSO session?
     *
     * @param requestContext the request context
     * @return whether there is a current SSO session
     */
    protected boolean singleSignOnSessionExists(final RequestContext requestContext) {
        try {
            val authn = getSingleSignOnAuthenticationFrom(requestContext);
            if (authn.isPresent()) {
                LOGGER.trace("Located a valid ticket-granting ticket. Examining existing single sign-on session strategies...");
                val authentication = authn.get();
                val builder = this.authenticationSystemSupport.establishAuthenticationContextFromInitial(authentication);
                val credentials = authentication.getCredentials();
                if (!credentials.isEmpty()) {
                    credentials.forEach(c -> {
                        val credential = c.toCredential();
                        builder.collect(credential);
                    });
                    builder.getInitialCredential().ifPresent(c -> WebUtils.putCredential(requestContext, c));
                }
                LOGGER.trace("Recording and tracking initial authentication results in the request context");
                WebUtils.putAuthenticationResultBuilder(builder, requestContext);
                WebUtils.putAuthentication(authentication, requestContext);

                return singleSignOnParticipationStrategy.supports(requestContext)
                    && singleSignOnParticipationStrategy.isParticipating(requestContext);
            }
        } catch (final AbstractTicketException e) {
            LOGGER.trace("Could not retrieve ticket id [{}] from registry.", e.getMessage());
        }
        LOGGER.trace("Ticket-granting ticket found in the webflow context is invalid or has expired");
        return false;
    }

    private void prepareRequestContextForSingleSignOn(final RequestContext context, final JEEContext webContext) {
        val resolvedService = WebUtils.getService(argumentExtractors, context);
        WebUtils.putServiceIntoFlowScope(context, resolvedService);
        val registeredService = servicesManager.findServiceBy(resolvedService);
        WebUtils.putRegisteredService(context, registeredService);
    }

    private boolean isDelegatedClientAuthorizedForService(final Client<Credentials> client,
                                                          final Service service) {
        return delegatedAuthenticationAccessStrategyHelper.isDelegatedClientAuthorizedForService(client, service);
    }

    /**
     * The Provider login page configuration.
     */
    @AllArgsConstructor
    @Getter
    @ToString
    @Setter
    public static class DelegatedClientIdentityProviderConfiguration implements Serializable {
        private static final long serialVersionUID = 6216882278086699364L;

        private final String name;
        private final String redirectUrl;
        private final String type;
        private final String cssClass;
        private boolean autoRedirect;
    }
}
