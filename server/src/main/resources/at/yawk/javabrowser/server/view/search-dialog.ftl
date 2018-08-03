<div class="search-dialog-wrapper">
  <div class="search-dialog search-box">
    <input type="text" class="search"
           <#if artifactId??>data-artifact-id="${artifactId.id}"</#if>
           data-target="#search-dialog-list"
           autocomplete="off"
           placeholder="Search for type…">
    <ul id="search-dialog-list"></ul>
  </div>
</div>
<script>
  document.addEventListener('keypress', function (e) {
    if (e.key === "t") {
      openSearch(document.querySelector(".search-dialog-wrapper"));
    } else if (e.key === "Escape") {
      closeSearch();
    }
  });
</script>