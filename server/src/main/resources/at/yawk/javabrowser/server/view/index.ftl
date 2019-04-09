<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.IndexView" -->
<#import "page.ftl" as page>
<@page.page title="Java Browser" artifactId=artifactId hasSearch=(artifactId.id == "")>
  <#if artifactId.id == "">
    <div class="message-box">
      <p>This site allows you to explore the source code of the OpenJDK standard library and a selected number of popular maven libraries. Either select an artifact below, or <a href="javascript:SearchDialog.instance.open()">search for a class directly</a>. <br><br>

        <a href="https://github.com/yawkat/java-browser">Contribute on GitHub</a>
      </p>
    </div>
  </#if>

  <#if artifactId.id == "">
    <div class="search-box">
      <input type="text" class="search" autofocus autocomplete="off" data-target="#result-list"
             placeholder="Search for type…" data-hide-empty>
      <ul id="result-list"></ul>
    </div>
  </#if>

  <h2>Artifacts</h2>
  <ul>
    <#list artifactId.flattenedChildren as child>
      <li><a href="/${child.id}">${child.id}</a></li>
    </#list>
  </ul>

  <p class="missing-lib">Missing a library or version you want to see?
    <a href="https://github.com/yawkat/java-browser/issues/new?labels=library">Open an issue!</a></p>
</@page.page>