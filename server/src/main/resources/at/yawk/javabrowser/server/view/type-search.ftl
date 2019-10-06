<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.TypeSearchView" -->
<#import "page.ftl" as page>
<#import "declarationNode.ftl" as declarationNode>

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
      <input type="text" class="search" autocomplete="off" data-target="#result-list" data-hide-empty data-artifact-id="${artifactId.id}" data-include-dependencies="false" placeholder="Search for type…">
      <ul id="result-list"></ul>
    </div>
    <div class="declaration-tree">
      <ul>
        <@ConservativeLoopBlock iterator=topLevelPackages; package>
          <li><@declarationNode.declarationNode package/></li>
        </@ConservativeLoopBlock>
      </ul>
    </div>
  </div>
</@page.page>