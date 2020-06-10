<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.FullTextSearchResultView" -->
<#import "page.ftl" as page>
<#import "fullTextSearchForm.ftl" as ftsf>
<#macro resultPart result>
  <code><pre><@result.renderNextRegionDirective/></pre></code>
  <#if result.hasMore>
    <@resultPart result/>
  </#if>
</#macro>
<@page.page title="Full Text Search" realm=searchRealm!'source' artifactId=searchArtifact!'' additionalTitle="Full Text Search" hasSearch=false>
  <div id="noncode">
    <@ftsf.fullTextSearchForm query=query searchArtifact=searchArtifact!'' />

    <#if searchArtifact?has_content>
      <a href="/fts?query=${query?url}">Search everywhere</a>
    </#if>

    <#if results?has_content>
      <ul>
        <@ConservativeLoopBlock iterator=results; result>
        <#-- @ftlvariable name="result" type="at.yawk.javabrowser.server.view.FullTextSearchResultView.SourceFileResult" -->
          <li class="fts-result">
            <h2><#if !searchArtifact??><a href="/${result.artifactId}">${result.artifactId}</a> / </#if><a href="/${result.artifactId}/${result.path}">${result.path}</a></h2>
            <@resultPart result/>
          </li>
        </@ConservativeLoopBlock>
      </ul>
    <#else>
      <p><i>No results</i></p>
    </#if>
  </div>
</@page.page>