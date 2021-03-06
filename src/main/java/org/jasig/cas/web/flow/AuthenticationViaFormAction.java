package org.jasig.cas.web.flow;

import com.gionee.cas.support.UsernamePasswordCaptchaCredential;
import org.apache.commons.lang3.StringUtils;
import org.jasig.cas.CasProtocolConstants;
import org.jasig.cas.CentralAuthenticationService;
import org.jasig.cas.authentication.*;
import org.jasig.cas.authentication.principal.Service;
import org.jasig.cas.ticket.AbstractTicketException;
import org.jasig.cas.ticket.ServiceTicket;
import org.jasig.cas.ticket.TicketCreationException;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.jasig.cas.web.support.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.binding.message.MessageBuilder;
import org.springframework.binding.message.MessageContext;
import org.springframework.web.util.CookieGenerator;
import org.springframework.webflow.core.collection.LocalAttributeMap;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * Created by Leon.Yu on 2016/12/22.
 */
public class AuthenticationViaFormAction {
    /**
     * Authentication succeeded with warnings from authn subsystem that should be displayed to user.
     */
    public static final String SUCCESS_WITH_WARNINGS = "successWithWarnings";

    /**
     * Authentication failure result.
     */
    public static final String AUTHENTICATION_FAILURE = "authenticationFailure";


    /**
     * Flow scope attribute that determines if authn is happening at a public workstation.
     */
    public static final String PUBLIC_WORKSTATION_ATTRIBUTE = "publicWorkstation";

    /**
     * Logger instance.
     **/
    protected final transient Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Core we delegate to for handling all ticket related tasks.
     */
    @NotNull
    @Autowired
    @Qualifier("centralAuthenticationService")
    private CentralAuthenticationService centralAuthenticationService;

    @NotNull
    @Autowired
    @Qualifier("warnCookieGenerator")
    private CookieGenerator warnCookieGenerator;

    @NotNull
    @Autowired(required = false)
    @Qualifier("defaultAuthenticationSystemSupport")
    private AuthenticationSystemSupport authenticationSystemSupport = new DefaultAuthenticationSystemSupport();

    /**
     * Put public workstation into the flow if request parameter present.
     *
     * @param context the context
     */
    private static void putPublicWorkstationToFlowIfRequestParameterPresent(final RequestContext context) {
        if (StringUtils.isNotBlank(context.getExternalContext()
                .getRequestParameterMap().get(PUBLIC_WORKSTATION_ATTRIBUTE))) {
            context.getFlowScope().put(PUBLIC_WORKSTATION_ATTRIBUTE, Boolean.TRUE);
        }
    }

    /**
     * Adds a warning message to the message context.
     *
     * @param context Message context.
     * @param warning Warning message.
     */
    private static void addWarningToContext(final MessageContext context, final MessageDescriptor warning) {
        final MessageBuilder builder = new MessageBuilder()
                .warning()
                .code(warning.getCode())
                .defaultText(warning.getDefaultMessage())
                .args(warning.getParams());
        context.addMessage(builder.build());
    }

    /**
     * Handle the submission of credentials from the post.
     *
     * @param context        the context
     * @param credential     the credential
     * @param messageContext the message context
     * @return the event
     * @since 4.1.0
     */
    public final Event submit(final RequestContext context, final Credential credential,
                              final MessageContext messageContext) {
        if (isRequestAskingForServiceTicket(context)) {
            return grantServiceTicket(context, credential);
        }

        return createTicketGrantingTicket(context, credential, messageContext);
    }

    public final String validateCaptcha(final RequestContext context, final Credential credential,
                                        final MessageContext messageContext) {
        final HttpServletRequest request = WebUtils.getHttpServletRequest(context);
        HttpSession session = request.getSession();
        String captcha = (String) session.getAttribute(com.google.code.kaptcha.Constants.KAPTCHA_SESSION_KEY);
        session.removeAttribute(com.google.code.kaptcha.Constants.KAPTCHA_SESSION_KEY);

        UsernamePasswordCaptchaCredential upc = (UsernamePasswordCaptchaCredential) credential;
        String submitAuthcodeCaptcha = upc.getCaptcha();


        if (!StringUtils.isNotBlank(submitAuthcodeCaptcha) || !StringUtils.isNotBlank(submitAuthcodeCaptcha)) {
            messageContext.addMessage(new MessageBuilder().code("required.captcha").build());
            return "error";
        }
        if (submitAuthcodeCaptcha.equals(captcha)) {
            return "success";
        }
        messageContext.addMessage(new MessageBuilder().error().code("error.authentication.captcha.bad").build());
        return "error";
    }

    public void setCentralAuthenticationService(final CentralAuthenticationService centralAuthenticationService) {
        this.centralAuthenticationService = centralAuthenticationService;
    }

    public void setWarnCookieGenerator(final CookieGenerator warnCookieGenerator) {
        this.warnCookieGenerator = warnCookieGenerator;
    }

    public void setAuthenticationSystemSupport(final AuthenticationSystemSupport authenticationSystemSupport) {
        this.authenticationSystemSupport = authenticationSystemSupport;
    }

    /**
     * New event based on the given id.
     *
     * @param id the id
     * @return the event
     */
    private Event newEvent(final String id) {
        return new Event(this, id);
    }

    /**
     * New event based on the id, which contains an error attribute referring to the exception occurred.
     *
     * @param id    the id
     * @param error the error
     * @return the event
     */
    private Event newEvent(final String id, final Exception error) {
        return new Event(this, id, new LocalAttributeMap("error", error));
    }

    /**
     * Is request asking for service ticket?
     *
     * @param context the context
     * @return true, if both service and tgt are found, and the request is not asking to renew.
     * @since 4.1.0
     */
    protected boolean isRequestAskingForServiceTicket(final RequestContext context) {
        final String ticketGrantingTicketId = WebUtils.getTicketGrantingTicketId(context);
        final Service service = WebUtils.getService(context);
        return (StringUtils.isNotBlank(context.getRequestParameters().get(CasProtocolConstants.PARAMETER_RENEW))
                && ticketGrantingTicketId != null
                && service != null);
    }

    /**
     * Grant service ticket for the given credential based on the service and tgt
     * that are found in the request context.
     *
     * @param context    the context
     * @param credential the credential
     * @return the resulting event. Warning, authentication failure or error.
     * @since 4.1.0
     */
    protected Event grantServiceTicket(final RequestContext context, final Credential credential) {
        final String ticketGrantingTicketId = WebUtils.getTicketGrantingTicketId(context);
        try {
            final Service service = WebUtils.getService(context);
            final AuthenticationContextBuilder builder = new DefaultAuthenticationContextBuilder(
                    this.authenticationSystemSupport.getPrincipalElectionStrategy());
            final AuthenticationTransaction transaction =
                    AuthenticationTransaction.wrap(credential);
            this.authenticationSystemSupport.getAuthenticationTransactionManager().handle(transaction, builder);
            final AuthenticationContext authenticationContext = builder.build(service);

            final ServiceTicket serviceTicketId = this.centralAuthenticationService.grantServiceTicket(
                    ticketGrantingTicketId, service, authenticationContext);
            WebUtils.putServiceTicketInRequestScope(context, serviceTicketId);
            WebUtils.putWarnCookieIfRequestParameterPresent(this.warnCookieGenerator, context);
            return newEvent(AbstractCasWebflowConfigurer.TRANSITION_ID_WARN);

        } catch (final AuthenticationException e) {
            return newEvent(AUTHENTICATION_FAILURE, e);
        } catch (final TicketCreationException e) {
            logger.warn("Invalid attempt to access service using renew=true with different credential. Ending SSO session.");
            this.centralAuthenticationService.destroyTicketGrantingTicket(ticketGrantingTicketId);
        } catch (final AbstractTicketException e) {
            return newEvent(AbstractCasWebflowConfigurer.TRANSITION_ID_ERROR, e);
        }
        return newEvent(AbstractCasWebflowConfigurer.TRANSITION_ID_ERROR);

    }

    /**
     * Create ticket granting ticket for the given credentials.
     * Adds all warnings into the message context.
     *
     * @param context        the context
     * @param credential     the credential
     * @param messageContext the message context
     * @return the resulting event.
     * @since 4.1.0
     */
    protected Event createTicketGrantingTicket(final RequestContext context, final Credential credential,
                                               final MessageContext messageContext) {
        try {
            final Service service = WebUtils.getService(context);
            final AuthenticationContextBuilder builder = new DefaultAuthenticationContextBuilder(
                    this.authenticationSystemSupport.getPrincipalElectionStrategy());
            final AuthenticationTransaction transaction =
                    AuthenticationTransaction.wrap(credential);
            this.authenticationSystemSupport.getAuthenticationTransactionManager().handle(transaction, builder);
            final AuthenticationContext authenticationContext = builder.build(service);

            final TicketGrantingTicket tgt = this.centralAuthenticationService.createTicketGrantingTicket(authenticationContext);
            WebUtils.putTicketGrantingTicketInScopes(context, tgt);
            WebUtils.putWarnCookieIfRequestParameterPresent(this.warnCookieGenerator, context);
            putPublicWorkstationToFlowIfRequestParameterPresent(context);
            if (addWarningMessagesToMessageContextIfNeeded(tgt, messageContext)) {
                return newEvent(SUCCESS_WITH_WARNINGS);
            }
            return newEvent(AbstractCasWebflowConfigurer.TRANSITION_ID_SUCCESS);

        } catch (final AuthenticationException e) {
            logger.debug(e.getMessage(), e);
            return newEvent(AUTHENTICATION_FAILURE, e);
        } catch (final Exception e) {
            logger.debug(e.getMessage(), e);
            return newEvent(AbstractCasWebflowConfigurer.TRANSITION_ID_ERROR, e);
        }
    }

    /**
     * Add warning messages to message context if needed.
     *
     * @param tgtId          the tgt id
     * @param messageContext the message context
     * @return true if warnings were found and added, false otherwise.
     * @since 4.1.0
     */
    protected boolean addWarningMessagesToMessageContextIfNeeded(final TicketGrantingTicket tgtId, final MessageContext messageContext) {
        boolean foundAndAddedWarnings = false;
        for (final Map.Entry<String, HandlerResult> entry : tgtId.getAuthentication().getSuccesses().entrySet()) {
            for (final MessageDescriptor message : entry.getValue().getWarnings()) {
                addWarningToContext(messageContext, message);
                foundAndAddedWarnings = true;
            }
        }
        return foundAndAddedWarnings;

    }
}
