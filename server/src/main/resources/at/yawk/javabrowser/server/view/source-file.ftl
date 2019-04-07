<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.SourceFileView" -->
<#import "page.ftl" as page>

<#assign additionalTitle>
  <span class="source-file-dir">${sourceFilePathDir}</span>${sourceFilePathFile}
</#assign>
<#assign additionalMenu>
  <a id="alt-versions" href="javascript:showAlternativeSourceFiles([
      <#list alternatives as alternative>{artifact:'${alternative.artifactId}',path:'${alternative.sourceFilePath}'},</#list>
      ])"></a>
</#assign>
<@page.page title="${artifactId.id} : ${sourceFilePathDir}${sourceFilePathFile}" artifactId=artifactId hasSearch=true additionalTitle=additionalTitle additionalMenu=additionalMenu>
  <#include "metadata.ftl">

  <code><pre>${codeHtml?no_esc}</pre></code>
  <div id="tooltip">
  </div>
</@page.page>