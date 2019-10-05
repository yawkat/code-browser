<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.SourceFileView" -->
<#import "page.ftl" as page>
<#import "declarationNode.ftl" as declarationNode>

<#assign additionalTitle>
  &nbsp;<span class="source-file-dir">${sourceFilePathDir}</span>${sourceFilePathFile}
</#assign>
<#assign additionalMenu>
  <a id="alt-versions" href="javascript:showAlternativeSourceFiles([
      <#list alternatives as alternative>{artifact:'${alternative.artifactId}',path:'${alternative.sourceFilePath}'},</#list>
      ])"><i class="ij ij-history"></i></a>
</#assign>
<@page.page title="${artifactId.id} : ${sourceFilePathDir}${sourceFilePathFile}" artifactId=artifactId hasSearch=true additionalTitle=additionalTitle additionalMenu=additionalMenu>

  <div id="code">
    <div id="structure">
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

      <code><pre>${codeHtml?no_esc}</pre></code>
    </div>
  </div>

  <div id="tooltip">
  </div>
</@page.page>