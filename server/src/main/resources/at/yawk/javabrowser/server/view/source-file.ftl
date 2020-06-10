<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.SourceFileView" -->
<#import "page.ftl" as page>
<#import "declarationNode.ftl" as declarationNode>

<#assign additionalTitle>
  <span class="source-file-dir">${newInfo.sourceFilePathDir}</span>${newInfo.sourceFilePathFile}
</#assign>
<#assign additionalMenu>
  <a id="alt-versions" href="javascript:showAlternativeSourceFiles([
      <#list alternatives as alternative>{artifact:'${alternative.artifactId}',path:'${alternative.sourceFilePath}'<#if alternative.diffPath??>,diffPath:'${alternative.diffPath}'</#if>},</#list>
      ])"><i class="ij ij-history"></i></a>
</#assign>
<@page.page title="${newInfo.artifactId.id} : ${newInfo.sourceFilePathDir}${newInfo.sourceFilePathFile}" realm=newInfo.realm artifactId=newInfo.artifactId hasSearch=true additionalTitle=additionalTitle additionalMenu=additionalMenu>

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
        Showing changes in
        <span class="foreground-new"><b>${newInfo.artifactId.id}</b>/${newInfo.sourceFilePathDir}${newInfo.sourceFilePathFile} (new version)</span> from
        <span class="foreground-old"><b>${oldInfo.artifactId.id}</b>/${oldInfo.sourceFilePathDir}${oldInfo.sourceFilePathFile} (old version)</span>.
        <span class="diff-stats">
          <span class="foreground-new">+${diff.insertions}</span>
          <span class="foreground-old">-${diff.deletions}</span>
        </span>
      </#if>

      <code><pre><@printerDirective/></pre></code>
    </div>
  </div>

  <div id="tooltip">
  </div>
</@page.page>