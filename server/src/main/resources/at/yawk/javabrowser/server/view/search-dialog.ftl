<div id="search-dialog-wrapper" class="dialog-wrapper">
  <div class="search-dialog search-box">
    <#if artifactId?? && artifactId.id?has_content>
      <small>This search is limited to the artifact
      <a href="/${artifactId.id}">${artifactId.id}</a> and its dependencies.</small></#if>
    <input type="text" class="search"
           <#if artifactId??>data-artifact-id="${artifactId.id}"</#if>
           data-target="#search-dialog-list"
           autocomplete="off"
           placeholder="Search for type…">
    <ul id="search-dialog-list"></ul>
  </div>
</div>