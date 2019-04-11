<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.ReferenceDetailView" -->
<#import "page.ftl" as page>
<#import "path.ftl" as path>
<#assign title>
  References to ${targetBinding}
  <#if sourceArtifactId??>from ${sourceArtifactId}</#if>
  <#if type??>of type '${type.displayName}'</#if>
</#assign>
<#assign head>
  <@path.home/>
  References to ${targetBinding}
  <#if sourceArtifactId??>from <a href="/${sourceArtifactId}">${sourceArtifactId}</a></#if>
  <#if type??>of type '${type.displayName}'</#if>
</#assign>
<#assign additionalMenu>
  <#if sourceArtifactId?? || type??><a href="${baseUri}">Show all results</a></#if>
</#assign>
<@page.page title=title artifactId="" additionalTitle=head additionalMenu=additionalMenu>
  <div class="reference-detail-table">
    <table>
      <thead>
      <tr>
        <th></th>
        <#list countsByType?keys as type>
          <th class="rotate small">${type.displayName}</th>
        </#list>
        <th class="rotate">Total</th>
      </tr>
      <tr>
        <th>Total</th>
        <#list countsByType as type, count>
          <th><a href="${baseUri}?type=${type}">${count}</a></th>
        </#list>
        <th><a href="${baseUri}">${totalCount}</a></th>
      </tr>
      </thead>
      <tbody>
      <#list artifacts as artifactId>
        <tr>
          <th class="small"><a href="/${artifactId}">${artifactId}</a></th>
          <#list countsByType?keys as type>
            <td><a href="${baseUri}?type=${type}&fromArtifact=${artifactId}">${countTable.get(artifactId, type)!""}</a>
            </td>
          </#list>
          <th><a href="${baseUri}?fromArtifact=${artifactId}">${countsByArtifact[artifactId]}</a></th>
        </tr>
      </#list>
      </tbody>
    </table>
  </div>

  <#if hitResultLimit>
    <span class="hit-result-limit">Cowardly refusing to show more than ${resultLimit} results (${totalCountInSelection} total).
      <a href="${baseUri}?limit=<#if type??>&type=${type}</#if><#if sourceArtifactId??>&type=${sourceArtifactId}</#if>">
        Show me everything</a></span>
  </#if>

  <ul class="reference-detail-list">
    <#list results as typeListing>
    <#-- @ftlvariable name="typeListing" type="at.yawk.javabrowser.server.view.ReferenceDetailView.TypeListing" -->
      <#if !typeListing??><#continue></#if>
      <li class="type">
        <h2>${typeListing.type.displayName}</h2>

        <ul>
          <#list typeListing.artifacts as artifactListing>
          <#-- @ftlvariable name="artifactListing" type="at.yawk.javabrowser.server.view.ReferenceDetailView.ArtifactListing" -->
            <#if !artifactListing??><#continue></#if>
            <li class="artifact">
              <h3><a href="/${artifactListing.artifactId}">${artifactListing.artifactId}</a></h3>

              <ul>
                <#list artifactListing.sourceFiles as sourceFileListing>
                <#-- @ftlvariable name="sourceFileListing" type="at.yawk.javabrowser.server.view.ReferenceDetailView.SourceFileListing" -->
                  <#if !sourceFileListing??><#continue></#if>
                  <li class="source-file">
                    <h4>${sourceFileListing.sourceFile}:</h4>

                    <ul>
                      <#list sourceFileListing.items as row>
                        <li><a href="${row.sourceLocation}">Line ${row.sourceFileLine}</a></li>
                      </#list>
                    </ul>
                  </li>
                </#list>
              </ul>
            </li>
          </#list>
        </ul>
      </li>
    </#list>
  </ul>
</@page.page>