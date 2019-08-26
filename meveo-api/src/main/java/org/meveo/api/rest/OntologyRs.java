package org.meveo.api.rest;

import org.jboss.resteasy.annotations.cache.Cache;
import org.meveo.api.rest.impl.BaseRs;
import org.meveo.service.crm.impl.JSONSchemaGenerator;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/ontology")
@Produces({MediaType.APPLICATION_JSON})
public class OntologyRs extends BaseRs {

    @Inject
    private JSONSchemaGenerator jsonSchemaGenerator;

    @GET
    @Cache(maxAge = 86400)
    public String getSchema(@DefaultValue("true") @QueryParam("onlyActivated") boolean onlyActivated, @QueryParam("category") String categoryCode){
        return jsonSchemaGenerator.generateSchema("ontology", onlyActivated, categoryCode);
    }
}
