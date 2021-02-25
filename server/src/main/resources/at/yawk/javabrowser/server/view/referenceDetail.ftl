<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.ReferenceDetailView" -->
<#import "page.ftl" as page>
<#assign title>
  References to ${targetBinding}
  <#if sourceArtifactId??>from ${sourceArtifactId}</#if>
  <#if type??>of type '${type.displayName}'</#if>
</#assign>
<#assign head>
  References to ${targetBinding}
  <#if type??>of type '${type.displayName}'</#if>
</#assign>
<#assign additionalMenu>
  <#if sourceArtifactId?? || type??><a href="${baseUri}">Show all results</a></#if>
</#assign>
<@page.page title=title realm=realm newPath=artifactPath! additionalTitle=head additionalMenu=additionalMenu>
  <div id="noncode">
    <div class="reference-detail-table size-expander-wrapper <#if (artifacts?size) gt 10> retracted</#if>">
      <div class="size-expander-target">
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
      <a class="size-expander-expand" href="javascript:"><i>Show all</i></a>
    </div>

    <#if hitResultLimit>
      <span class="hit-result-limit">Cowardly refusing to show more than ${resultLimit} results (${totalCountInSelection} total).
        <a href="${baseUri}?limit=<#if type??>&type=${type}</#if><#if sourceArtifactId??>&type=${sourceArtifactId}</#if>">
          Show me everything</a></span>
    </#if>

    <ul class="reference-detail-list">
      <@ConservativeLoopBlock iterator=results skipNull=true; typeListing>
      <#-- @ftlvariable name="typeListing" type="at.yawk.javabrowser.server.view.ReferenceDetailView.TypeListing" -->
        <li class="type">
          <h2>${typeListing.type.displayName}</h2>

          <ul>
            <@ConservativeLoopBlock iterator=typeListing.artifacts skipNull=true; artifactListing>
            <#-- @ftlvariable name="artifactListing" type="at.yawk.javabrowser.server.view.ReferenceDetailView.ArtifactListing" -->
              <li class="artifact">
                <h3><a href="/${artifactListing.artifactId}">${artifactListing.artifactId}</a></h3>

                <ul>
                  <@ConservativeLoopBlock iterator=artifactListing.sourceFiles skipNull=true; sourceFileListing>
                  <#-- @ftlvariable name="sourceFileListing" type="at.yawk.javabrowser.server.view.ReferenceDetailView.SourceFileListing" -->
                    <li class="source-file">
                      <h4>${sourceFileListing.sourceFile}:</h4>

                      <ul>
                        <#list sourceFileListing.items as row>
                          <li><a href="${row.sourceLocation}">Line ${row.sourceFileLine}</a></li>
                        </#list>
                      </ul>
                    </li>
                  </@ConservativeLoopBlock>
                </ul>
              </li>
            </@ConservativeLoopBlock>
          </ul>
        </li>
      </@ConservativeLoopBlock>
    </ul>
  </div>
</@page.page>