<#ftl strip_text=true>
<#macro fullTextSearchForm searchArtifact query="">
    <form class="fts-form" action="/fts" method="get">
      <#if searchArtifact?has_content><input type="hidden" name="artifactId" value="${searchArtifact.id}"></#if>
      <#if searchArtifact?has_content>
        <#assign text>Search for text in ${searchArtifact.id}…</#assign>
      <#else>
        <#assign text>Search for text…</#assign>
      </#if>
      <input type="text" name="query" placeholder="${text}" title="${text}" value="${query}">
    </form>
</#macro>