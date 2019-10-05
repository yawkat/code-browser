<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.TypeSearchView" -->
<#import "page.ftl" as page>

<@page.page title="${artifactId.id}" artifactId=artifactId>
  <div id="noncode">
      <#include "metadata.ftl">

      <#if dependencies?has_content>
        <h2>Dependencies</h2>
        <ul>
          <#list dependencies as dependency>
            <li>
              <#if dependency.prefix??>
                <a href="/${dependency.prefix}">${dependency.prefix}</a></#if>${dependency.suffix}
            </li>
          </#list>
        </ul>
      </#if>

    <div class="search-box">
      <input type="text" class="search" autofocus autocomplete="off" data-target="#result-list" data-artifact-id="${artifactId.id}" data-include-dependencies="false" data-load-immediately placeholder="Search for type…">
      <ul id="result-list"></ul>
    </div>
  </div>
</@page.page>