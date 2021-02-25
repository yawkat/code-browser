<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.SourceFileView" -->
<#import "page.ftl" as page>
<#import "declarationNode.ftl" as declarationNode>
<#import "alternatives.ftl" as at>
<#import "diffStats.ftl" as diffStats>

<#assign additionalMenu>
    <@at.alternatives alternatives/>
</#assign>
<@page.page title="${newInfo.sourceFilePath.artifact.stringId} : ${newInfo.sourceFilePath.sourceFilePath}" realm=newInfo.realm newPath=newInfo.sourceFilePath oldPath=(oldInfo.sourceFilePath)! hasSearch=true additionalMenu=additionalMenu tooltip=true narrow=false>

  <div id="code">
    <div class="declaration-tree structure">
      <ul>
        <@ConservativeLoopBlock iterator=declarations; declaration>
          <li class="expanded-on-desktop">
            <@declarationNode.declarationNode declaration/>
          </li>
        </@ConservativeLoopBlock>
      </ul>
    </div>

    <div id="code-body">
      <#include "metadata.ftl">

      <#if oldInfo??>
        <@diffStats.diffStats newInfo.sourceFilePath oldInfo.sourceFilePath/>
        <span class="diff-stats">
          <span class="foreground-new">+${diff.insertions}</span>
          <span class="foreground-old">-${diff.deletions}</span>
        </span>
      </#if>

      <code><pre><@printerDirective/></pre></code>
    </div>
  </div>
</@page.page>