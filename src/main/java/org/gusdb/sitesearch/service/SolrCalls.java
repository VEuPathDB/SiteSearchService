package org.gusdb.sitesearch.service;

import static org.gusdb.fgputil.FormatUtil.NL;
import static org.gusdb.fgputil.FormatUtil.TAB;
import static org.gusdb.fgputil.FormatUtil.urlEncodeUtf8;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.gusdb.fgputil.Tuples.TwoTuple;
import org.gusdb.fgputil.solr.Solr;
import org.gusdb.fgputil.solr.Solr.HttpMethod;
import org.gusdb.fgputil.solr.SolrResponse;
import org.gusdb.sitesearch.service.metadata.DocumentField;
import org.gusdb.sitesearch.service.metadata.Metadata;
import org.gusdb.sitesearch.service.request.Pagination;
import org.gusdb.sitesearch.service.request.SearchRequest;
import org.json.JSONObject;

public class SolrCalls {

  // hard-coded document fields
  public static final String DOCUMENT_TYPE_FIELD = "document-type";
  public static final String ORGANISM_FIELD = "organismsForFilter";
  public static final String PROJECT_FIELD = "project";
  public static final String ID_FIELD = "id";
  public static final String PRIMARY_KEY_FIELD = "primaryKey";
  public static final String SCORE_FIELD = "score";
  public static final String WDK_PRIMARY_KEY_FIELD = "wdkPrimaryKeyString";
  public static final String HYPERLINK_NAME_FIELD = "hyperlinkName";
  public static final String JSON_BLOB_FIELD = "json-blob";

  // hard-coded doc types
  public static final String CATEGORIES_META_DOCTYPE = "document-categories";
  public static final String FIELDS_META_DOCTYPE = "document-fields";
  public static final String BATCH_META_DOCTYPE = "batch-meta";

  // tuning constants
  private static final int FETCH_SIZE_FROM_SOLR = 10000;

  // search constants
  private static final String SORTING_FIELDS = SCORE_FIELD + " desc, " + ID_FIELD + " asc";

  // template for metadata document requests
  private static final Function<String,String> METADOC_REQUEST = docType ->
    "q=*&fq=" + DOCUMENT_TYPE_FIELD + ":(" + docType + ")&fl=" + JSON_BLOB_FIELD + ":[json]&wt=json";

  // two different metadata requests required, defined by document type requested
  private static final String CATAGORIES_METADOC_REQUEST = METADOC_REQUEST.apply(CATEGORIES_META_DOCTYPE);
  private static final String FIELDS_METADOC_REQUEST = METADOC_REQUEST.apply(FIELDS_META_DOCTYPE);

  /**
   * Loads basic metadata (but not facet counts) using two SOLR searches which return:
   * 1. a single categories/documentTypes JSON document, defining doc types and their categories
   * 2. a single documentType fields JSON document, defining fields for each doc type
   * 
   * @return initial metadata object
   */
  public static Metadata initializeMetadata(Solr solr) {
    // initialize metadata object with categories and document data
    Metadata meta = solr.executeQuery(HttpMethod.GET, CATAGORIES_METADOC_REQUEST, true, response -> {
      SolrResponse result = Solr.parseResponse(CATAGORIES_METADOC_REQUEST, response);
      return new Metadata(result);
    });
    // supplement doc types with the fields in those doc types
    return solr.executeQuery(HttpMethod.GET, FIELDS_METADOC_REQUEST, true, response -> {
      SolrResponse result = Solr.parseResponse(FIELDS_METADOC_REQUEST, response);
      return meta.addFieldData(result);
    });
  }

  /**
   * Performs a SOLR search defined by the parameters of the request object and
   * using fields defined by the metadata object
   * 
   * @param solr configured SOLR querying utility
   * @param request request specified by the service caller
   * @param meta metadata object populated by "static" calls to SOLR
   * @param omitResults whether to override pagination and return zero documents
   *           Highlighting will also be turned off since it is not needed.
   * @param applyOrganismFilter whether to apply organism filter
   * @param applyFieldsFilter whether to apply fields filter
   * @param fieldFacetsRequested whether to include field facet counts in request
   * @return SOLR search response
   */
  public static SolrResponse getSearchResponse(Solr solr, SearchRequest request, Metadata meta,
      boolean omitResults, boolean applyOrganismFilter, boolean applyFieldsFilter, boolean fieldFacetsRequested) {

    // don't need any documents in result if only collecting organism facets
    Pagination pagination = omitResults ? new Pagination(0,0) :
      request.getPagination().get(); // should always be present for this call; bug if not

    // select search fields that will be applied to this search
    TwoTuple<List<DocumentField>,Boolean> searchFields = meta.getSearchFields(request, applyFieldsFilter);
    String searchQueryString = getSearchQueryString(request.getSearchText(), searchFields);
    String searchFieldsString = formatFieldsForRequest(searchFields.getFirst());

    String searchFiltersParam = buildQueryFilterParams(request, applyOrganismFilter);
    String fieldQueryFacets = buildFieldQueryFacets(request.getSearchText(), searchFields.getFirst(), fieldFacetsRequested);
    
    String filteredDocsRequest =
        "q=" + urlEncodeUtf8(searchQueryString) +                      // search text
        "&qf=" + urlEncodeUtf8(searchFieldsString) +                   // fields to search
        "&start=" + pagination.getOffset() +                           // first row to return
        "&rows=" + pagination.getNumRecords() +                        // number of documents to return
        "&facet=true" +                                                // use facets
        "&facet.limit=-1" +                                            // turn off max # of facets returned
        "&facet.field=" + DOCUMENT_TYPE_FIELD +                        // declare document-type as facet field
        "&facet.field=" + ORGANISM_FIELD +                             // declare organism as facet field
        fieldQueryFacets +                                             // special field facets
        "&defType=edismax" +                                           // chosen query parser
        "&fl=" + urlEncodeUtf8("* " + SCORE_FIELD) +                   // fields to return
        "&sort=" + urlEncodeUtf8(SORTING_FIELDS) +                     // how to sort results
        (omitResults ? "" : "&hl=true") +                              // turn on highlighting
        (omitResults ? "" : "&hl.fl=*") +                              // highlight matches on all fields
        (omitResults ? "" : "&hl.method=unified") +                    // chosen highlighting method
        searchFiltersParam;                                            // filters to apply to search
    return solr.executeQuery(HttpMethod.POST, filteredDocsRequest, true, resp -> {
      return Solr.parseResponse(filteredDocsRequest, resp);
    });
  }

  private static String getSearchQueryString(String searchText, TwoTuple<List<DocumentField>,Boolean> searchFields) {
    return !searchText.equals("*") ? searchText : searchFields.getSecond() ? "*:*" :
      // special case for raw wildcard; need to explicitly search fields if field filter present
      searchFields.getFirst().stream().map(field -> field.getName() + ":*").collect(Collectors.joining(" "));
  }

  private static String buildFieldQueryFacets(String searchText, List<DocumentField> searchFields, boolean fieldQueryFacetsRequested) {
    return !fieldQueryFacetsRequested || searchFields.isEmpty() ? "" : searchFields.stream()
        .map(field -> "&facet.query=" + urlEncodeUtf8(field.getName() + ":(" + searchText + ")"))
        .collect(Collectors.joining());
  }

  private static String buildQueryFilterParams(SearchRequest request, boolean applyOrganismFilter) {
    // if applyOrganismFilter is false, then still filter on orgs this request cares about (i.e. metadata orgs)
    Optional<List<String>> organisms = applyOrganismFilter ?
        request.getRestrictSearchToOrganisms() :
        request.getRestrictMetadataToOrganisms();

    return
      // add always-on filter to remove metadata and batch doc types from any search results
      "&fq=" + urlEncodeUtf8("-(" + DOCUMENT_TYPE_FIELD + ":(" + CATEGORIES_META_DOCTYPE + "))") +
      "&fq=" + urlEncodeUtf8("-(" + DOCUMENT_TYPE_FIELD + ":(" + FIELDS_META_DOCTYPE + "))") +
      "&fq=" + urlEncodeUtf8("-(" + DOCUMENT_TYPE_FIELD + ":(" + BATCH_META_DOCTYPE + "))") +

      // apply project filter
      // example: -(project:[* TO *] AND -project:(PlasmoDB))
      request.getRestrictToProject().map(project ->
        "&fq=" + urlEncodeUtf8("-(" + PROJECT_FIELD + ":[* TO *] AND -" + PROJECT_FIELD + ":(" + project + "))")
      ).orElse("") +

      // apply docType filter
      // example: document-type:(gene)
      request.getDocTypeFilter().map(filter ->
         "&fq=" + urlEncodeUtf8(DOCUMENT_TYPE_FIELD + ":(" + filter.getDocType() + ")")
      ).orElse("") +

      // apply organism filter only if asked
      // example: -(organism:[* TO *] AND -organism:("Plasmodium falciparum 3D7" OR "Plasmodium falciparum 7G8"))
      organisms.map(orgs ->
        "&fq=" + urlEncodeUtf8("-(" + ORGANISM_FIELD + ":[* TO *] AND -" + ORGANISM_FIELD + ":(" + getOrgFilterCondition(orgs) + "))")
      ).orElse("");
  }

  private static String getOrgFilterCondition(List<String> organisms) {
    return organisms.stream(
        ).map(org -> "\"" + org + "\"")
        .collect(Collectors.joining(" OR "));
  }

  private static String formatFieldsForRequest(List<DocumentField> fields) {
    return fields.isEmpty() ? "" : fields.stream()
        .map(field -> field.getName() + (field.getBoost() == 1 ? "" : ("^" + String.format("%.2f", field.getBoost()))))
        .collect(Collectors.joining(" "));
  }

  public static void writeSearchResponse(Solr solr, SearchRequest request, Metadata meta, OutputStream output) throws IOException {
    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));
    String nextCursorMark = "*";
    String lastCursorMark = null;
    TwoTuple<List<DocumentField>,Boolean> searchFields = meta.getSearchFields(request, true);
    String searchQueryString = getSearchQueryString(request.getSearchText(), searchFields);
    String searchFieldsString = formatFieldsForRequest(searchFields.getFirst());
    String searchFiltersParam = buildQueryFilterParams(request, true);
    String fieldsToReturn = PRIMARY_KEY_FIELD + " " + SCORE_FIELD + " " + PROJECT_FIELD;
    String staticPortionOfRequest =
        "q=" + urlEncodeUtf8(searchQueryString) +          // search text
        "&qf=" + urlEncodeUtf8(searchFieldsString) +       // fields to search
        "&rows=" + FETCH_SIZE_FROM_SOLR +                  // number of documents to return
        "&defType=edismax" +                               // chosen query parser
        "&sort=" + urlEncodeUtf8(SORTING_FIELDS) +         // how to sort results
        "&fl=" + urlEncodeUtf8(fieldsToReturn) +           // fields to return
        "&echoParams=none" +                               // do not echo param info
        searchFiltersParam;                                // filters to apply to search
    while (!nextCursorMark.equals(lastCursorMark)) {
      String requestUrl = staticPortionOfRequest + "&cursorMark=" + urlEncodeUtf8(nextCursorMark);
      SolrResponse response = solr.executeQuery(HttpMethod.POST, requestUrl, true, resp -> {
        return Solr.parseResponse(requestUrl, resp);
      });
      for (JSONObject document : response.getDocuments()) {
        writer.write(document.getJSONArray(PRIMARY_KEY_FIELD).toString());
        writer.write(TAB);
        writer.write(String.valueOf(document.getDouble(SCORE_FIELD)));
        writer.write(TAB);
        writer.write(document.optString(PROJECT_FIELD, ""));
        writer.write(NL);
      }
      lastCursorMark = nextCursorMark;
      nextCursorMark = response.getNextCursorMark().get();
    }
    writer.flush();
  }

}
