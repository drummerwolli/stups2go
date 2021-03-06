package org.zalando.stups.go.auth;

import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zalando.stups.tokens.AccessTokens;
import org.zalando.stups.tokens.Tokens;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static java.util.Arrays.asList;

@Extension
public class STUPSAuthenticationPlugin implements GoPlugin {
    private static final Logger LOG = LoggerFactory.getLogger(STUPSAuthenticationPlugin.class);

    public static final String PLUGIN_ID = "stups.authenticator";
    public static final String EXTENSION_NAME = "authentication";
    private static final List<String> goSupportedVersions = asList("1.0");

    public static final String PLUGIN_AUTHENTICATION_CONFIGURATION = "go.authentication.plugin-configuration";
    public static final String SEARCH_USER = "go.authentication.search-user";
    public static final String AUTHENTICATE_USER = "go.authentication.authenticate-user";

    public static final int SUCCESS_RESPONSE_CODE = 200;
    public static final int INTERNAL_ERROR_RESPONSE_CODE = 500;

    private static final String ENV_ACCESS_TOKEN_URL = "STUPS_ACCESS_TOKEN_URL";
    private static final String ENV_TEAM_SERVICE_URL = "STUPS_TEAM_SERVICE_URL";
    private static final String ENV_TEAMS = "STUPS_TEAMS";

    private static final String TOKEN_SERVICE = "service";

    private static AccessTokens tokens;

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return new GoPluginIdentifier(EXTENSION_NAME, goSupportedVersions);
    }

    static {
        // plugins are no singletons! use poor-mans singleton code

        // initialize background fetching of service token to get user list
        LOG.info("Initializing STUPS authentication plugin...");

        try {
            tokens = Tokens.createAccessTokensWithUri(new URI(System.getenv(ENV_ACCESS_TOKEN_URL) + "?realm=/services"))
                    .manageToken(TOKEN_SERVICE)
                    .addScope("uid")
                    .done()
                    .start();
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void initializeGoApplicationAccessor(final GoApplicationAccessor goApplicationAccessor) {
        // noop
    }

    @Override
    public GoPluginApiResponse handle(final GoPluginApiRequest goPluginApiRequest) {
        switch (goPluginApiRequest.requestName()) {
            case PLUGIN_AUTHENTICATION_CONFIGURATION:
                return handlePluginAuthenticationConfigurationRequest();
            case SEARCH_USER:
                return handleSearchUserRequest(goPluginApiRequest);
            case AUTHENTICATE_USER:
                return handleAuthenticateUserRequest(goPluginApiRequest);
            default:
                LOG.warn("Unknown request from server to me: " + goPluginApiRequest.requestName());
                return renderResponse(404, null, null);
        }
    }

    private GoPluginApiResponse handlePluginAuthenticationConfigurationRequest() {
        final Map<String, Object> configuration = new HashMap<>();
        configuration.put("display-name", "STUPS");
        configuration.put("supports-password-based-authentication", true);
        configuration.put("supports-user-search", true);
        return renderResponse(SUCCESS_RESPONSE_CODE, null, JSONUtils.toJSON(configuration));
    }

    private GoPluginApiResponse handleSearchUserRequest(GoPluginApiRequest goPluginApiRequest) {
        final Map<String, String> requestBodyMap = (Map<String, String>) JSONUtils.fromJSON(goPluginApiRequest.requestBody());
        final String searchTerm = requestBodyMap.get("search-term");

        final List<Map> searchResults = new ArrayList<>();
        final String[] teams = System.getenv(ENV_TEAMS).split(",");

        for (final String team : teams) {
            // ask team service
            final Map response;
            try {
                response = Request.Get(System.getenv(ENV_TEAM_SERVICE_URL) + "/teams/" + team)
                        .addHeader("Authorization", "Bearer " + tokens.get(TOKEN_SERVICE))
                        .execute()
                        .handleResponse(httpResponse -> {
                            final int status = httpResponse.getStatusLine().getStatusCode();
                            if (status != 200) {
                                throw new IllegalStateException("response status: " + status);
                            }
                            return (Map) JSONUtils.fromJSON(httpResponse.getEntity().getContent());
                        });
            } catch (final Exception e) {
                LOG.warn("Couldn't include team " + team + " in search result because of an error!", e);
                continue;
            }

            final List<String> members = (List<String>) response.get("member");
            for (final String member : members) {
                if (member.contains(searchTerm)) {
                    searchResults.add(getUserJSON(member));
                }
            }
        }

        return renderResponse(SUCCESS_RESPONSE_CODE, null, JSONUtils.toJSON(searchResults));
    }

    private GoPluginApiResponse handleAuthenticateUserRequest(GoPluginApiRequest goPluginApiRequest) {
        final Map<String, Object> requestBodyMap = (Map<String, Object>) JSONUtils.fromJSON(goPluginApiRequest.requestBody());
        final String username = (String) requestBodyMap.get("username");
        final String password = (String) requestBodyMap.get("password");

        // use password credential grant to verify user password
        try {
            final File clientCredentialsFile = new File(System.getenv("CREDENTIALS_DIR"), "client.json");
            final Map<String, String> clientCredentials = (Map<String, String>) JSONUtils.fromJSON(clientCredentialsFile);
            final String basicAuth = clientCredentials.get("client_id") + ":" + clientCredentials.get("client_secret");
            final String basicAuthHash = Base64.getEncoder().encodeToString(basicAuth.getBytes());

            return Request.Post(new URI(System.getenv(ENV_ACCESS_TOKEN_URL) + "?realm=/employees"))
                    .addHeader("Authorization", "Basic " + basicAuthHash)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .bodyForm(
                            new NameValuePair() {
                                @Override
                                public String getName() {
                                    return "grant_type";
                                }

                                @Override
                                public String getValue() {
                                    return "password";
                                }
                            },
                            new NameValuePair() {
                                @Override
                                public String getName() {
                                    return "username";
                                }

                                @Override
                                public String getValue() {
                                    return username;
                                }
                            },
                            new NameValuePair() {
                                @Override
                                public String getName() {
                                    return "password";
                                }

                                @Override
                                public String getValue() {
                                    return password;
                                }
                            },
                            new NameValuePair() {
                                @Override
                                public String getName() {
                                    return "scope";
                                }

                                @Override
                                public String getValue() {
                                    return "uid";
                                }
                            })
                    .execute()
                    .handleResponse(httpResponse -> {
                        final int status = httpResponse.getStatusLine().getStatusCode();
                        if (status != 200) {
                            LOG.warn("Authentication rejected for " + username + ": (" + status + ") "
                                    + httpResponse.getStatusLine().getReasonPhrase());
                            return renderResponse(SUCCESS_RESPONSE_CODE, null, null);
                        }
                        Map<String, Object> userMap = new HashMap<>();
                        userMap.put("user", getUserJSON(username));
                        LOG.info("Authenticated user " + username);
                        return renderResponse(SUCCESS_RESPONSE_CODE, null, JSONUtils.toJSON(userMap));
                    });
        } catch (final Exception e) {
            LOG.error("Couldn't authenticate user!", e);
            return renderResponse(INTERNAL_ERROR_RESPONSE_CODE, null, e.toString());
        }
    }

    private Map<String, String> getUserJSON(String username) {
        Map<String, String> userMap = new HashMap<>();
        userMap.put("username", username);
        return userMap;
    }

    private GoPluginApiResponse renderResponse(final int responseCode, final Map<String, String> responseHeaders, final String responseBody) {
        return new GoPluginApiResponse() {
            @Override
            public int responseCode() {
                return responseCode;
            }

            @Override
            public Map<String, String> responseHeaders() {
                return responseHeaders;
            }

            @Override
            public String responseBody() {
                return responseBody;
            }
        };
    }
}
