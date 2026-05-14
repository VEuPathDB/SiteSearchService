package org.gusdb.sitesearch.service;

import static org.gusdb.sitesearch.service.request.SearchRequest.MAX_RECORDS_IN_TABULAR_RESPONSE;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Optional;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gusdb.fgputil.runtime.BuildStatus;
import org.gusdb.fgputil.server.RESTServer;
import org.gusdb.fgputil.solr.Solr;
import org.gusdb.fgputil.solr.SolrResponse;
import org.gusdb.fgputil.web.MimeTypes;
import org.gusdb.sitesearch.service.exception.InvalidRequestException;
import org.gusdb.sitesearch.service.metadata.Metadata;
import org.gusdb.sitesearch.service.request.SearchRequest;
import org.gusdb.sitesearch.service.server.Server.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@Path("/")
public class Service {

  private static final Logger LOG = LogManager.getLogger(Service.class);

  private static Solr getSolr() {
    var ctx = RESTServer.getApplicationContext();
    return new Solr(joinUrl((String)ctx.get(Context.SOLR_URL), (String)ctx.get(Context.SOLR_CORE)));
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response runSearch(
      @QueryParam("searchText") @DefaultValue("*") String searchText,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("numRecords") @DefaultValue("20") int numRecords,
      @QueryParam("projectId") String projectId,
      @QueryParam("docType") String docType) {
    return handleSearchRequest(getSolr(), new SearchRequest(searchText,
        offset, numRecords, Optional.ofNullable(docType), Optional.ofNullable(projectId)));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response runSearch(String body) {
    return handleSearchRequest(getSolr(), new SearchRequest(new JSONObject(body), true, false, false));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MimeTypes.ND_JSON)
  public Response getStreamingResults(String body) {
    return handleStreamRequest(getSolr(), new SearchRequest(new JSONObject(body), false, true, false));
  }

  /**
   * SOLR Response:
   *
   * <pre>
   * {
   *   "suggest": {
   *     "default": {
   *       "<term>": {
   *         "numFound": 10,
   *         "suggestions": [
   *           {
   *             "term": "<match>",
   *             "weight": 0,
   *             "payload": ""
   *           }
   *         ]
   *       }
   *     }
   *   }
   * }
   * </pre>
   */
  @GET
  @Path("/suggest")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getSuggestions(@QueryParam("searchText") String searchText) {
    // Return an empty list for requests that contain no search text or a search
    // term that is fewer than 3 characters.
    if (searchText == null || searchText.isBlank() || searchText.trim().length() < 3)
      return Response.ok("[]", MediaType.APPLICATION_JSON_TYPE).build();

    // Build the SOLR request URL
    var url = (String) RESTServer.getApplicationContext().get(Context.SOLR_URL);
    var ep = joinUrl(url, RESTServer.getApplicationContext().get(Context.SOLR_CORE) + "/suggest");
    var q = ep + "?suggest.q=" + URLEncoder.encode(searchText, Charset.defaultCharset());

    // Open the connection to SOLR
    try(var stream = new URL(q).openConnection().getInputStream()) {
      // Parse the response
      var node = new JSONObject(new String(stream.readAllBytes()));

      node = node.getJSONObject("suggest")
        .getJSONObject("default");

      if (node.length() > 1)
        throw new InternalServerErrorException("unexpected response from SOLR: suggest.default object contained more than one suggestion set");
      if (node.length() < 1)
        throw new InternalServerErrorException("unexpected response from SOLR: suggest.default object contained no suggestion sets");

      node = node.getJSONObject(node.keys().next());

      if (node.getInt("numFound") < 1)
        return Response.ok("[]", MediaType.APPLICATION_JSON_TYPE).build();

      var suggestions = node.getJSONArray("suggestions");
      var out = new JSONArray(suggestions.length());

      for (var i = 0; i < suggestions.length(); i++) {
        out.put(suggestions.getJSONObject(i).getString("term"));
      }

      return Response.ok(out.toString(), MediaType.APPLICATION_JSON_TYPE).build();
    } catch (IOException e) {
      LOG.error("failed to connect to SOLR: ", e);
      throw new InternalServerErrorException("failed to connect to SOLR");
    } catch (JSONException e) {
      LOG.error("could not parse JSON response from SOLR", e);
      throw new InternalServerErrorException("could not parse JSON response from SOLR: " + e.getMessage());
    }
  }

  @GET
  @Path("/categories-metadata")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getCategoriesJson(@QueryParam("projectId") String projectId) {
    LOG.info("Request received for categories metadata");
    Metadata meta = SolrCalls.initializeMetadata(getSolr());
    return Response.ok(
      new JSONObject()
        .put("categories", meta.getCategoriesJson())
        .put("documentTypes", meta.getDocumentTypesJson(Optional.ofNullable(projectId)))
        .toString(2)
    ).build();
  }

  @GET
  @Path("/build-status")
  @Produces(MediaType.TEXT_PLAIN)
  public Response getBuildStatus() {
    return Response.ok(BuildStatus.getLatestBuildStatus()).build();
  }

  @GET
  @Path("/cores")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getCoresInfo() {
    var url = (String) RESTServer.getApplicationContext().get(Context.SOLR_URL);
    var ep = joinUrl(url, "admin/cores");
    try(var stream = new URL(ep).openConnection().getInputStream()) {
      return Response.ok(new String(stream.readAllBytes())).build();
    } catch (IOException e) {
      LOG.error("failed to connect to SOLR: ", e);
      throw new InternalServerErrorException("failed to connect to SOLR");
    }
  }

  private static Response handleSearchRequest(Solr solr, SearchRequest request) {

    // initialize metadata (2 SOLR calls for docTypes and fields)
    Metadata meta = SolrCalls.initializeMetadata(solr);
    meta.validateRequest(request);

    // get response with all filters in request applied (will produce results to deliver)
    boolean fieldFacetsRequested = request.hasDocTypeFilter();
    SolrResponse searchResults = SolrCalls.getSearchResponse(solr, request, meta, false, true, true, fieldFacetsRequested);

    // apply facets
    meta.applyDocTypeFacetCounts(searchResults.getFacetCounts());
    meta.setOrganismFacetCounts(request.getRestrictMetadataToOrganisms(), searchResults.getFacetCounts());
    if (fieldFacetsRequested) {
      meta.setFieldFacetCounts(request.getDocTypeFilter(), searchResults.getFacetQueryResults());
    }

    // At this point:
    //  - doc type facets are correct because: if doc type filter applied, we only need a count for that type
    //  - organism facets are wrong if org filter present; recalculate with org filter off
    //  - field facets are wrong if field filter present; recalculate with field filter off

    if (request.hasOrganismFilter()) {
      // need another call; one without organism filter applied to get org facets
      SolrResponse facetResponse = SolrCalls.getSearchResponse(solr, request, meta, true, false, true, false);
      meta.setOrganismFacetCounts(request.getRestrictMetadataToOrganisms(), facetResponse.getFacetCounts());
    }

    if (request.hasDocTypeFilterAndFields()) {
      // need another call; one without fields filtering applied to get field facets
      SolrResponse facetResponse = SolrCalls.getSearchResponse(solr, request, meta, true, true, false, true);
      meta.setFieldFacetCounts(request.getDocTypeFilter(), facetResponse.getFacetQueryResults());
    }

    return Response.ok(ResultsFormatter.formatResults(meta, searchResults, request.getRestrictToProject()).toString(2)).build();
  }

  private static Response handleStreamRequest(Solr solr, SearchRequest request) {

    // initialize metadata (2 SOLR calls for docTypes and fields)
    Metadata meta = SolrCalls.initializeMetadata(solr);
    meta.validateRequest(request);

    // get stats on this search to test result size against max
    SolrResponse statsResponse = SolrCalls.getSearchResponse(solr, request, meta, true, true, true, false);

    // make sure the resulting document count is not higher than the max
    if (statsResponse.getTotalCount() > MAX_RECORDS_IN_TABULAR_RESPONSE) {
      throw new InvalidRequestException("Maximum number of results (" + MAX_RECORDS_IN_TABULAR_RESPONSE + ") exceeded.");
    }

    return Response.ok(new StreamingOutput() {
      @Override
      public void write(OutputStream output) throws IOException, WebApplicationException {
        // make the search request and stream primary keys to the client
        SolrCalls.writeSearchResponse(solr, request, meta, output);
      }
    }).build();
  }

  private static String joinUrl(String seg1, String seg2) {
    return (seg1.endsWith("/")) ? seg1 + seg2 : seg1 + "/" + seg2;
  }
}
