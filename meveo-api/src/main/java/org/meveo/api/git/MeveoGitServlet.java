/*
 * (C) Copyright 2018-2020 Webdrone SAS (https://www.webdrone.fr/) and contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. This program is
 * not suitable for any direct or indirect application in MILITARY industry See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.meveo.api.git;

import org.eclipse.jgit.http.server.GitServlet;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.representations.AccessToken;
import org.meveo.event.qualifier.git.Commited;
import org.meveo.model.git.GitActionType;
import org.meveo.model.git.GitRepository;
import org.meveo.security.CurrentUser;
import org.meveo.security.MeveoUser;
import org.meveo.service.git.GitHelper;
import org.meveo.service.git.GitRepositoryService;
import org.slf4j.Logger;

import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Servlet enabling git through HTTP(s)
 * @author clement.bareth
 * @lastModifiedVersion 6.4.0
 */
@WebServlet(
        urlPatterns = "/git/*"
)
public class MeveoGitServlet extends GitServlet {

    private static Map<String, GitActionType> SERVICE_ROLE_MAPPING = new HashMap<>();

    static {
        SERVICE_ROLE_MAPPING.put("git-upload-pack", GitActionType.READ);
        SERVICE_ROLE_MAPPING.put("git-receive-pack", GitActionType.WRITE);
    }
	
    @Inject
    @CurrentUser
    private MeveoUser currentUser;

    @Inject
    private GitRepositoryService gitRepositoryService;

    @Inject
    private Logger log;

    @Inject
    @Commited
    private Event<GitRepository> gitRepositoryCommitedEvent;

    @Override
    public void init(ServletConfig config) throws ServletException {
        Map<String, String> initParameters = new HashMap<>();
        initParameters.put("base-path", GitHelper.getGitDirectory(currentUser));
        initParameters.put("export-all", "1");

        ServletConfig servletConfig = new ServletConfig(){

            @Override
            public String getServletName() {
                return "GitServlet";
            }

            @Override
            public ServletContext getServletContext() {
                return config.getServletContext();
            }

            @Override
            public String getInitParameter(String name) {
                return initParameters.get(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(initParameters.keySet());
            }
        };

        setReceivePackFactory(new MeveoReceivePackFactory());

        super.init(servletConfig);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        if(req.getUserPrincipal() == null){
            res.addHeader("WWW-Authenticate", "Basic realm=\"Meveo Git access\", charset=\"UTF-8\"");
            res.getWriter().print("You must be logged to access the Meveo Git server");
            res.setStatus(401);
            return;
        }
        
        KeycloakPrincipal<?> principal = (KeycloakPrincipal<?>) req.getUserPrincipal();
        final AccessToken.Access gitAccess = principal.getKeycloakSecurityContext().getToken().getResourceAccess("git");
        if(gitAccess == null){
            res.setStatus(500);
            res.getWriter().print("Keycloak git client not configured or maybe your are not authorized to access it. Please refer to documentation to see how to configure it.");
            return;
        }

        String service = null;
        if(req.getQueryString() != null) {
        	service = req.getQueryString().replaceAll(".*service=([\\w-]+).*", "$1");
        } else if (req.getRequestURI().matches(".*(git-.*-pack).*")){
        	service = req.getRequestURI().replaceAll(".*(git-.*-pack).*", "$1");
        }

        String code = req.getRequestURL().toString().replaceAll(".*/investigation-core/git/([^/]+).*", "$1");

        boolean authorized;

        final GitActionType gitActionType = SERVICE_ROLE_MAPPING.get(service);
        final GitRepository gitRepository = gitRepositoryService.findByCode(code);

        switch (gitActionType) {
            case READ: authorized = GitHelper.hasReadRole(currentUser, gitRepository);
                break;

            case WRITE: authorized = GitHelper.hasWriteRole(currentUser, gitRepository);
                break;

            default:
                authorized = false;
                log.error("Unmapped service type {}", service);
                break;
        }

        if(!authorized) {
            res.setStatus(403);
            res.getWriter().print("You are not authorized to execute this action");
            return;
        }

        super.service(req, res);

        if(gitActionType == GitActionType.WRITE && req.getMethod().equals("POST")) {
            gitRepositoryCommitedEvent.fire(gitRepository);
        }
    }
}