<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.TypeSearchView" -->
<#import "page.ftl" as page>
<#import "declarationNode.ftl" as declarationNode>

<#assign additionalMenu>
  <a id="alt-versions" href="javascript:showAlternativeSourceFiles([
      <#list alternatives as alternative>{artifact:'${alternative.artifact.id}',path:''<#if alternative.diffPath??>,diffPath:'${alternative.diffPath}'</#if>},</#list>
      ])"><i class="ij ij-history"></i></a>
</#assign>
<@page.page title="${artifactId.id}" artifactId=artifactId additionalMenu=additionalMenu>
  <div id="noncode">
      <#include "metadata.ftl">

      <#if oldArtifactId??>
        Showing changes in
        <span class="foreground-new"><b>${artifactId.id}</b> (new version)</span> from
        <span class="foreground-old"><b>${oldArtifactId.id}</b> (old version)</span>.
      </#if>

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

    <#if !oldArtifactId??>
      <div class="search-box">
        <input type="text" class="search" autocomplete="off" data-target="#result-list" data-hide-empty data-artifact-id="${artifactId.id}" data-include-dependencies="false" placeholder="Search for type…">
        <ul id="result-list"></ul>
      </div>
    </#if>
    <div class="declaration-tree">
      <ul>
        <#if oldArtifactId??>
          <#assign diffArtifactId=oldArtifactId.id>
        <#else>
          <#assign diffArtifactId="">
        </#if>
        <@ConservativeLoopBlock iterator=topLevelPackages; package>
          <li><@declarationNode.declarationNode node=package diffArtifactId=diffArtifactId/></li>
        </@ConservativeLoopBlock>
      </ul>
    </div>
  </div>
  <div id="tooltip">
  </div>
</@page.page>