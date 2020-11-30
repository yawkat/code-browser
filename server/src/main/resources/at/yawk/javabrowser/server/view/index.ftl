<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.IndexView" -->
<#import "page.ftl" as page>
<#import "fullTextSearchForm.ftl" as ftsf>
<@page.page title="Java Browser" realm='source' artifactId=artifactId hasSearch=(artifactId.stringId == "")>
  <div id="noncode">
    <#if artifactId.stringId == "">
      <div class="message-box">
        <p>This site allows you to explore the source code of the OpenJDK standard library and a selected number of popular maven libraries. Either select an artifact below, or <a href="javascript:SearchDialog.instance.open()">search for a class directly</a>. <br><br>

          Serving <b>${siteStatistics.artifactCount}</b> artifacts with <b>${siteStatistics.sourceFileCount}</b>&nbsp;source files containing <b>${siteStatistics.classCount}</b>&nbsp;classes, <b>${siteStatistics.bindingCount}</b>&nbsp;total bindings, <b>${siteStatistics.referenceCount}</b>&nbsp;references, <b>${siteStatistics.lexemeCountNoSymbols}</b> words and <b>${siteStatistics.lexemeCountWithSymbols - siteStatistics.lexemeCountNoSymbols}</b> other tokens.

          <br><br>

          <a href="https://github.com/yawkat/java-browser">Contribute on GitHub</a>
        </p>
      </div>
    </#if>

    <#if artifactId.stringId == "">
      <@ftsf.fullTextSearchForm query='' searchArtifact=''/>
      <div class="search-box">
        <div class="search-spinner-wrapper">
          <input type="text" class="search" autofocus autocomplete="off" data-target="#result-list"
                 placeholder="Search for type…" data-hide-empty>
          <div class="spinner"></div>
        </div>
        <ul id="result-list"></ul>
      </div>
    </#if>

    <h2>Artifacts</h2>
    <ul>
      <#list artifactId.flattenedChildren as child>
        <li><a href="/${child.stringId}">${child.stringId}</a></li>
      </#list>
    </ul>
  </div>
</@page.page>