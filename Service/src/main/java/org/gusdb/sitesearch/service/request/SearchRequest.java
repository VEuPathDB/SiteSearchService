package org.gusdb.sitesearch.service.request;

import static java.util.Arrays.asList;
import static org.gusdb.fgputil.json.JsonUtil.toStringArray;

import java.util.List;
import java.util.Optional;

import org.gusdb.sitesearch.service.exception.InvalidRequestException;
import org.json.JSONObject;

/**
 * inputSchema = {
 *   searchText: string,
 *   pagination: {
 *     offset: integer,
 *     numRecords: integer (max 50)
 *   },
 *   restrictToProject?: string,
 *   restrictMetadataToOrganisms?: string[],
 *   restrictSearchToOrganisms?: string[], (must be subset of metadata orgs)
 *   documentTypeFilter?: {
 *     documentType: string,
 *     foundOnlyInFields?: string[]
 *   }
 * }
 */
public class SearchRequest {

  private final String _searchText;
  private final Pagination _pagination;
  private final String _restrictToProject;
  private final List<String> _restrictMetadataToOrganisms;
  private final List<String> _restrictSearchToOrganisms;
  private final DocTypeFilter _filter;

  public SearchRequest(JSONObject requestJson, boolean expectAndRequirePagination, boolean requireDocTypeFilter, boolean disallowFieldFilters) {
    _searchText = translateSearchText(requestJson.getString("searchText"));
    if (expectAndRequirePagination) {
      _pagination = new Pagination(requestJson.getJSONObject("pagination"));
      if (_pagination.getNumRecords() > 50)
        throw new InvalidRequestException("numRecords must be <= 50");
    }
    else {
      if (requestJson.has("pagination")) {
        throw new InvalidRequestException("pagination property is not allowed");
      }
      _pagination = null;
    }
    _restrictToProject = requestJson.optString("restrictToProject", null);
    _restrictMetadataToOrganisms = getArrayValues(requestJson, "restrictMetadataToOrganisms");
    _restrictSearchToOrganisms = getArrayValues(requestJson, "restrictSearchToOrganisms");
    ensureSubset(_restrictSearchToOrganisms, _restrictMetadataToOrganisms);
    _filter = !requestJson.has("documentTypeFilter") ? null :
      new DocTypeFilter(requestJson.getJSONObject("documentTypeFilter"));
    if (_filter == null && requireDocTypeFilter) {
      throw new InvalidRequestException("'documentTypeFilter' and contained 'documentType' properties are required at this endpoint.");
    }
    if (_filter != null && _filter.getFoundOnlyInFields().isPresent() && disallowFieldFilters) {
      throw new InvalidRequestException("Field filters ('foundOnlyInFields' property) are not allowed at this endpoint.");
    }
  }

  private static void ensureSubset(
      List<String> restrictSearchToOrganisms,
      List<String> restrictMetadataToOrganisms) {
    if (restrictSearchToOrganisms == null || restrictMetadataToOrganisms == null) return;
    for (String org : restrictSearchToOrganisms) {
      if (!restrictMetadataToOrganisms.contains(org)) {
        throw new InvalidRequestException("All organisms in search must exist in organism metadata list");
      }
    }
  }

  public static List<String> getArrayValues(JSONObject requestJson, String key) {
    List<String> values = !requestJson.has(key) ? null :
      asList(toStringArray(requestJson.getJSONArray(key)));
    return values != null && values.isEmpty() ? null : values;
  }

  public SearchRequest(String searchText, int offset, int numRecords,
      Optional<String> docTypeFilter, Optional<String> projectIdFilter) {
    _searchText = translateSearchText(searchText);
    _pagination = new Pagination(offset, numRecords);
    _restrictToProject = projectIdFilter.orElse(null);
    _filter = docTypeFilter.map(docType -> new DocTypeFilter(new JSONObject().put("documentType", docType))).orElse(null);
    _restrictMetadataToOrganisms = null;
    _restrictSearchToOrganisms = null;
  }

  /**
   * Hack to prevent false positives based on the way we are configuring SOLR.
   * Will split on whitespace, then wrap each token with double-quotes, then
   * rejoin them with a space delimiter.
   *
   * @param rawSearchText input string from the user
   * @return translated search term string that behaves better
   */
  private String translateSearchText(String rawSearchText) {
    return rawSearchText;
    /* TODO: may not need this any more, delete when we're sure
    return Arrays.stream(rawSearchText.split("\\s"))
        .map(token -> token.indexOf("*") == -1 ? "\"" + token + "\"" : token)
        .collect(Collectors.joining(" ")); */
  }

  public String getSearchText() {
    return _searchText;
  }

  public Optional<Pagination> getPagination() {
    return Optional.ofNullable(_pagination);
  }

  public Optional<String> getRestrictToProject() {
    return Optional.ofNullable(_restrictToProject);
  }

  public Optional<List<String>> getRestrictMetadataToOrganisms() {
    return Optional.ofNullable(_restrictMetadataToOrganisms);
  }

  public Optional<List<String>> getRestrictSearchToOrganisms() {
    return Optional.ofNullable(_restrictSearchToOrganisms);
  }

  public Optional<DocTypeFilter> getDocTypeFilter() {
    return Optional.ofNullable(_filter);
  }

  public boolean hasOrganismFilter() {
    return getRestrictSearchToOrganisms().isPresent();
  }

  public boolean hasDocTypeFilter() {
    return getDocTypeFilter().isPresent();
  }

  public boolean hasDocTypeFilterAndFields() {
    return getDocTypeFilter().isPresent() &&
        getDocTypeFilter().get().getFoundOnlyInFields().isPresent();
  }

}
