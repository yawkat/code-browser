<#-- @ftlvariable name="artifactId" type="at.yawk.javabrowser.server.artifact.ArtifactNode" -->
<#-- @ftlvariable name="realm" type="at.yawk.javabrowser.Realm" -->
<div id="search-dialog-wrapper" class="dialog-wrapper">
  <div class="search-dialog search-box">
    <#if artifactId?? && artifactId.stringId?has_content>
      <small>This search is limited to the artifact
      <a href="/${artifactId.stringId}">${artifactId.stringId}</a> and its dependencies.</small></#if>
    <input type="text" class="search"
           data-realm="${realm}"
           <#if artifactId??>data-artifact-id="${artifactId.stringId}"</#if>
           data-target="#search-dialog-list"
           autocomplete="off"
           placeholder="Search for type…">
    <ul id="search-dialog-list"></ul>
  </div>
</div>